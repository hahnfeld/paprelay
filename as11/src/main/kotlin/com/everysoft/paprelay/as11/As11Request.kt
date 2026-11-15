// SPDX-License-Identifier: GPL-3.0-or-later
//
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
// Based on SomnoTrace, originally created by Ilya Kruchinin (https://github.com/ilyakruchinin).
//
// Request shapes follow SomnoTrace main/as11_ble.c and main/post_therapy.c,
// Copyright (C) 2026 Ilya Kruchinin, GPL-3.0-or-later with the Section 7(b)
// attribution term reproduced in NOTICE.
// Written for PAPRelay 2026-11-15 as a closed, read-only command set.
package com.everysoft.paprelay.as11

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Stored-data logs PAPRelay reads. No other log can be requested. */
public enum class SpoolName(public val wireName: String) {
    USAGE_EVENTS("UsageEvents-TherapyStatusEvents"),
    SUMMARY("Summary"),
    RESPIRATORY_EVENTS("TherapyEvents-RespiratoryEvents"),
    ONE_MINUTE("TherapyOneMinutePeriodic"),
}

/** Data-model items PAPRelay reads. No other item can be requested. */
public enum class GetItem(public val wireName: String) {
    UNIVERSAL_IDENTIFIER("UniversalIdentifier"),
    SERIAL_NUMBER("SerialNumber"),
    PRODUCT_CODE("ProductCode"),
    PRODUCT_NAME("ProductName"),
    PRODUCT_GEOGRAPHIC_IDENTIFIER("ProductGeographicIdentifier"),
    HARDWARE_IDENTIFIER("HardwareIdentifier"),
    APPLICATION_IDENTIFIER("ApplicationIdentifier"),
    DATA_MODEL_VERSION_IDENTIFIER("DataModelVersionIdentifier"),
    SETTING_PROFILES("SettingProfiles"),
}

/** Where a stored-data read starts. */
public sealed class SpoolAddress {
    internal abstract fun toJson(spool: SpoolName): JsonObject

    /** Start at a point on the machine's own clock. */
    public class FromMachineTime(public val machineEpochMs: Long) : SpoolAddress() {
        init {
            require(machineEpochMs in MIN_EPOCH_MS..MAX_EPOCH_MS) { "start time out of range" }
        }

        override fun toJson(spool: SpoolName): JsonObject = buildJsonObject {
            put(
                spool.wireName,
                buildJsonObject { put("fromDateTime", JsonPrimitive(ISO_MILLIS.format(Instant.ofEpochMilli(machineEpochMs)))) },
            )
        }
    }

    /**
     * Continue from the `nextSpoolAddress` the machine returned, passed back
     * verbatim. It must address the same log and be small.
     */
    public class Continuation(public val next: JsonObject) : SpoolAddress() {
        override fun toJson(spool: SpoolName): JsonObject {
            if (next.keys != setOf(spool.wireName) || next.toString().length > MAX_CONTINUATION_CHARS) {
                throw As11ProtocolException("machine returned an unusable continuation address")
            }
            return next
        }
    }

    private companion object {
        const val MAX_CONTINUATION_CHARS = 512
        const val MIN_EPOCH_MS = 946_684_800_000L // 2000-01-01
        const val MAX_EPOCH_MS = 4_102_444_800_000L // 2100-01-01
        val ISO_MILLIS: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    }
}

/**
 * The complete set of commands PAPRelay can send.
 *
 * The machine's encrypted channel also accepts commands that change settings,
 * start therapy and erase data. This type is sealed and its constructors take
 * only enumerated or validated values, so no such command can be expressed.
 * [As11Client] can transmit nothing but an [As11Request].
 *
 * That is a guarantee about what PAPRelay sends. It is not a guarantee against
 * someone actively altering radio traffic: the machine's encryption carries no
 * authentication, so such a party can damage a request in transit. See [encode].
 */
public sealed class As11Request(public val method: String, private val jsonRpcVersion: String) {
    internal abstract fun params(): JsonElement?

    /**
     * Serialises the request with a fixed member order: `id`, `jsonrpc`, `method`, `params`.
     *
     * The order matters. With this encryption, altering the IV rewrites the first
     * plaintext block bit for bit and leaves no trace. With `id` and `jsonrpc`
     * leading, the method name always starts beyond that block, which removes
     * the cheapest way to change which command the machine runs. Altering later
     * blocks is still possible at the cost of scrambling the block before them;
     * nothing on the client can prevent that.
     */
    public fun encode(id: Int): String {
        require(id >= 0) { "id must be non-negative" }
        val out = StringBuilder(96)
        out.append("{\"id\":").append(id)
        out.append(",\"jsonrpc\":\"").append(jsonRpcVersion).append('"')
        out.append(",\"method\":\"").append(method).append('"')
        params()?.let { out.append(",\"params\":").append(it.toString()) }
        out.append('}')
        return out.toString()
    }

    /** Sent in the clear: first pairing and session setup only. */
    public sealed class Plaintext(method: String) : As11Request(method, "2.0")

    /** Sent on the encrypted channel after session setup. */
    public sealed class Encrypted(method: String, version: String = "1.0") : As11Request(method, version)

    public class StartKeyExchange(private val clientPublicKey: ByteArray) : Plaintext("StartKeyExchange") {
        override fun params(): JsonElement = buildJsonObject { put("clientPk", JsonPrimitive(Hex.encode(clientPublicKey))) }
    }

    public class ConfirmKeyExchange(private val clientProof: ByteArray) : Plaintext("ConfirmKeyExchange") {
        override fun params(): JsonElement =
            buildJsonObject { put("clientConfirmation", JsonPrimitive(Hex.encode(clientProof))) }
    }

    public class RequestSession(private val clientId: String) : Plaintext("RequestSession") {
        init {
            require(isAcceptableClientId(clientId)) { "client id has an unexpected form" }
        }

        override fun params(): JsonElement = buildJsonObject { put("clientId", JsonPrimitive(clientId)) }
    }

    public class CheckSessionIntegrity(private val response: ByteArray) : Plaintext("CheckSessionIntegrity") {
        override fun params(): JsonElement = buildJsonObject { put("response", JsonPrimitive(Hex.encode(response))) }
    }

    public class Get(private val items: List<GetItem>) : Encrypted("Get") {
        init {
            require(items.isNotEmpty()) { "Get needs at least one item" }
        }

        override fun params(): JsonElement = JsonArray(items.map { JsonPrimitive(it.wireName) })
    }

    public object GetVersion : Encrypted("GetVersion", "2.0") {
        override fun params(): JsonElement? = null
    }

    public object GetDateTime : Encrypted("GetDateTime") {
        override fun params(): JsonElement? = null
    }

    public class StartSpool(
        private val spool: SpoolName,
        private val address: SpoolAddress,
        private val maxSpoolSize: Int = DEFAULT_MAX_SPOOL_SIZE,
    ) : Encrypted("StartSpool") {
        init {
            require(maxSpoolSize in 1..MAX_SPOOL_SIZE) { "maxSpoolSize out of range" }
        }

        override fun params(): JsonElement = buildJsonObject {
            put("spoolAddress", address.toJson(spool))
            put("maxSpoolSize", JsonPrimitive(maxSpoolSize))
        }
    }

    public class PullSpoolFragments(
        private val spoolId: Int,
        private val maxFragmentSize: Int = DEFAULT_MAX_FRAGMENT_SIZE,
    ) : Encrypted("PullSpoolFragments") {
        init {
            require(maxFragmentSize in 1..MAX_FRAGMENT_SIZE) { "maxFragmentSize out of range" }
        }

        override fun params(): JsonElement = buildJsonObject {
            put("spoolId", JsonPrimitive(spoolId))
            put("maxFragmentSize", JsonPrimitive(maxFragmentSize))
            put("maxNotifications", JsonPrimitive(0))
        }
    }

    /**
     * Asks the machine to forget this client's pairing. Affects only PAPRelay's
     * own pairing record; used solely by the explicit "Forget machine" action.
     */
    public object DiscardPairKey : Encrypted("DiscardPairKey") {
        override fun params(): JsonElement? = null
    }

    public companion object {
        public const val DEFAULT_MAX_SPOOL_SIZE: Int = 100_000
        public const val MAX_SPOOL_SIZE: Int = 1_000_000
        public const val DEFAULT_MAX_FRAGMENT_SIZE: Int = 2808
        public const val MAX_FRAGMENT_SIZE: Int = 3576
        private const val MAX_CLIENT_ID_CHARS = 128

        /**
         * The machine chooses the client id and its format is not documented, so
         * the only rules are a length bound and no control characters. It is sent
         * back as a JSON string, which is escaped by the serialiser.
         */
        public fun isAcceptableClientId(clientId: String): Boolean =
            clientId.length in 1..MAX_CLIENT_ID_CHARS && clientId.none { it < ' ' || it == '\u007f' }
    }
}
