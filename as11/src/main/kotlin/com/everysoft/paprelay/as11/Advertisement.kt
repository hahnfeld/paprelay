// SPDX-License-Identifier: GPL-3.0-or-later
//
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
// Based on SomnoTrace, originally created by Ilya Kruchinin (https://github.com/ilyakruchinin).
//
// Derived from SomnoTrace main/as11_adv.c (as11_adv_salvage),
// Copyright (C) 2026 Ilya Kruchinin, GPL-3.0-or-later with the Section 7(b)
// attribution term reproduced in NOTICE.
// Ported to Kotlin and modified 2026-11-15: returns decoded values, and adds a
// raw-byte fallback for buffers where a scan response follows the broken field.
package com.everysoft.paprelay.as11

/** What could be recovered from a possibly malformed advertising payload. */
public class Advertisement internal constructor(
    public val localName: String?,
    public val serviceUuids16: List<Int>,
    /** True when a structure claimed more bytes than the payload holds. */
    public val truncated: Boolean,
    private val raw: ByteArray,
) {
    /**
     * Whether this looks like an AirSense 11: it advertises the vendor service,
     * or its name starts with "ResMed". The raw bytes are searched as a last
     * resort because the machine's own payload ends in a structure that is one
     * byte short, which can hide everything after it.
     */
    public val looksLikeAirSense: Boolean
        get() = SERVICE_UUID16 in serviceUuids16 ||
            localName?.startsWith(NAME_PREFIX) == true ||
            indexOf(raw, NAME_PREFIX.toByteArray(Charsets.US_ASCII)) >= 0

    public companion object {
        public const val SERVICE_UUID16: Int = 0xFD56
        public const val NAME_PREFIX: String = "ResMed"

        private const val TYPE_UUID16_INCOMPLETE = 0x02
        private const val TYPE_UUID16_COMPLETE = 0x03
        private const val TYPE_NAME_SHORTENED = 0x08
        private const val TYPE_NAME_COMPLETE = 0x09

        /**
         * Walks the advertising structures and keeps the intact ones. Never
         * throws: anything within radio range can advertise anything.
         */
        public fun parse(payload: ByteArray): Advertisement {
            var name: String? = null
            val uuids = ArrayList<Int>()
            var truncated = false
            var at = 0
            while (at + 1 < payload.size) {
                // The length counts the type byte as well as the data.
                val length = payload[at].toInt() and 0xFF
                // Zero is padding, and also what would make this loop spin for ever.
                if (length == 0 || at + 1 + length > payload.size) {
                    truncated = true
                    break
                }
                val type = payload[at + 1].toInt() and 0xFF
                val dataAt = at + 2
                val dataLength = length - 1
                when (type) {
                    TYPE_NAME_COMPLETE, TYPE_NAME_SHORTENED ->
                        name = String(payload, dataAt, dataLength, Charsets.UTF_8)
                    TYPE_UUID16_COMPLETE, TYPE_UUID16_INCOMPLETE ->
                        for (i in 0 until dataLength / 2) {
                            uuids += (payload[dataAt + 2 * i].toInt() and 0xFF) or
                                ((payload[dataAt + 2 * i + 1].toInt() and 0xFF) shl 8)
                        }
                }
                at += 1 + length
            }
            return Advertisement(name, uuids, truncated, payload.copyOf())
        }

        /**
         * The payload as hex with identifying content blanked: the bytes of local
         * names and of the device-address structure (type 0x1B), and everything
         * after a structure that runs off the end, since the machine's own
         * truncated trailer is its address. Structure lengths and types are kept,
         * which is what diagnosing a parser disagreement needs.
         */
        public fun redactedHex(payload: ByteArray): String {
            val out = StringBuilder(payload.size * 2)
            fun hex(b: Byte) = out.append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
            fun blank(count: Int) = repeat(count) { out.append("xx") }
            var at = 0
            while (at < payload.size) {
                val length = payload[at].toInt() and 0xFF
                hex(payload[at])
                if (length == 0) {
                    // Padding carries nothing; anything non-zero after it is unexplained, so hide it.
                    blank(payload.size - at - 1)
                    break
                }
                if (at + 1 >= payload.size) break
                hex(payload[at + 1])
                val type = payload[at + 1].toInt() and 0xFF
                val available = minOf(length - 1, payload.size - at - 2)
                val overruns = at + 1 + length > payload.size
                if (overruns || type == TYPE_LE_ADDRESS || type == TYPE_NAME_COMPLETE || type == TYPE_NAME_SHORTENED) {
                    blank(available)
                } else {
                    for (i in 0 until available) hex(payload[at + 2 + i])
                }
                if (overruns) break
                at += 1 + length
            }
            return out.toString()
        }

        private const val TYPE_LE_ADDRESS = 0x1B
        private const val HEX = "0123456789ABCDEF"

        private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
            outer@ for (i in 0..haystack.size - needle.size) {
                for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
                return i
            }
            return -1
        }
    }
}
