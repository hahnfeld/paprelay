// SPDX-License-Identifier: GPL-3.0-or-later
//
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
// Based on SomnoTrace, originally created by Ilya Kruchinin (https://github.com/ilyakruchinin).
//
// Message flow derived from SomnoTrace main/as11_ble.c (pair_task, confirm_task,
// do_setup_encrypted_session, handle_notify, get_datetime_impl, spool_one_round),
// Copyright (C) 2026 Ilya Kruchinin, GPL-3.0-or-later with the Section 7(b)
// attribution term reproduced in NOTICE.
// Ported to Kotlin and modified 2026-11-15: request ids are matched, the server
// proof is compared in constant time, a missing download hash is fatal, plaintext
// is refused once a session exists, and all device-supplied values are bounded.
package com.everysoft.paprelay.as11

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Byte pipe to the machine. The Android implementation wraps the GATT link. */
public interface As11Transport {
    /** Bytes received from the machine, in arrival order, in arbitrary chunks. */
    public val incoming: Flow<ByteArray>

    /** Sends one complete frame, splitting it to whatever the link can carry. */
    public suspend fun send(frame: ByteArray)
}

/** Long-term secret established by first pairing. */
public class PairingCredentials(public val clientId: String, public val pairingKey: ByteArray) {
    init {
        require(As11Request.CLIENT_ID.matches(clientId)) { "client id has an unexpected form" }
        require(pairingKey.size == SessionCrypto.KEY_BYTES) { "pairing key has the wrong size" }
    }

    override fun toString(): String = "PairingCredentials(redacted)"
}

/** Remembers session nonces so a replayed session setup can be refused. */
public fun interface NonceRegistry {
    /** Records [nonce]; returns false if it has been seen before. */
    public fun register(nonce: ByteArray): Boolean

    public companion object {
        /** Accepts everything. For tests and one-off diagnostics only. */
        public val AcceptAll: NonceRegistry = NonceRegistry { true }
    }
}

/** The machine's clock as read at a known moment of local time. */
public class MachineClockReading(
    public val machineEpochMs: Long,
    /** Local time when the request left. */
    public val sentAtMs: Long,
    /** Local time when the answer arrived. */
    public val receivedAtMs: Long,
) {
    public val roundTripMs: Long get() = receivedAtMs - sentAtMs

    /** Local minus machine time at the midpoint of the exchange. */
    public val offsetMs: Long get() = sentAtMs + roundTripMs / 2 - machineEpochMs
}

/** One verified round of a stored-data download. */
public class SpoolRound(
    /** Reassembled bytes; empty when the log holds nothing in range. */
    public val data: ByteArray,
    /** SHA-256 of [data], equal to the hash the machine sent. Null only when [data] is empty. */
    public val sha256: ByteArray?,
    /** Where to continue, or null when the log is exhausted. */
    public val next: JsonObject?,
)

public class As11Timeouts(
    public val keyExchangeMs: Long = 30_000,
    public val confirmMs: Long = 15_000,
    public val sessionMs: Long = 10_000,
    public val rpcMs: Long = 10_000,
    public val clockMs: Long = 5_000,
    public val spoolAckMs: Long = 5_000,
    /** Longest silence tolerated between two download fragments. */
    public val spoolStallMs: Long = 30_000,
)

/** Counters for input that was ignored rather than acted on. All zero on a healthy link. */
public class As11Counters internal constructor() {
    internal val plaintextAfterSession = AtomicLong()
    internal val encryptedBeforeSession = AtomicLong()
    internal val unmatchedResponses = AtomicLong()
    internal val ignoredNotifications = AtomicLong()
    internal val malformedMessages = AtomicLong()
    internal val nonCanonicalPadding = AtomicLong()
    internal val strayFragments = AtomicLong()

    public fun snapshot(): Map<String, Long> = mapOf(
        "plaintextAfterSession" to plaintextAfterSession.get(),
        "encryptedBeforeSession" to encryptedBeforeSession.get(),
        "unmatchedResponses" to unmatchedResponses.get(),
        "ignoredNotifications" to ignoredNotifications.get(),
        "malformedMessages" to malformedMessages.get(),
        "nonCanonicalPadding" to nonCanonicalPadding.get(),
        "strayFragments" to strayFragments.get(),
    )
}

/**
 * One connection's worth of conversation with an AirSense 11.
 *
 * Create it when the link is up, then either [pair] (first time) or
 * [startSession] (every later connection), then use the read-only calls.
 * Exactly one request is in flight at a time. Any integrity failure on the
 * encrypted channel ends the conversation: every later call throws.
 *
 * @param scope owns the reader; cancel it (or call [close]) when the link drops.
 * @param nowMs local wall clock, injectable for tests.
 */
public class As11Client(
    private val transport: As11Transport,
    scope: CoroutineScope,
    private val random: SecureRandom = SecureRandom(),
    private val timeouts: As11Timeouts = As11Timeouts(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private class Pending(val id: Int, val answer: CompletableDeferred<JsonObject>)

    private class Fragment(
        val spoolId: Int?,
        val seq: Int,
        val data: ByteArray,
        val status: String?,
        val next: JsonObject?,
        val hashHex: String?,
    )

    public val counters: As11Counters = As11Counters()

    private val deframer = FigDeframer()
    private val rpcLock = Mutex()
    private val pending = AtomicReference<Pending?>(null)
    private val fragments = AtomicReference<Channel<Fragment>?>(null)
    private val failure = AtomicReference<As11Exception?>(null)
    private val sessionKey = AtomicReference<ByteArray?>(null)
    private var nextId = 1000 + random.nextInt(9000)

    private val reader: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            transport.incoming.collect { bytes ->
                for (frame in deframer.push(bytes)) onFrame(frame)
            }
            fail(As11TransportException("link closed"))
        } catch (e: CancellationException) {
            fail(As11TransportException("link closed"))
            throw e
        } catch (e: As11Exception) {
            fail(e)
        } catch (e: Exception) {
            fail(As11TransportException("link failed", e))
        }
    }

    /** True once [startSession] has succeeded on this connection. */
    public val isSecure: Boolean get() = sessionKey.get() != null

    /** Link-level framing statistics. */
    public val discardedBytes: Long get() = deframer.discardedBytes
    public val payloadCrcFailures: Long get() = deframer.payloadCrcFailures

    /**
     * First pairing. The machine displays its code as soon as the exchange
     * starts; [askPasskey] is invoked at that point and should return what the
     * user reads off the screen.
     *
     * This is never retried automatically: every attempt gives a device
     * impersonating the machine one guess at the code.
     *
     * @throws As11AuthException if the code is wrong or the machine cannot prove it knows it.
     */
    public suspend fun pair(askPasskey: suspend () -> String): PairingCredentials = rpcLock.withLock {
        check(!isSecure) { "already in a session" }
        val srp = Srp6aClient(random)
        val start = exchange(As11Request.StartKeyExchange(srp.publicKey), timeouts.keyExchangeMs).resultObject()
        val serverKey = start.string("serverPk", MAX_HEX_CHARS)
        val salt = start.string("salt", MAX_HEX_CHARS)

        val proof = srp.computeProof(serverKey, salt, askPasskey())
        val confirm = try {
            exchange(As11Request.ConfirmKeyExchange(proof.clientProof), timeouts.confirmMs).resultObject()
        } catch (e: As11RpcException) {
            throw As11AuthException("the machine rejected the pairing code")
        }
        val serverProof = Hex.decode(confirm.string("serverConfirmation", MAX_HEX_CHARS), 64)
        if (!MessageDigest.isEqual(serverProof, proof.expectedServerProof)) {
            throw As11AuthException("the machine could not prove it knows the pairing code")
        }
        val clientId = confirm.string("clientId", 64)
        if (!As11Request.CLIENT_ID.matches(clientId)) {
            throw As11ProtocolException("machine issued a client id of unexpected form")
        }
        PairingCredentials(clientId, proof.sharedKey)
    }

    /**
     * Proves knowledge of the pairing key and switches to the encrypted channel.
     *
     * @throws As11AuthException if the machine no longer recognises the pairing,
     *   rejects the proof, or presents a nonce that was already used.
     */
    public suspend fun startSession(credentials: PairingCredentials, nonces: NonceRegistry): Unit = rpcLock.withLock {
        check(!isSecure) { "already in a session" }
        val offer = try {
            exchange(As11Request.RequestSession(credentials.clientId), timeouts.sessionMs).resultObject()
        } catch (e: As11RpcException) {
            throw As11AuthException("the machine does not recognise this pairing")
        }
        val challenge = Hex.decode(offer.string("challenge", MAX_HEX_CHARS), 64)
        val nonce = Hex.decode(offer.string("nonce", MAX_HEX_CHARS), 64)
        if (!nonces.register(nonce)) {
            throw As11AuthException("the machine reused a session nonce")
        }
        try {
            exchange(
                As11Request.CheckSessionIntegrity(SessionCrypto.challengeResponse(credentials.pairingKey, challenge)),
                timeouts.sessionMs,
            )
        } catch (e: As11RpcException) {
            throw As11AuthException("the machine rejected the session proof")
        }
        sessionKey.set(SessionCrypto.deriveSessionKey(credentials.pairingKey, nonce))
    }

    /** Reads the named data-model items. The result is keyed by each item's wire name. */
    public suspend fun get(items: List<GetItem>): JsonObject =
        call(As11Request.Get(items), timeouts.rpcMs).resultObject()

    public suspend fun getVersion(): JsonObject = call(As11Request.GetVersion, timeouts.rpcMs).resultObject()

    /** Reads the machine's clock, bracketed by local time so drift can be measured. */
    public suspend fun getDateTime(): MachineClockReading = rpcLock.withLock {
        val sent = nowMs()
        val result = exchange(As11Request.GetDateTime, timeouts.clockMs).resultObject()
        val received = nowMs()
        val text = result.string("dateTime", 40)
        val machine = try {
            Instant.parse(text).toEpochMilli()
        } catch (e: DateTimeParseException) {
            throw As11ProtocolException("machine sent an unreadable date", e)
        }
        MachineClockReading(machine, sent, received)
    }

    /** Asks the machine to forget this client. Only for the explicit "Forget machine" action. */
    public suspend fun discardPairKey() {
        call(As11Request.DiscardPairKey, timeouts.rpcMs)
    }

    /**
     * Downloads one round of a stored-data log and verifies it end to end:
     * fragments must be numbered 0..n-1 with no gap or repeat, and the SHA-256
     * of the reassembled bytes must equal the hash the machine sends.
     *
     * @throws As11SpoolUnavailableException if the machine reports the data is unavailable.
     * @throws As11ProtocolException on any ordering, size or hash failure.
     */
    public suspend fun pullSpoolRound(
        spool: SpoolName,
        address: SpoolAddress,
        maxSpoolSize: Int = As11Request.DEFAULT_MAX_SPOOL_SIZE,
    ): SpoolRound = rpcLock.withLock {
        val inbox = Channel<Fragment>(Channel.UNLIMITED)
        check(fragments.compareAndSet(null, inbox)) { "a download is already running" }
        try {
            val started = exchange(As11Request.StartSpool(spool, address, maxSpoolSize), timeouts.rpcMs).resultObject()
            val spoolId = started.int("spoolId")
            try {
                exchange(As11Request.PullSpoolFragments(spoolId), timeouts.spoolAckMs)
            } catch (e: As11TimeoutException) {
                // The acknowledgement is advisory; fragments are what matter.
            }
            collectRound(inbox, spoolId, maxSpoolSize)
        } finally {
            fragments.set(null)
            inbox.close()
        }
    }

    /** Stops reading and forgets the session key. Safe to call more than once. */
    public fun close() {
        reader.cancel()
        fail(As11TransportException("closed"))
        sessionKey.getAndSet(null)?.fill(0)
    }

    private suspend fun collectRound(inbox: Channel<Fragment>, spoolId: Int, maxSpoolSize: Int): SpoolRound {
        val received = ArrayList<Fragment>()
        var total = 0L
        var terminal: Fragment? = null
        while (terminal == null) {
            val fragment = withTimeoutOrNull(timeouts.spoolStallMs) { inbox.receive() }
                ?: throw As11TimeoutException("download stalled after ${received.size} fragments")
            if (fragment.spoolId != null && fragment.spoolId != spoolId) {
                counters.strayFragments.incrementAndGet()
                continue
            }
            total += fragment.data.size
            // The machine may overshoot its own limit by a fragment; anything more is abuse.
            if (received.size >= MAX_FRAGMENTS || total > maxSpoolSize.toLong() + As11Request.MAX_FRAGMENT_SIZE) {
                throw As11ProtocolException("download exceeded its size limit")
            }
            received.add(fragment)
            if (fragment.status != null && fragment.status != STATUS_INCOMPLETE) terminal = fragment
        }

        val status = terminal.status!!
        if (status.startsWith("ERROR_")) throw As11SpoolUnavailableException(status)
        if (status != STATUS_DONE && status != STATUS_MORE) {
            throw As11ProtocolException("download ended with unknown status")
        }

        received.sortBy { it.seq }
        for (i in received.indices) {
            if (received[i].seq != i) throw As11ProtocolException("download has a gap or repeat at fragment $i")
        }
        val data = ByteArray(total.toInt())
        var at = 0
        for (fragment in received) {
            fragment.data.copyInto(data, at)
            at += fragment.data.size
        }

        val next = if (status == STATUS_MORE) {
            terminal.next ?: throw As11ProtocolException("download promised more data without an address")
        } else {
            null
        }
        if (data.isEmpty()) return SpoolRound(data, null, next)

        val expected = terminal.hashHex ?: throw As11ProtocolException("download arrived without an integrity hash")
        val actual = MessageDigest.getInstance("SHA-256").digest(data)
        if (!MessageDigest.isEqual(actual, Hex.decode(expected, 32))) {
            throw As11ProtocolException("download failed its integrity hash")
        }
        return SpoolRound(data, actual, next)
    }

    private suspend fun call(request: As11Request.Encrypted, timeoutMs: Long): JsonObject =
        rpcLock.withLock { exchange(request, timeoutMs) }

    /** Sends [request] and returns the machine's whole answer. Caller holds [rpcLock]. */
    private suspend fun exchange(request: As11Request, timeoutMs: Long): JsonObject {
        failure.get()?.let { throw it }
        val id = nextId
        nextId = if (nextId >= MAX_ID) 1000 else nextId + 1
        val answer = CompletableDeferred<JsonObject>()
        pending.set(Pending(id, answer))
        try {
            // A failure recorded between the first check and registration would be missed.
            failure.get()?.let { throw it }
            val json = request.encode(id).toByteArray(Charsets.UTF_8)
            val frame = when (request) {
                is As11Request.Encrypted -> {
                    val key = sessionKey.get() ?: throw IllegalStateException("no session")
                    Fig.encode(Fig.CHANNEL_TX_ENCRYPTED, SessionCrypto.encrypt(key, json, random))
                }
                is As11Request.Plaintext -> {
                    check(!isSecure) { "plaintext is not sent once a session exists" }
                    Fig.encode(Fig.CHANNEL_TX_PLAIN, json)
                }
            }
            try {
                transport.send(frame)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val wrapped = e as? As11Exception ?: As11TransportException("send failed", e)
                fail(wrapped)
                throw wrapped
            }
            val message = withTimeoutOrNull(timeoutMs) { answer.await() }
                ?: throw As11TimeoutException(request.method)
            val error = message["error"]
            if (error != null && error !is JsonNull) {
                val detail = error as? JsonObject
                throw As11RpcException(
                    request.method,
                    (detail?.get("code") as? JsonPrimitive)?.intOrNull,
                    (detail?.get("message") as? JsonPrimitive)?.takeIf { it.isString }?.content?.take(120),
                )
            }
            return message
        } finally {
            pending.set(null)
        }
    }

    private fun onFrame(frame: FigFrame) {
        val key = sessionKey.get()
        val message: JsonObject = if (key != null) {
            if (frame.channel != Fig.CHANNEL_RX_ENCRYPTED) {
                counters.plaintextAfterSession.incrementAndGet()
                return
            }
            val decrypted = SessionCrypto.decrypt(key, frame.payload)
            if (!decrypted.canonicalPadding) counters.nonCanonicalPadding.incrementAndGet()
            // Nothing but well-formed JSON is acceptable on the encrypted channel.
            parseObject(decrypted.plaintext) ?: throw As11ProtocolException("undecodable message on the encrypted channel")
        } else {
            if (frame.channel == Fig.CHANNEL_RX_ENCRYPTED) {
                counters.encryptedBeforeSession.incrementAndGet()
                return
            }
            parseObject(frame.payload) ?: run {
                counters.malformedMessages.incrementAndGet()
                return
            }
        }
        dispatch(message)
    }

    private fun dispatch(message: JsonObject) {
        val id = message["id"]
        val method = (message["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        when {
            method != null && id == null -> {
                if (method == "SpoolFragment") {
                    val inbox = fragments.get()
                    val params = message["params"] as? JsonObject
                    if (inbox == null || params == null) {
                        counters.strayFragments.incrementAndGet()
                    } else {
                        inbox.trySend(parseFragment(params))
                    }
                } else {
                    counters.ignoredNotifications.incrementAndGet()
                }
            }
            method == null && id != null -> {
                val waiting = pending.get()
                val numeric = (id as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                // The machine reports unparseable requests with a null id.
                val anonymousError = id is JsonNull && message["error"] is JsonObject
                if (waiting != null && (numeric == waiting.id || anonymousError)) {
                    waiting.answer.complete(message)
                } else {
                    counters.unmatchedResponses.incrementAndGet()
                }
            }
            else -> counters.malformedMessages.incrementAndGet()
        }
    }

    private fun parseFragment(params: JsonObject): Fragment {
        val seq = (params["seq"] as? JsonPrimitive)?.intOrNull
            ?: throw As11ProtocolException("download fragment has no sequence number")
        val encoded = (params["data"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
        if (encoded.length > MAX_FRAGMENT_CHARS) throw As11ProtocolException("download fragment is too large")
        val data = try {
            Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            throw As11ProtocolException("download fragment is not valid base64", e)
        }
        return Fragment(
            spoolId = (params["spoolId"] as? JsonPrimitive)?.intOrNull,
            seq = seq,
            data = data,
            status = (params["status"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            next = params["nextSpoolAddress"] as? JsonObject,
            hashHex = (params["spoolHash"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
        )
    }

    private fun parseObject(bytes: ByteArray): JsonObject? {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            return null
        }
        return try {
            JSON.parseToJsonElement(text) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun fail(cause: As11Exception) {
        if (!failure.compareAndSet(null, cause)) return
        pending.get()?.answer?.completeExceptionally(cause)
        fragments.get()?.close(cause)
    }

    private fun JsonObject.resultObject(): JsonObject =
        this["result"] as? JsonObject ?: throw As11ProtocolException("answer carries no result object")

    private fun JsonObject.string(name: String, maxChars: Int): String {
        val value = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw As11ProtocolException("answer is missing '$name'")
        if (value.isEmpty() || value.length > maxChars) throw As11ProtocolException("'$name' has an invalid length")
        return value
    }

    private fun JsonObject.int(name: String): Int =
        (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?: throw As11ProtocolException("answer is missing '$name'")

    private companion object {
        const val STATUS_INCOMPLETE = "SPOOL_INCOMPLETE"
        const val STATUS_MORE = "SPOOL_COMPLETE_MORE_DATA_PENDING"
        const val STATUS_DONE = "SPOOL_COMPLETE_NO_MORE_DATA"
        const val MAX_HEX_CHARS = 512
        const val MAX_FRAGMENTS = 2048
        const val MAX_FRAGMENT_CHARS = 8192
        const val MAX_ID = 1_000_000_000
        val JSON = Json { isLenient = false }
    }
}
