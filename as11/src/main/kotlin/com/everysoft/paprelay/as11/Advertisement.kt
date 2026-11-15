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
                if (length == 0) break
                if (at + 1 + length > payload.size) {
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

        private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
            outer@ for (i in 0..haystack.size - needle.size) {
                for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
                return i
            }
            return -1
        }
    }
}
