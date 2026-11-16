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
         * The payload as hex with everything that could identify the machine or
         * its owner blanked. Works from an allowlist: each structure's length
         * and type are shown, and its content only for the few types that carry
         * no identity (flags, 16-bit service lists, transmit power). Names,
         * addresses, manufacturer data, service data and anything unrecognised
         * are blanked.
         *
         * Blanking is total from the first device-address structure (type 0x1B)
         * or the first structure that runs off the end. The machine's own
         * address structure is one byte short, so on Android, where the scan
         * response follows the advertisement in one buffer, parsing goes out of
         * step from there and nothing after it can be classified.
         */
        public fun redactedHex(whole: ByteArray): String {
            // The original kind of advertisement, with its scan response, is 62 bytes at most.
            // A longer (extended) one is cut there: its shape matters, not two thousand masked bytes.
            val payload = if (whole.size > MAX_DESCRIBED_BYTES) whole.copyOf(MAX_DESCRIBED_BYTES) else whole
            val out = StringBuilder(payload.size * 2)
            fun hex(b: Byte) = out.append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
            fun blank(count: Int) = repeat(count) { out.append("xx") }
            // Each kind of content is shown once. A real advertisement has one of each; a second
            // would let a payload spell something out a structure at a time.
            val alreadyShown = HashSet<Int>()
            var at = 0
            while (at < payload.size) {
                val length = payload[at].toInt() and 0xFF
                // A type no advertisement uses means the walk has lost its place, usually because
                // an earlier structure lied about its length; what looks like a header is then
                // somebody's data, so not even that is shown.
                val lostPlace = length > MAX_STRUCTURE_LENGTH || // longer than an ordinary advertisement can hold
                    at + 1 >= payload.size || // a last byte with nothing after it: not a header at all
                    (payload[at + 1].toInt() and 0xFF) !in KNOWN_TYPES
                if (length != 0 && lostPlace) {
                    blank(payload.size - at)
                    break
                }
                hex(payload[at])
                if (length == 0) {
                    blank(payload.size - at - 1)
                    break
                }
                hex(payload[at + 1])
                val type = payload[at + 1].toInt() and 0xFF
                val overruns = at + 1 + length > payload.size
                if (overruns || type == TYPE_LE_ADDRESS) {
                    blank(payload.size - at - 2)
                    break
                }
                // Shown only at the size the structure should have: an over-long one is not
                // what its type says, and its extra bytes could be anything.
                val content = length - 1
                val asExpected = when (type) {
                    TYPE_FLAGS, TYPE_TX_POWER -> content == 1
                    else -> content % 2 == 0 && content <= MAX_SHOWN_UUID_BYTES
                }
                val kind = if (type == TYPE_UUID16_INCOMPLETE) TYPE_UUID16_COMPLETE else type
                if (type in SHOWN_TYPES && asExpected && alreadyShown.add(kind)) {
                    for (i in 0 until content) hex(payload[at + 2 + i])
                } else {
                    blank(length - 1)
                }
                at += 1 + length
            }
            if (whole.size > payload.size) out.append("(+").append(whole.size - payload.size).append(" bytes)")
            return out.toString()
        }

        /** Flags, incomplete and complete 16-bit service lists, transmit power. */
        private const val TYPE_FLAGS = 0x01
        private const val TYPE_TX_POWER = 0x0A
        private const val MAX_SHOWN_UUID_BYTES = 8
        private const val MAX_DESCRIBED_BYTES = 62
        private const val MAX_STRUCTURE_LENGTH = 0x1F
        private val SHOWN_TYPES = setOf(TYPE_FLAGS, TYPE_UUID16_INCOMPLETE, TYPE_UUID16_COMPLETE, TYPE_TX_POWER)
        private const val TYPE_LE_ADDRESS = 0x1B

        /**
         * The structure types advertisements actually carry: flags, service lists, names, power,
         * service data, appearance, addresses, manufacturer data. Letters and digits are not among them.
         */
        private val KNOWN_TYPES = setOf(
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x12, 0x14, 0x15, 0x16, 0x17, 0x19, 0x1A, 0x1B, 0x1C,
            0x20, 0x21, 0x24, 0xFF,
        )
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
