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
// proof is compared in constant time, an unverifiable download is refused,
// plaintext is ignored once a session exists, a frame cut short by a lost chunk
// is dropped once later frames prove it dead, a session counts as open only
// after a valid encrypted answer, sends are time-limited, and all
// device-supplied values are bounded.
package com.everysoft.paprelay.as11

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Byte pipe to the machine. The Android implementation wraps the GATT link.
 *
 * Contract:
 * - [incoming] must deliver every byte received since the link came up, in
 *   order, even those that arrive before it is collected (buffer them). It must
 *   complete or throw when the link drops.
 * - [send] either delivers the whole frame or throws. After it throws, the
 *   client assumes part of a frame may be on the wire and stops using the link.
 */
public interface As11Transport {
    public val incoming: Flow<ByteArray>

    public suspend fun send(frame: ByteArray)
}

/** Long-term secret established by first pairing. */
public class PairingCredentials(public val clientId: String, public val pairingKey: ByteArray) {
    init {
        require(As11Request.isAcceptableClientId(clientId)) { "client id has an unexpected form" }
        require(pairingKey.size == SessionCrypto.KEY_BYTES) { "pairing key has the wrong size" }
    }

    override fun toString(): String = "PairingCredentials(redacted)"
}

/**
 * Remembers session nonces so a replayed session setup can be refused.
 *
 * A nonce is recorded only after the peer has proved it holds the pairing key,
 * so a device that merely pretends to be the machine cannot fill the record or
 * push genuine entries out of it.
 */
public interface NonceRegistry {
    /** Whether [nonce] was used by an earlier, proven session. */
    public suspend fun isKnown(nonce: ByteArray): Boolean

    /** Records [nonce] as used. */
    public suspend fun remember(nonce: ByteArray)

    public companion object {
        /** Remembers nothing. For tests and one-off diagnostics only. */
        public val AcceptAll: NonceRegistry = object : NonceRegistry {
            override suspend fun isKnown(nonce: ByteArray): Boolean = false
            override suspend fun remember(nonce: ByteArray) = Unit
        }
    }
}

/** The machine's clock as read at a known moment of local time. */
public class MachineClockReading(
    public val machineEpochMs: Long,
    /** Local wall-clock time just before the request left. */
    public val sentAtMs: Long,
    /** From just before sending to the answer's arrival, on a monotonic clock. */
    public val roundTripMs: Long,
) {
    /**
     * Local minus machine time, taking the machine's reading to have been made
     * at the midpoint of the exchange. Positive when the machine's clock is behind.
     */
    public val offsetMs: Long get() = sentAtMs + roundTripMs / 2 - machineEpochMs
}

/** Values read with `Get`, and the names the machine could not supply. */
public class GetResult(public val values: JsonObject, public val unavailable: List<String>)

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
    /** Longest a single download round may take in total. */
    public val spoolTotalMs: Long = 300_000,
    /** Longest the link may take to accept one request. */
    public val sendMs: Long = 15_000,
    /** Quiet period after which a half-received frame is given up on. */
    public val resyncIdleMs: Long = 1_500,
    /** Pause after session setup before the first encrypted request, as the reference client does. */
    public val sessionSettleMs: Long = 200,
    /** Longest the user may take to type the code the machine is showing. */
    public val passkeyEntryMs: Long = 120_000,
)

/**
 * Counts of input that was ignored rather than acted on. These are diagnostics,
 * not errors: a real machine sends heartbeats on the plaintext channel, for
 * instance. They matter when something else has gone wrong.
 */
public class As11Counters internal constructor() {
    internal val plaintextAfterSession = AtomicLong()
    internal val encryptedBeforeSession = AtomicLong()
    internal val unmatchedResponses = AtomicLong()
    internal val ignoredNotifications = AtomicLong()
    internal val malformedMessages = AtomicLong()
    internal val undecodableEncrypted = AtomicLong()
    internal val nonCanonicalPadding = AtomicLong()
    internal val strayFragments = AtomicLong()
    internal val sessionConfirmationMissing = AtomicLong()

    public fun snapshot(): Map<String, Long> = mapOf(
        "plaintextAfterSession" to plaintextAfterSession.get(),
        "encryptedBeforeSession" to encryptedBeforeSession.get(),
        "unmatchedResponses" to unmatchedResponses.get(),
        "ignoredNotifications" to ignoredNotifications.get(),
        "malformedMessages" to malformedMessages.get(),
        "undecodableEncrypted" to undecodableEncrypted.get(),
        "nonCanonicalPadding" to nonCanonicalPadding.get(),
        "strayFragments" to strayFragments.get(),
        "sessionConfirmationMissing" to sessionConfirmationMissing.get(),
    )
}

/**
 * One connection's worth of conversation with an AirSense 11.
 *
 * Create it when the link is up, then either [pair] (first time) or
 * [startSession] (every later connection), then use the read-only calls.
 * Exactly one request is in flight at a time.
 *
 * Once the conversation fails ([terminated] completes) every call throws that
 * failure, and the owner should drop the link. Always [close] the client: its
 * reader keeps [scope] alive until then.
 *
 * @param scope owns the reader; cancelling it has the same effect as [close].
 * @param nowMs local wall clock.
 * @param elapsedMs a monotonic clock, used for intervals and deadlines.
 */
public class As11Client(
    private val transport: As11Transport,
    scope: CoroutineScope,
    private val random: SecureRandom = SecureRandom(),
    private val timeouts: As11Timeouts = As11Timeouts(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : AutoCloseable {
    private class Answer(val message: JsonObject, val arrivedElapsedMs: Long)

    private class Pending(val id: Int, val answer: CompletableDeferred<Answer>)

    private sealed class Inbound {
        class Piece(
            val spoolId: Int?,
            val seq: Int?,
            val data: ByteArray,
            val status: String?,
            val next: JsonObject?,
            val hashHex: String?,
        ) : Inbound()

        class Unreadable(val spoolId: Int?, val reason: String) : Inbound()
    }

    public val counters: As11Counters = As11Counters()

    private val deframer = FigDeframer()
    private val rpcLock = Mutex()
    private val pending = AtomicReference<Pending?>(null)
    private val fragments = AtomicReference<Channel<Inbound>?>(null)
    private val failure = AtomicReference<As11Exception?>(null)
    private val failed = CompletableDeferred<As11Exception>()
    private val sessionKey = AtomicReference<ByteArray?>(null)
    private var chunksSeen = 0L // guarded by the deframer lock
    private val consecutiveUndecodable = AtomicInteger()
    private val totalUndecodable = AtomicInteger()

    @Volatile
    private var sessionProven = false
    private var nextId = 1000 + random.nextInt(9000)
    private var pairedOnThisLink = false

    private val reader: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            coroutineScope {
                // A failure in either coroutine ends both: the reader never runs half-alive.
                val watchdog = launch { recoverFramesHiddenByLoss() }
                transport.incoming.collect { bytes ->
                    handle {
                        chunksSeen++
                        deframer.push(bytes)
                    }
                }
                watchdog.cancel()
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

    /** Completes with the reason once the conversation can no longer be used. */
    public val terminated: Deferred<As11Exception> get() = failed

    /**
     * True once an encrypted session is open and the machine has proved it by
     * answering on it.
     */
    public val isSecure: Boolean get() = sessionProven && sessionKey.get() != null

    /** Link-level framing statistics. */
    public val discardedBytes: Long get() = deframer.discardedBytes
    public val payloadCrcFailures: Long get() = deframer.payloadCrcFailures
    public val abandonedFrames: Long get() = deframer.abandonedFrames

    /**
     * First pairing. The machine displays its code as soon as the exchange
     * starts, so [askPasskey] is invoked straight away and should return what
     * the user reads off the screen. It is cancelled if the exchange fails.
     *
     * Never retried automatically: every attempt gives a device impersonating
     * the machine one guess at the code. After pairing, open a new connection
     * before calling [startSession], as the reference client does.
     *
     * @throws As11AuthException.PasskeyRejected if the machine rejects the code, or it is not a code at all.
     * @throws As11PairingCancelledException if [askPasskey] is cancelled or takes too long.
     * @throws As11AuthException.MachineUnauthenticated if the other end cannot prove it knows the code.
     */
    public suspend fun pair(askPasskey: suspend () -> String): PairingCredentials = rpcLock.withLock {
        check(sessionKey.get() == null) { "already in a session" }
        val srp = Srp6aClient(random)
        coroutineScope {
            val entry = async {
                try {
                    askPasskey()
                } catch (e: CancellationException) {
                    // The caller's own cancellation propagates; a cancelled prompt is the user backing out.
                    currentCoroutineContext().ensureActive()
                    throw As11PairingCancelledException()
                }
            }
            val start = exchange(As11Request.StartKeyExchange(srp.publicKey), timeouts.keyExchangeMs).resultObject()
            val serverKey = start.string("serverPk", MAX_HEX_CHARS)
            val salt = start.string("salt", MAX_HEX_CHARS)

            // Stop waiting for the user the moment the link is known to be gone, and not for ever.
            val passkey = withTimeoutOrNull(timeouts.passkeyEntryMs) {
                select<String> {
                    entry.onAwait { it }
                    failed.onAwait { throw it }
                }
            } ?: throw As11PairingCancelledException("the pairing code was not entered in time")
            if (!Srp6aClient.isValidPasskey(passkey)) {
                // Not sent: a malformed code would only spend the machine's one attempt.
                throw As11AuthException.PasskeyRejected(null)
            }
            val proof = srp.computeProof(serverKey, salt, passkey)
            val confirm = try {
                exchange(As11Request.ConfirmKeyExchange(proof.clientProof), timeouts.confirmMs).resultObject()
            } catch (e: As11RpcException) {
                if (e.anonymous) throw e
                throw As11AuthException.PasskeyRejected(e)
            }
            val serverProof = Hex.decode(confirm.string("serverConfirmation", MAX_HEX_CHARS), 64)
            if (!MessageDigest.isEqual(serverProof, proof.expectedServerProof)) {
                throw As11AuthException.MachineUnauthenticated("the machine could not prove it knows the pairing code")
            }
            val clientId = confirm.string("clientId", MAX_HEX_CHARS)
            if (!As11Request.isAcceptableClientId(clientId)) {
                throw As11ProtocolException("machine issued a client id of unexpected form")
            }
            pairedOnThisLink = true
            PairingCredentials(clientId, proof.sharedKey)
        }
    }

    /**
     * Proves knowledge of the pairing key and switches to the encrypted channel.
     *
     * If this times out the machine may consider the session open while this
     * client does not, so the connection must be dropped rather than retried.
     *
     * @throws As11AuthException.PairingUnknown if the machine answers our client id with an error.
     * @throws As11AuthException.SessionProofRejected if it rejects the proof.
     * @throws As11AuthException.NonceReplayed if it presents a nonce that was already used.
     * @throws As11AuthException.SessionUnproven if no valid encrypted answer follows.
     */
    public suspend fun startSession(credentials: PairingCredentials, nonces: NonceRegistry): Unit = rpcLock.withLock {
        check(sessionKey.get() == null) { "already in a session" }
        check(!pairedOnThisLink) { "open a new connection after pairing" }
        val offer = try {
            exchange(As11Request.RequestSession(credentials.clientId), timeouts.sessionMs).resultObject()
        } catch (e: As11RpcException) {
            if (e.anonymous) throw e
            throw As11AuthException.PairingUnknown(e)
        }
        val challenge = Hex.decode(offer.string("challenge", MAX_HEX_CHARS), 64)
        val nonce = Hex.decode(offer.string("nonce", MAX_HEX_CHARS), 64)
        if (recorded { nonces.isKnown(nonce) }) throw As11AuthException.NonceReplayed()

        val verdict = try {
            exchange(
                As11Request.CheckSessionIntegrity(SessionCrypto.challengeResponse(credentials.pairingKey, challenge)),
                timeouts.sessionMs,
            ).message
        } catch (e: As11RpcException) {
            if (e.anonymous) throw e
            throw As11AuthException.SessionProofRejected(e)
        }
        when (((verdict["result"] as? JsonObject)?.get("confirmation") as? JsonPrimitive)?.booleanOrNull) {
            true -> Unit
            false -> throw As11AuthException.SessionProofRejected(null)
            // Documented as always present; tolerated and counted until seen on a real machine.
            null -> counters.sessionConfirmationMissing.incrementAndGet()
        }

        sessionKey.set(SessionCrypto.deriveSessionKey(credentials.pairingKey, nonce))
        // The conversation may have failed while the key was being derived.
        failure.get()?.let {
            clearSessionKey()
            throw it
        }
        delay(timeouts.sessionSettleMs)

        // Nothing so far required the other end to know the pairing key. A valid
        // encrypted answer does, so the session counts as open only once one arrives.
        // The reference client sees the first encrypted request go unanswered at times.
        var lastFailure: As11Exception? = null
        for (attempt in 1..SESSION_PROOF_ATTEMPTS) {
            try {
                exchange(As11Request.GetVersion, timeouts.rpcMs)
                sessionProven = true
                break
            } catch (e: As11TimeoutException) {
                lastFailure = e
            } catch (e: As11RpcException) {
                // Even a refusal is a valid encrypted answer.
                sessionProven = true
                break
            }
        }
        if (!sessionProven) {
            val cause = As11AuthException.SessionUnproven(lastFailure)
            fail(cause)
            throw cause
        }
        recorded { nonces.remember(nonce) }
    }

    /**
     * Reads the named data-model items. Items this machine does not have are
     * reported in [GetResult.unavailable] instead of failing the whole read.
     */
    public suspend fun get(items: List<GetItem>): GetResult = try {
        GetResult(call(As11Request.Get(items), timeouts.rpcMs).resultObject(), emptyList())
    } catch (e: As11RpcException) {
        val partial = e.data as? JsonObject
        if (e.code != ERROR_INVALID_OBJECT || partial == null) throw e
        val missing = (partial[INVALID_OBJECTS] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.take(80) }
        GetResult(JsonObject(partial.filterKeys { it != INVALID_OBJECTS }), missing)
    }

    public suspend fun getVersion(): JsonObject = call(As11Request.GetVersion, timeouts.rpcMs).resultObject()

    /** Reads the machine's clock, bracketed by local time so drift can be measured. */
    public suspend fun getDateTime(): MachineClockReading = rpcLock.withLock {
        val sentWall = nowMs()
        val sentElapsed = elapsedMs()
        val answer = exchange(As11Request.GetDateTime, timeouts.clockMs)
        val text = answer.resultObject().string("dateTime", 40)
        val machine = try {
            Instant.parse(text).toEpochMilli()
        } catch (e: DateTimeParseException) {
            throw As11ProtocolException("machine sent an unreadable date", e)
        }
        MachineClockReading(machine, sentWall, (answer.arrivedElapsedMs - sentElapsed).coerceAtLeast(0))
    }

    /** Asks the machine to forget this client. Only for the explicit "Forget machine" action. */
    public suspend fun discardPairKey() {
        call(As11Request.DiscardPairKey, timeouts.rpcMs)
    }

    /**
     * Downloads one round of a stored-data log and verifies it end to end:
     * data-bearing fragments must be numbered 0..n-1 with no gap or repeat, and
     * the SHA-256 of the reassembled bytes must equal the hash the machine sends.
     *
     * A failed round does not end the conversation; it can be pulled again.
     *
     * @throws As11SpoolUnavailableException if the machine reports the data is unavailable.
     * @throws As11SpoolIntegrityException on an ordering, size or hash failure.
     * @throws As11SpoolUnverifiedException if the machine sent no hash.
     * @throws As11SpoolStalledException if the round stops arriving.
     */
    public suspend fun pullSpoolRound(
        spool: SpoolName,
        address: SpoolAddress,
        maxSpoolSize: Int = As11Request.DEFAULT_MAX_SPOOL_SIZE,
    ): SpoolRound = rpcLock.withLock {
        val inbox = Channel<Inbound>(Channel.UNLIMITED)
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
    override fun close() {
        reader.cancel()
        fail(As11TransportException("closed"))
    }

    private suspend fun collectRound(inbox: Channel<Inbound>, spoolId: Int, maxSpoolSize: Int): SpoolRound {
        val deadline = elapsedMs() + timeouts.spoolTotalMs
        val pieces = ArrayList<Inbound.Piece>()
        var total = 0L
        var seen = 0
        var terminal: Inbound.Piece? = null
        while (terminal == null) {
            val wait = minOf(timeouts.spoolStallMs, deadline - elapsedMs())
            val inbound = (if (wait > 0) withTimeoutOrNull(wait) { inbox.receive() } else null)
                ?: throw As11SpoolStalledException(pieces.size)
            // Everything received counts, so a stream of irrelevant fragments cannot keep the round alive.
            if (++seen > MAX_FRAGMENTS) throw As11SpoolIntegrityException("download exceeded its fragment limit")
            when (inbound) {
                is Inbound.Unreadable -> {
                    if (inbound.spoolId != null && inbound.spoolId != spoolId) {
                        counters.strayFragments.incrementAndGet()
                        continue
                    }
                    throw As11SpoolIntegrityException(inbound.reason)
                }
                is Inbound.Piece -> {
                    if (inbound.spoolId != null && inbound.spoolId != spoolId) {
                        counters.strayFragments.incrementAndGet()
                        continue
                    }
                    total += inbound.data.size
                    // The machine may overshoot its own limit by a fragment; anything more is abuse.
                    if (total > maxSpoolSize.toLong() + As11Request.MAX_FRAGMENT_SIZE) {
                        throw As11SpoolIntegrityException("download exceeded its size limit")
                    }
                    if (inbound.data.isNotEmpty()) pieces.add(inbound)
                    if (inbound.status != null && inbound.status != STATUS_INCOMPLETE) terminal = inbound
                }
            }
        }

        val status = terminal.status!!
        if (status.startsWith("ERROR_")) throw As11SpoolUnavailableException(status.take(60))
        if (status != STATUS_DONE && status != STATUS_MORE) {
            throw As11ProtocolException("download ended with unknown status '${status.take(60)}'")
        }

        // Only fragments that carry data are numbered; a bare status fragment need not be.
        if (pieces.any { it.seq == null }) {
            throw As11SpoolIntegrityException("download has a data fragment without a sequence number")
        }
        pieces.sortBy { it.seq }
        for (i in pieces.indices) {
            if (pieces[i].seq != i) throw As11SpoolIntegrityException("download has a gap or repeat at fragment $i")
        }
        val data = ByteArray(total.toInt())
        var at = 0
        for (piece in pieces) {
            piece.data.copyInto(data, at)
            at += piece.data.size
        }

        val next = if (status == STATUS_MORE) {
            terminal.next ?: throw As11ProtocolException("download promised more data without an address")
        } else {
            null
        }
        // A hash is checked whenever one is sent, including for a round that arrived
        // empty: if every data fragment was lost, the hash is what gives it away.
        val hashHex = terminal.hashHex
        if (hashHex == null) {
            if (data.isNotEmpty()) throw As11SpoolUnverifiedException()
            return SpoolRound(data, null, next)
        }
        val expected = try {
            Hex.decode(hashHex, 32)
        } catch (e: As11ProtocolException) {
            throw As11SpoolIntegrityException("download carried a malformed integrity hash")
        }
        val actual = MessageDigest.getInstance("SHA-256").digest(data)
        if (!MessageDigest.isEqual(actual, expected)) {
            throw As11SpoolIntegrityException("download failed its integrity hash")
        }
        return SpoolRound(data, if (data.isEmpty()) null else actual, next)
    }

    private suspend fun call(request: As11Request.Encrypted, timeoutMs: Long): Answer =
        rpcLock.withLock { exchange(request, timeoutMs) }

    /** Sends [request] and returns the machine's answer. Caller holds [rpcLock]. */
    private suspend fun exchange(request: As11Request, timeoutMs: Long): Answer {
        failure.get()?.let { throw it }
        val id = nextId
        nextId = if (nextId >= MAX_ID) 1000 else nextId + 1
        val answer = CompletableDeferred<Answer>()
        val unmatchedBefore = counters.unmatchedResponses.get()
        pending.set(Pending(id, answer))
        try {
            // A failure recorded between the first check and registration would be missed.
            failure.get()?.let { throw it }
            val json = request.encode(id).toByteArray(Charsets.UTF_8)
            val frame = when (request) {
                is As11Request.Encrypted -> {
                    // A private copy, so closing the client cannot blank the key mid-encryption.
                    val key = sessionKey.get()?.copyOf() ?: throw IllegalStateException("no session")
                    try {
                        Fig.encode(Fig.CHANNEL_TX_ENCRYPTED, SessionCrypto.encrypt(key, json, random))
                    } finally {
                        key.fill(0)
                    }
                }
                is As11Request.Plaintext -> {
                    check(sessionKey.get() == null) { "plaintext is not sent once a session exists" }
                    Fig.encode(Fig.CHANNEL_TX_PLAIN, json)
                }
            }
            send(frame)
            val received = withTimeoutOrNull(timeoutMs) { answer.await() }
                // The answer may have landed in the same instant the timer fired.
                ?: if (answer.isCompleted) {
                    answer.await()
                } else {
                    throw As11TimeoutException(request.method, counters.unmatchedResponses.get() - unmatchedBefore)
                }
            val error = received.message["error"]
            if (error != null && error !is JsonNull) {
                val detail = error as? JsonObject
                val responseId = received.message["id"]
                throw As11RpcException(
                    method = request.method,
                    code = (detail?.get("code") as? JsonPrimitive)?.intOrNull,
                    deviceMessage = (detail?.get("message") as? JsonPrimitive)?.takeIf { it.isString }?.content?.take(120),
                    anonymous = responseId == null || responseId is JsonNull,
                    data = detail?.get("data"),
                )
            }
            return received
        } finally {
            pending.set(null)
        }
    }

    private suspend fun send(frame: ByteArray) {
        val sent = try {
            withTimeoutOrNull(timeouts.sendMs) {
                transport.send(frame)
                true
            }
        } catch (e: CancellationException) {
            // Part of a frame may be on the wire: nothing sent after it could be trusted.
            val cause = As11TransportException("send interrupted", e)
            fail(cause)
            currentCoroutineContext().ensureActive() // the caller itself was cancelled
            throw cause // the transport gave up on its own
        } catch (e: Exception) {
            val cause = e as? As11Exception ?: As11TransportException("send failed", e)
            fail(cause)
            throw cause
        }
        if (sent == null) {
            val cause = As11TransportException("the link did not accept the request in time")
            fail(cause)
            throw cause
        }
    }

    /**
     * A frame whose middle chunk was lost would otherwise hold back every frame
     * after it until enough bytes arrived to cover its declared length. When the
     * link has been quiet for a while, look behind the pending frame for complete
     * ones. A frame that is only arriving slowly is never dropped by this.
     */
    private suspend fun recoverFramesHiddenByLoss() {
        var lastSeen = -1L
        var quietTicks = 0
        while (true) {
            delay(timeouts.resyncIdleMs / 2)
            handle {
                val seen = chunksSeen
                quietTicks = if (deframer.hasPartialFrame && seen == lastSeen) quietTicks + 1 else 0
                lastSeen = seen
                if (quietTicks >= 2) {
                    quietTicks = 0
                    deframer.recoverHiddenFrames()
                } else {
                    emptyList()
                }
            }
        }
    }

    /**
     * The one place frames are produced and acted on. Holding the lock keeps the
     * collector and the watchdog from interleaving, so frames are handled one at
     * a time and in order. Nothing in here suspends.
     */
    private fun handle(produce: () -> List<FigFrame>) {
        synchronized(deframer) {
            try {
                produce().forEach(::onFrame)
            } catch (e: As11Exception) {
                throw e
            } catch (e: RuntimeException) {
                // A bug in message handling must not be reported as a bad link.
                throw As11DefectException(e)
            }
        }
    }

    private fun onFrame(frame: FigFrame) {
        val key = sessionKey.get()
        val message: JsonObject = if (key != null) {
            if (frame.channel != Fig.CHANNEL_RX_ENCRYPTED) {
                // The machine sends heartbeats here. Nothing on this channel is acted on.
                counters.plaintextAfterSession.incrementAndGet()
                return
            }
            val parsed = try {
                val decrypted = SessionCrypto.decrypt(key, frame.payload)
                if (!decrypted.canonicalPadding) counters.nonCanonicalPadding.incrementAndGet()
                parseObject(decrypted.plaintext)
            } catch (e: As11ProtocolException) {
                null
            }
            if (parsed == null) {
                // One bad frame is dropped; a run of them means the session key or the link is wrong.
                counters.undecodableEncrypted.incrementAndGet()
                // Each dropped frame costs a request, so a budget for the whole
                // session stops someone damaging one frame in three indefinitely.
                if (consecutiveUndecodable.incrementAndGet() >= MAX_CONSECUTIVE_UNDECODABLE ||
                    totalUndecodable.incrementAndGet() >= MAX_UNDECODABLE_PER_SESSION
                ) {
                    throw As11ProtocolException("the encrypted channel is producing undecodable messages")
                }
                return
            }
            consecutiveUndecodable.set(0)
            parsed
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
        val idAbsent = id == null || id is JsonNull
        val method = (message["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        when {
            // Notifications carry no id, or a null one.
            method != null && idAbsent -> {
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
                // The machine reports requests it could not parse with a null id.
                val anonymousError = id is JsonNull && message["error"] is JsonObject
                if (waiting != null && (numeric == waiting.id || anonymousError)) {
                    waiting.answer.complete(Answer(message, elapsedMs()))
                } else {
                    counters.unmatchedResponses.incrementAndGet()
                }
            }
            else -> counters.malformedMessages.incrementAndGet()
        }
    }

    /** Never throws: a fragment that cannot be read fails its round, not the connection. */
    private fun parseFragment(params: JsonObject): Inbound {
        val spoolId = (params["spoolId"] as? JsonPrimitive)?.intOrNull
        val encoded = (params["data"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
        if (encoded.length > MAX_FRAGMENT_CHARS) return Inbound.Unreadable(spoolId, "download fragment is too large")
        val data = try {
            Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            return Inbound.Unreadable(spoolId, "download fragment is not valid base64")
        }
        return Inbound.Piece(
            spoolId = spoolId,
            seq = (params["seq"] as? JsonPrimitive)?.intOrNull,
            data = data,
            status = (params["status"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            next = params["nextSpoolAddress"] as? JsonObject,
            hashHex = (params["spoolHash"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
        )
    }

    private fun parseObject(bytes: ByteArray): JsonObject? {
        // Trailing NULs are padding some senders count as content.
        var end = bytes.size
        while (end > 0 && bytes[end - 1].toInt() == 0) end--
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(bytes, 0, end))
            .toString()
        return try {
            JSON.parseToJsonElement(text) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun fail(cause: As11Exception) {
        if (!failure.compareAndSet(null, cause)) return
        failed.complete(cause)
        pending.get()?.answer?.completeExceptionally(cause)
        fragments.get()?.close(cause)
        clearSessionKey()
    }

    /** Takes the lock the receive path decrypts under, so the key is never blanked mid-use. */
    private fun clearSessionKey() {
        synchronized(deframer) {
            sessionProven = false
            sessionKey.getAndSet(null)?.fill(0)
        }
    }

    /** Runs a nonce-record operation, turning storage trouble into a typed failure. */
    private suspend fun <T> recorded(operation: suspend () -> T): T = try {
        operation()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw e as? As11Exception ?: As11NonceStoreException(e)
    }

    private fun Answer.resultObject(): JsonObject =
        message["result"] as? JsonObject ?: throw As11ProtocolException("answer carries no result object")

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
        const val ERROR_INVALID_OBJECT = -11201
        const val INVALID_OBJECTS = "InvalidObjects"
        const val MAX_HEX_CHARS = 512
        const val MAX_FRAGMENTS = 2048
        const val MAX_FRAGMENT_CHARS = 8192
        const val MAX_ID = 1_000_000_000
        const val MAX_CONSECUTIVE_UNDECODABLE = 3
        const val MAX_UNDECODABLE_PER_SESSION = 8
        const val SESSION_PROOF_ATTEMPTS = 2
        val JSON = Json { isLenient = false }
    }
}
