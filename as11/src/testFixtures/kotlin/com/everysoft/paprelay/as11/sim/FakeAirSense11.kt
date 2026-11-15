// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11.sim

import com.everysoft.paprelay.as11.As11Transport
import com.everysoft.paprelay.as11.Fig
import com.everysoft.paprelay.as11.FigDeframer
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Misbehaviours the simulated machine can be told to exhibit. */
public class FaultPlan {
    /** Fragment sequence numbers to withhold. */
    public val dropFragments: MutableSet<Int> = mutableSetOf()

    /** Fragment sequence numbers to send twice. */
    public val duplicateFragments: MutableSet<Int> = mutableSetOf()

    /** Deliver each round's fragments in reverse order. */
    public var reverseFragments: Boolean = false

    /** Send a hash that does not match the data. */
    public var corruptSpoolHash: Boolean = false

    /** Send no hash at all. */
    public var omitSpoolHash: Boolean = false

    /** End every round with this status instead of the real one. */
    public var forceSpoolStatus: String? = null

    /** Never send the terminal fragment. */
    public var withholdTerminalFragment: Boolean = false

    /** Methods that receive no answer, as the machine does for denied commands. */
    public val silentMethods: MutableSet<String> = mutableSetOf()

    /** Return a server proof that does not match. */
    public var wrongServerProof: Boolean = false

    /** Reject the session proof even when it is correct. */
    public var rejectSession: Boolean = false

    /** Use this nonce for every session instead of a fresh one. */
    public var fixedNonce: ByteArray? = null

    /** Answer with a different id than the request carried. */
    public var wrongResponseId: Boolean = false

    /** Flip a bit in the ciphertext of this many upcoming encrypted frames. */
    public var corruptEncryptedFrames: Int = 0

    /** What `CheckSessionIntegrity` reports: true, false, or null for a bare `true` result. */
    public var sessionConfirmation: Boolean? = true

    /** Send the terminal status in a fragment of its own, with no data and no sequence number. */
    public var separateTerminalFragment: Boolean = false

    /** Give download fragments an explicit `"id": null`, as JSON-RPC 1.0 notifications have. */
    public var fragmentsCarryNullId: Boolean = false

    /** Before each round, send a fragment belonging to some other download. */
    public var strayFragmentBeforeRound: Boolean = false

    /** Items `Get` reports as unavailable, alongside the ones it can supply. */
    public val unavailableItems: MutableSet<String> = mutableSetOf()

    /** Methods answered with an id-less parse error, as if the request arrived damaged. */
    public val parseErrorMethods: MutableSet<String> = mutableSetOf()

    /** Send a plaintext heartbeat and an encrypted event before every encrypted answer. */
    public var chatter: Boolean = false

    /** How long the machine takes to answer each request. */
    public var responseDelayMs: Long = 0

    /** Lose this chunk (counting from 0) of the next encrypted frame, as a missed notification would. */
    public var dropChunkOfNextEncryptedFrame: Int? = null

    /**
     * With [dropChunkOfNextEncryptedFrame]: lose that chunk and every one after
     * it, as a longer radio dropout would.
     */
    public var dropRestOfFrame: Boolean = false

    /** Let this many encrypted frames through before [dropChunkOfNextEncryptedFrame] applies. */
    public var encryptedFramesBeforeChunkDrop: Int = 0

    /**
     * Behave as a device that holds no key: answer the first encrypted request
     * by sending back its first cipher block under an adjusted IV, so that it
     * decrypts to a bare `{"id":N}`. This is the strongest forgery available
     * without the key, because the encryption has no authentication.
     */
    public var forgeSessionProof: Boolean = false

    /** Accept writes but never complete them. */
    public var hangSend: Boolean = false

    /** Emit this plaintext frame before every encrypted answer. */
    public var plaintextInjection: ByteArray? = null

    /** Close the link after this many more bytes have been sent to the client. */
    public var disconnectAfterBytes: Int? = null
}

/**
 * In-process stand-in for an AirSense 11, implementing the machine's side of
 * the protocol: framing, first pairing, session setup, encrypted commands and
 * stored-data downloads.
 *
 * It shares only the framing code with the client. The key exchange and the
 * session cryptography are implemented here separately so a mistake in the
 * client is not mirrored by the simulator. It is still written from the same
 * understanding of the protocol, so passing against it does not replace
 * testing against a real machine.
 *
 * Like the real machine it holds a single pairing: pairing again replaces it.
 */
public class FakeAirSense11(
    public var passkey: String = "0420",
    public val serialNumber: String = "23212345678",
    seed: Long = 1,
    /** The machine's own clock, which need not agree with anyone else's. */
    public var clockMs: () -> Long = { 1_790_000_000_000L },
) {
    public val faults: FaultPlan = FaultPlan()

    /** Stored data: for each log, the rounds it will hand out in order. */
    public val spools: MutableMap<String, List<ByteArray>> = mutableMapOf()

    /** Methods of every request received in the clear, in order. */
    public val plaintextMethodsSeen: MutableList<String> = mutableListOf()

    /** Methods of every command accepted on the encrypted channel, in order. */
    public val encryptedMethodsSeen: MutableList<String> = mutableListOf()

    /** `fromDateTime` values received, by log name. */
    public val spoolStartTimes: MutableList<Pair<String, String?>> = mutableListOf()

    public var pairedClientId: String? = null
        private set
    private var pairingKey: ByteArray? = null
    private val random = Random(seed)
    private var pairingsIssued = 0

    /** Opens a new link. [chunkSize] is how many bytes each notification carries. */
    public fun connect(chunkSize: Int = 244): As11Transport = Link(chunkSize)

    private inner class Link(private val chunkSize: Int) : As11Transport {
        private val toClient = Channel<ByteArray>(Channel.UNLIMITED)
        private val deframer = FigDeframer()
        private var sessionKey: ByteArray? = null
        private var pendingSessionKey: ByteArray? = null
        private var expectedSessionResponse: ByteArray? = null
        private var srp: ServerSrp? = null
        private var nextSpoolId = 12
        private val openSpools = HashMap<Int, Pair<String, Int>>()
        private var closed = false
        private var lastPlaintextId = 0

        override val incoming: Flow<ByteArray> = toClient.receiveAsFlow()

        override suspend fun send(frame: ByteArray) {
            if (closed) throw java.io.IOException("link closed")
            if (faults.hangSend) awaitCancellation()
            if (faults.responseDelayMs > 0) delay(faults.responseDelayMs)
            // Deliver in small pieces, as GATT writes would arrive.
            var at = 0
            while (at < frame.size) {
                val end = minOf(frame.size, at + chunkSize)
                for (received in deframer.push(frame.copyOfRange(at, end))) onFrame(received.channel, received.payload)
                at = end
            }
        }

        private fun onFrame(channel: Int, payload: ByteArray) {
            when (channel) {
                Fig.CHANNEL_TX_PLAIN -> {
                    if (sessionKey != null) return
                    val request = parse(payload) ?: return
                    val method = request["method"]?.jsonPrimitive?.content ?: return
                    plaintextMethodsSeen += method
                    lastPlaintextId = request["id"]?.jsonPrimitive?.int ?: lastPlaintextId
                    if (method in faults.silentMethods) return
                    if (method in faults.parseErrorMethods) {
                        emit(CHANNEL_RX_PLAIN, PARSE_ERROR.toByteArray())
                        return
                    }
                    val answer = handlePlain(method, request["params"] as? JsonObject ?: JsonObject(emptyMap()))
                    emit(CHANNEL_RX_PLAIN, respond(request, answer).toString().toByteArray())
                    pendingSessionKey?.let {
                        if (method == "CheckSessionIntegrity" && answer !is Failure) sessionKey = it
                    }
                }
                Fig.CHANNEL_TX_ENCRYPTED -> {
                    if (faults.forgeSessionProof) {
                        forgeAnswer(payload)
                        return
                    }
                    val key = sessionKey ?: return
                    val request = parse(decrypt(key, payload) ?: return) ?: return
                    val method = request["method"]?.jsonPrimitive?.content ?: return
                    encryptedMethodsSeen += method
                    if (method in faults.silentMethods) return
                    val answer = handleEncrypted(method, request["params"])
                    sendEncrypted(respond(request, answer))
                    if (method == "PullSpoolFragments" && answer !is Failure) {
                        emitFragments(request["params"]!!.jsonObject["spoolId"]!!.jsonPrimitive.int, request["params"]!!.jsonObject)
                    }
                }
            }
        }

        /** Uses only what a keyless eavesdropper has: the ciphertext and the predictable request. */
        private fun forgeAnswer(payload: ByteArray) {
            val id = lastPlaintextId + 1
            val known = """{"id":$id,"jsonrpc":"2.0","method":"GetVersion"}""".toByteArray()
            val wanted = """{"id":$id}""".toByteArray()
            val original = ByteArray(16)
            original[0] = known.size.toByte()
            known.copyInto(original, 2, 0, 14)
            val replacement = ByteArray(16)
            replacement[0] = wanted.size.toByte()
            wanted.copyInto(replacement, 2)
            val iv = ByteArray(16) { (payload[it].toInt() xor original[it].toInt() xor replacement[it].toInt()).toByte() }
            emit(Fig.CHANNEL_RX_ENCRYPTED, iv + payload.copyOfRange(16, 32))
        }

        private fun handlePlain(method: String, params: JsonObject): Any = when (method) {
            "StartKeyExchange" -> {
                val a = BigInteger(params["clientPk"]!!.jsonPrimitive.content, 16)
                val exchange = ServerSrp(a, passkey, random)
                srp = exchange
                buildJsonObject {
                    put("serverPk", JsonPrimitive(exchange.publicKeyHex))
                    put("salt", JsonPrimitive(hex(exchange.salt)))
                }
            }
            "ConfirmKeyExchange" -> {
                val exchange = srp
                val proof = unhex(params["clientConfirmation"]!!.jsonPrimitive.content)
                if (exchange == null || !proof.contentEquals(exchange.expectedClientProof)) {
                    Failure(-32000, "key exchange failed")
                } else {
                    val id = "client-${++pairingsIssued}-${Integer.toHexString(random.nextInt())}"
                    pairedClientId = id
                    pairingKey = exchange.key
                    val serverProof = exchange.serverProof.copyOf()
                    if (faults.wrongServerProof) serverProof[0] = (serverProof[0].toInt() xor 1).toByte()
                    buildJsonObject {
                        put("clientId", JsonPrimitive(id))
                        put("serverConfirmation", JsonPrimitive(hex(serverProof)))
                        put("nonce", JsonPrimitive(hex(ByteArray(32).also(random::nextBytes))))
                    }
                }
            }
            "RequestSession" -> {
                val key = pairingKey
                if (key == null || params["clientId"]?.jsonPrimitive?.content != pairedClientId) {
                    Failure(-32001, "unknown client")
                } else {
                    val challenge = ByteArray(32).also(random::nextBytes)
                    val nonce = faults.fixedNonce ?: ByteArray(32).also(random::nextBytes)
                    expectedSessionResponse = hmac(key, challenge)
                    pendingSessionKey = sha256(key, nonce)
                    buildJsonObject {
                        put("challenge", JsonPrimitive(hex(challenge)))
                        put("nonce", JsonPrimitive(hex(nonce)))
                    }
                }
            }
            "CheckSessionIntegrity" -> {
                val response = unhex(params["response"]!!.jsonPrimitive.content)
                if (faults.rejectSession || !response.contentEquals(expectedSessionResponse)) {
                    pendingSessionKey = null
                    Failure(-32002, "session integrity failed")
                } else {
                    when (val reported = faults.sessionConfirmation) {
                        null -> JsonPrimitive(true)
                        else -> {
                            if (!reported) pendingSessionKey = null
                            buildJsonObject { put("confirmation", JsonPrimitive(reported)) }
                        }
                    }
                }
            }
            else -> Failure(-32601, "Method not found")
        }

        private fun handleEncrypted(method: String, params: JsonElement?): Any = when (method) {
            "Get" -> {
                val names = (params as JsonArray).map { it.jsonPrimitive.content }
                val missing = names.filter { it in faults.unavailableItems }
                val values = buildJsonObject {
                for (key in names - missing.toSet()) {
                    put(
                        key,
                        when (key) {
                            "SerialNumber" -> JsonPrimitive(serialNumber)
                            "ProductName" -> JsonPrimitive("AirSense 11 AutoSet")
                            "ProductCode" -> JsonPrimitive("39000")
                            "SettingProfiles" -> buildJsonObject { put("TimeZoneOffset", JsonPrimitive(600)) }
                            else -> JsonPrimitive("sim-$key")
                        },
                    )
                }
                }
                if (missing.isEmpty()) {
                    values
                } else {
                    Failure(
                        -11201,
                        "InvalidObject",
                        JsonObject(values + ("InvalidObjects" to JsonArray(missing.map(::JsonPrimitive)))),
                    )
                }
            }
            "GetVersion" -> buildJsonObject { put("FlowGenerator", buildJsonObject { put("sim", JsonPrimitive(true)) }) }
            "GetDateTime" -> buildJsonObject {
                put("dateTime", JsonPrimitive(Instant.ofEpochMilli(clockMs()).toString()))
            }
            "StartSpool" -> {
                val address = (params as JsonObject)["spoolAddress"]!!.jsonObject
                val name = address.keys.single()
                val where = address[name]!!.jsonObject
                spoolStartTimes += name to where["fromDateTime"]?.jsonPrimitive?.content
                val round = where["cursor"]?.jsonPrimitive?.int ?: 0
                val id = nextSpoolId++
                openSpools[id] = name to round
                buildJsonObject { put("spoolId", JsonPrimitive(id)) }
            }
            "PullSpoolFragments" -> {
                val id = (params as JsonObject)["spoolId"]!!.jsonPrimitive.int
                if (id in openSpools) buildJsonObject { put("spoolId", JsonPrimitive(id)) } else Failure(-32602, "Invalid Params")
            }
            "DiscardPairKey" -> {
                pairedClientId = null
                pairingKey = null
                JsonPrimitive(true)
            }
            else -> Failure(-32601, "Method not found")
        }

        private fun emitFragments(spoolId: Int, pull: JsonObject) {
            val (name, round) = openSpools.remove(spoolId) ?: return
            val rounds = spools[name].orEmpty()
            val more = round + 1 < rounds.size
            val forced = faults.forceSpoolStatus
            // An error status comes alone: no data accompanies it.
            val data = if (forced?.startsWith("ERROR_") == true) ByteArray(0) else rounds.getOrElse(round) { ByteArray(0) }
            val size = pull["maxFragmentSize"]!!.jsonPrimitive.int
            val pieces = data.asList().chunked(size) { it.toByteArray() }
            val finalStatus = forced ?: if (more) "SPOOL_COMPLETE_MORE_DATA_PENDING" else "SPOOL_COMPLETE_NO_MORE_DATA"

            fun fragment(seq: Int?, piece: ByteArray?, terminal: Boolean) = buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("method", JsonPrimitive("SpoolFragment"))
                if (faults.fragmentsCarryNullId) put("id", JsonNull)
                put(
                    "params",
                    buildJsonObject {
                        put("spoolId", JsonPrimitive(spoolId))
                        // Only data-bearing fragments are numbered.
                        if (seq != null) put("seq", JsonPrimitive(seq))
                        if (piece != null) put("data", JsonPrimitive(Base64.getEncoder().encodeToString(piece)))
                        put("status", JsonPrimitive(if (terminal) finalStatus else "SPOOL_INCOMPLETE"))
                        if (terminal) {
                            if (!faults.omitSpoolHash && data.isNotEmpty()) {
                                val hash = sha256(data)
                                if (faults.corruptSpoolHash) hash[5] = (hash[5].toInt() xor 0x40).toByte()
                                put("spoolHash", JsonPrimitive(hex(hash).lowercase()))
                            }
                            if (more) {
                                put(
                                    "nextSpoolAddress",
                                    buildJsonObject { put(name, buildJsonObject { put("cursor", JsonPrimitive(round + 1)) }) },
                                )
                            }
                        }
                    },
                )
            }

            val bareTerminal = pieces.isEmpty() || faults.separateTerminalFragment
            val messages = ArrayList<JsonObject>()
            for ((seq, piece) in pieces.withIndex()) {
                if (seq in faults.dropFragments) continue
                val message = fragment(seq, piece, terminal = !bareTerminal && seq == pieces.lastIndex)
                messages += message
                if (seq in faults.duplicateFragments) messages += message
            }
            if (faults.reverseFragments) {
                // Keep the terminal fragment last so the round still completes.
                val last = if (bareTerminal) null else messages.removeLastOrNull()
                messages.reverse()
                last?.let(messages::add)
            }
            if (bareTerminal) messages += fragment(null, null, terminal = true)
            if (faults.withholdTerminalFragment) messages.removeLastOrNull()

            if (faults.strayFragmentBeforeRound) {
                sendEncrypted(
                    buildJsonObject {
                        put("jsonrpc", JsonPrimitive("2.0"))
                        put("method", JsonPrimitive("SpoolFragment"))
                        put(
                            "params",
                            buildJsonObject {
                                put("spoolId", JsonPrimitive(spoolId - 1))
                                put("seq", JsonPrimitive(0))
                                put("data", JsonPrimitive("c3RhbGU="))
                                put("status", JsonPrimitive("SPOOL_COMPLETE_NO_MORE_DATA"))
                            },
                        )
                    },
                )
            }
            messages.forEach(::sendEncrypted)
        }

        private fun respond(request: JsonObject, answer: Any): JsonObject = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            val id = request["id"] ?: JsonNull
            put("id", if (faults.wrongResponseId) JsonPrimitive(id.jsonPrimitive.int + 1) else id)
            if (answer is Failure) {
                put(
                    "error",
                    buildJsonObject {
                        put("code", JsonPrimitive(answer.code))
                        put("message", JsonPrimitive(answer.message))
                        answer.data?.let { put("data", it) }
                    },
                )
            } else {
                put("result", answer as JsonElement)
            }
        }

        private fun sendEncrypted(message: JsonObject) {
            val key = sessionKey ?: return
            faults.plaintextInjection?.let { emit(CHANNEL_RX_PLAIN, it) }
            if (faults.chatter && message["method"] == null) {
                emit(CHANNEL_RX_PLAIN, """{"jsonrpc":"2.0","method":"HeartBeat"}""".toByteArray())
                emit(
                    Fig.CHANNEL_RX_ENCRYPTED,
                    encrypt(key, """{"jsonrpc":"2.0","method":"EventNotification","id":null,"params":{}}""".toByteArray()),
                )
            }
            val payload = encrypt(key, message.toString().toByteArray())
            if (faults.corruptEncryptedFrames > 0) {
                faults.corruptEncryptedFrames--
                payload[payload.size - 1] = (payload[payload.size - 1].toInt() xor 0x01).toByte()
            }
            val dropChunk = if (faults.encryptedFramesBeforeChunkDrop > 0) {
                faults.encryptedFramesBeforeChunkDrop--
                null
            } else {
                faults.dropChunkOfNextEncryptedFrame.also { faults.dropChunkOfNextEncryptedFrame = null }
            }
            emit(Fig.CHANNEL_RX_ENCRYPTED, payload, dropChunk)
        }

        private fun emit(channel: Int, payload: ByteArray, dropChunk: Int? = null) {
            val frame = Fig.encode(channel, payload)
            var at = 0
            var index = 0
            while (at < frame.size && !closed) {
                var end = minOf(frame.size, at + chunkSize)
                val budget = faults.disconnectAfterBytes
                if (budget != null) {
                    end = minOf(end, at + budget)
                    faults.disconnectAfterBytes = budget - (end - at)
                }
                val lost = dropChunk != null && (index == dropChunk || (faults.dropRestOfFrame && index > dropChunk))
                index++
                if (end > at && !lost) toClient.trySend(frame.copyOfRange(at, end))
                at = end
                if (faults.disconnectAfterBytes == 0) {
                    closed = true
                    toClient.close()
                }
            }
        }

        private fun encrypt(key: ByteArray, message: ByteArray): ByteArray {
            val framed = ByteArray((2 + message.size + 15) / 16 * 16)
            framed[0] = (message.size and 0xFF).toByte()
            framed[1] = (message.size ushr 8).toByte()
            message.copyInto(framed, 2)
            val iv = ByteArray(16).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            return iv + cipher.doFinal(framed)
        }

        private fun decrypt(key: ByteArray, payload: ByteArray): ByteArray? {
            if (payload.size < 32 || payload.size % 16 != 0) return null
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(payload, 0, 16))
            val plain = cipher.doFinal(payload, 16, payload.size - 16)
            val length = (plain[0].toInt() and 0xFF) or ((plain[1].toInt() and 0xFF) shl 8)
            return if (length <= plain.size - 2) plain.copyOfRange(2, 2 + length) else null
        }
    }

    private class Failure(val code: Int, val message: String, val data: JsonElement? = null)

    /** The machine's side of SRP-6a, written independently of the client. */
    private class ServerSrp(clientPublic: BigInteger, passkey: String, random: Random) {
        val salt: ByteArray = ByteArray(16).also(random::nextBytes)
        val publicKeyHex: String
        val key: ByteArray
        val expectedClientProof: ByteArray
        val serverProof: ByteArray

        init {
            val k = BigInteger(1, sha256(fixed(N), fixed(G)))
            val x = BigInteger(1, sha256(salt, sha256(passkey.toByteArray(Charsets.US_ASCII))))
            val verifier = G.modPow(x, N)
            val b = BigInteger(1, ByteArray(32).also(random::nextBytes))
            val serverPublic = k.multiply(verifier).add(G.modPow(b, N)).mod(N)
            val u = BigInteger(1, sha256(fixed(clientPublic), fixed(serverPublic)))
            val secret = clientPublic.multiply(verifier.modPow(u, N)).mod(N).modPow(b, N)
            key = sha256(fixed(secret))
            val hn = sha256(fixed(N))
            val hg = sha256(fixed(G))
            val mixed = ByteArray(32) { (hn[it].toInt() xor hg[it].toInt()).toByte() }
            expectedClientProof = sha256(mixed, salt, fixed(clientPublic), fixed(serverPublic), key)
            serverProof = sha256(fixed(clientPublic), expectedClientProof, key)
            // Unpadded, as a number: the real machine is not known to pad.
            publicKeyHex = serverPublic.toString(16).uppercase()
        }
    }

    private companion object {
        /** Plaintext answers. The client must not depend on this channel number. */
        const val CHANNEL_RX_PLAIN = 0x0392

        val N = BigInteger(
            "AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050" +
                "A37329CBB4A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50" +
                "E8083969EDB767B0CF6095179A163AB3661A05FBD5FAAAE82918A9962F0B93B8" +
                "55F97993EC975EEAA80D740ADBF4FF747359D041D5C33EA71D281E446B14773B" +
                "CA97B43A23FB801676BD207A436C6481F1D2B9078717461A5B9D32E688F87748" +
                "544523B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB3786160279004E57AE6" +
                "AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DBFBB6" +
                "94B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73",
            16,
        )
        val G: BigInteger = BigInteger.valueOf(2)
        val JSON = Json
        const val PARSE_ERROR = """{"jsonrpc":"2.0","id":null,"error":{"code":-32700,"message":"Parse error"}}"""

        fun parse(bytes: ByteArray): JsonObject? = try {
            JSON.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        }

        fun fixed(value: BigInteger): ByteArray {
            val raw = value.toByteArray().dropWhile { it.toInt() == 0 }.toByteArray()
            return ByteArray(256 - raw.size) + raw
        }

        fun sha256(vararg parts: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            parts.forEach(digest::update)
            return digest.digest()
        }

        fun hmac(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }

        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it) }

        fun unhex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
