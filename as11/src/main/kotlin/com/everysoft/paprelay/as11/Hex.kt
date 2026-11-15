// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11

/** Strict hexadecimal codec. The machine's protocol carries all binary values as hex strings. */
public object Hex {
    private const val DIGITS = "0123456789ABCDEF"

    /** Upper-case encoding, matching what the reference client sends. */
    public fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = DIGITS[v ushr 4]
            out[i * 2 + 1] = DIGITS[v and 0x0F]
        }
        return String(out)
    }

    /**
     * Decodes [text], accepting either case.
     *
     * @throws As11ProtocolException if the text is empty, odd-length, longer than
     *   [maxBytes] bytes, or contains a non-hex character.
     */
    public fun decode(text: String, maxBytes: Int): ByteArray {
        if (text.isEmpty() || text.length % 2 != 0 || text.length / 2 > maxBytes) {
            throw As11ProtocolException("hex value has invalid length ${text.length}")
        }
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            val hi = nibble(text[i * 2])
            val lo = nibble(text[i * 2 + 1])
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun nibble(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw As11ProtocolException("hex value contains a non-hex character")
    }
}
