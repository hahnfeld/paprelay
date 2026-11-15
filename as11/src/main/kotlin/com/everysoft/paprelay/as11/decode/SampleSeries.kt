// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
//
// The encoding is described in the airbreak-plus protocol notes (MIT), used
// here as a reference for facts only. This implementation is original.
package com.everysoft.paprelay.as11.decode

/**
 * Decodes the machine's compressed series of 16-bit samples.
 *
 * Layout: two little-endian int16 seed samples, then a bit stream read most
 * significant bit first. Each further sample is stored as the second difference
 * `d = s[n] - 2*s[n-1] + s[n-2]`, ZigZag-mapped to a non-negative number and
 * Rice-coded: a unary quotient (ones ended by a zero) followed by `log2(modulus)`
 * remainder bits.
 *
 * The stream carries no sample count and ends on a byte boundary, so its last
 * few bits are padding. Padding of zeros decodes as extra samples that continue
 * the last slope, and there is no way to tell them from real ones here. Callers
 * that know how many samples to expect must pass [maxSamples]; see
 * [decode] for what is returned otherwise.
 */
public object SampleSeries {
    /** Largest series accepted. A day of per-minute samples is 1440. */
    public const val MAX_SAMPLES: Int = 100_000

    /** Plain little-endian int16 samples, used for signals the machine does not compress. */
    public fun decodeRaw(blob: ByteArray): IntArray {
        if (blob.size % 2 != 0) throw SpoolDecodeException("sample block has an odd length")
        return IntArray(blob.size / 2) { int16(blob, it * 2) }
    }

    /**
     * @param modulus the Rice modulus for this signal: a power of two, at least 1.
     * @param maxSamples stop after this many samples. Without it, everything the
     *   bits can express is returned, which may include up to
     *   `7 / (1 + log2(modulus))` trailing samples that are really padding.
     * @throws SpoolDecodeException if the block is malformed or a sample leaves the int16 range.
     */
    public fun decode(blob: ByteArray, modulus: Int, maxSamples: Int = MAX_SAMPLES): IntArray {
        if (modulus < 1 || modulus and (modulus - 1) != 0) throw SpoolDecodeException("unsupported Rice modulus $modulus")
        // Blocks too short to hold two seeds and a code are stored uncompressed.
        if (blob.size <= 4) return decodeRaw(blob).let { if (it.size > maxSamples) it.copyOf(maxSamples) else it }
        val remainderBits = Integer.numberOfTrailingZeros(modulus)
        val out = IntArray(minOf(maxSamples, MAX_SAMPLES).coerceAtLeast(2))
        out[0] = int16(blob, 0)
        out[1] = int16(blob, 2)
        var count = 2
        val totalBits = (blob.size - 4) * 8
        var bit = 0

        fun next(): Int {
            val byte = blob[4 + (bit ushr 3)].toInt()
            return (byte ushr (7 - (bit and 7))) and 1
        }

        while (count < out.size) {
            var quotient = 0
            var terminated = false
            while (bit < totalBits) {
                if (next() == 0) {
                    bit++
                    terminated = true
                    break
                }
                bit++
                // A run this long cannot come from a real int16 difference.
                if (++quotient > MAX_QUOTIENT) throw SpoolDecodeException("sample block is not a valid Rice stream")
            }
            if (!terminated || totalBits - bit < remainderBits) break
            var remainder = 0
            repeat(remainderBits) {
                remainder = (remainder shl 1) or next()
                bit++
            }
            val encoded = quotient.toLong() * modulus + remainder
            val difference = (encoded ushr 1) xor -(encoded and 1)
            val sample = 2L * out[count - 1] - out[count - 2] + difference
            if (sample < Short.MIN_VALUE || sample > Short.MAX_VALUE) {
                throw SpoolDecodeException("sample block decodes outside the 16-bit range")
            }
            out[count++] = sample.toInt()
        }
        return out.copyOf(count)
    }

    /** Largest quotient a real second difference of int16 samples can need, at modulus 1. */
    private const val MAX_QUOTIENT = 4 * 65_536

    private fun int16(blob: ByteArray, at: Int): Int =
        ((blob[at].toInt() and 0xFF) or (blob[at + 1].toInt() shl 8)).toShort().toInt()
}
