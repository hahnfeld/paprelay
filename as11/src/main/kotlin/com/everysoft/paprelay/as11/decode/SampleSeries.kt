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
 * The stream carries no sample count and ends on a byte boundary, so up to seven
 * of its last bits are padding. Padding of zeros decodes as extra samples that
 * continue the last slope, and nothing here can tell them from real ones. A
 * caller that knows how many samples to expect should pass that count.
 */
public object SampleSeries {
    /** Largest series accepted. A day of per-minute samples is 1440. */
    public const val MAX_SAMPLES: Int = 100_000

    /**
     * Plain little-endian int16 samples, used for signals the machine does not
     * compress. A trailing odd byte is ignored, as the reference decoder does.
     */
    public fun decodeRaw(blob: ByteArray): IntArray = IntArray(blob.size / 2) { int16(blob, it * 2) }

    /**
     * @param modulus the Rice modulus for this signal: a power of two, at least 1.
     * @param maxSamples return at most this many samples. Leave it out to get
     *   everything the bits can express, which may include up to
     *   `7 / (1 + log2(modulus))` trailing samples that are really padding.
     * @throws SpoolDecodeException if the block is malformed, a sample leaves the
     *   int16 range, or (with no [maxSamples]) it holds more than [MAX_SAMPLES].
     */
    public fun decode(blob: ByteArray, modulus: Int, maxSamples: Int? = null): IntArray {
        if (modulus < 1 || modulus and (modulus - 1) != 0) throw SpoolDecodeException("unsupported Rice modulus $modulus")
        require(maxSamples == null || maxSamples in 0..MAX_SAMPLES) { "maxSamples out of range" }
        val limit = maxSamples ?: MAX_SAMPLES
        // Blocks too short to hold two seeds and a code are stored uncompressed.
        if (blob.size <= 4) return decodeRaw(blob).let { if (it.size > limit) it.copyOf(limit) else it }

        val remainderBits = Integer.numberOfTrailingZeros(modulus)
        val totalBits = (blob.size - 4) * 8
        // Every code is at least one terminator bit plus the remainder, which bounds the count.
        val possible = 2L + totalBits / (1 + remainderBits)
        if (maxSamples == null && possible > MAX_SAMPLES) {
            throw SpoolDecodeException("sample block could hold more than $MAX_SAMPLES samples")
        }
        val out = IntArray(minOf(limit.toLong(), possible).toInt().coerceAtLeast(2))
        out[0] = int16(blob, 0)
        out[1] = int16(blob, 2)
        if (limit < 2) return out.copyOf(limit)
        var count = 2
        var bit = 0

        fun next(): Int {
            val byte = blob[4 + (bit ushr 3)].toInt()
            return (byte ushr (7 - (bit and 7))) and 1
        }

        while (count < out.size) {
            val codeStart = bit
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
                // A code that begins in the last seven bits may be padding, and padding on a
                // steep series runs out of range. That ends the series; it is not corruption.
                if (totalBits - codeStart < 8) break
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
