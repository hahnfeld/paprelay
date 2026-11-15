// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
//
// The encoding is described in the airbreak-plus protocol notes (MIT), used
// here as a reference for facts only. This implementation is original.
package com.everysoft.paprelay.as11.decode

/**
 * A decoded series, with what is known about where it really ends.
 *
 * The stream carries no sample count and ends on a byte boundary, so up to
 * seven of its last bits are padding. Padding of zeros decodes as extra samples
 * that continue the last slope, and nothing in the block can tell those from
 * real ones. The true length is therefore somewhere from [certain] to
 * `samples.size`, and only the caller can settle it, from what else it knows:
 * the other signals in the same record, an uncompressed signal's length, or the
 * time until the next record. A caller that cannot settle it must not store
 * the uncertain tail.
 */
public class SampleBlock internal constructor(
    /** Everything the bits can express, padding included. */
    public val samples: IntArray,
    /**
     * How many leading samples are definitely real: those whose code began
     * before the last seven bits of the block. Equal to `samples.size` when
     * nothing is in doubt.
     */
    public val certain: Int,
    /**
     * Bits left unread because the caller's limit was reached; zero when
     * decoding ran to the end of the block. Eight or more means the block
     * definitely holds more samples than were taken. One to seven may be
     * nothing but padding, so they say nothing either way.
     */
    public val unreadBits: Long,
) {
    /** True when the block certainly holds more samples than the caller's limit allowed. */
    public val definitelyMore: Boolean get() = unreadBits >= 8

    /** The series cut to [count] samples, which must lie within what is possible. */
    public fun take(count: Int): IntArray {
        if (count < certain || count > samples.size) {
            throw SpoolDecodeException("a length of $count is not possible for this block ($certain to ${samples.size})")
        }
        return samples.copyOf(count)
    }
}

/**
 * Decodes the machine's compressed series of 16-bit samples.
 *
 * Layout: two little-endian int16 seed samples, then a bit stream read most
 * significant bit first. Each further sample is stored as the second difference
 * `d = s[n] - 2*s[n-1] + s[n-2]`, ZigZag-mapped to a non-negative number and
 * Rice-coded: a unary quotient (ones ended by a zero) followed by `log2(modulus)`
 * remainder bits.
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
     * @param maxSamples decode at most this many samples.
     * @throws SpoolDecodeException if the block is malformed or cut short, a
     *   sample leaves the int16 range, or (with no [maxSamples]) the block could
     *   hold more than [MAX_SAMPLES].
     */
    public fun decode(blob: ByteArray, modulus: Int, maxSamples: Int? = null): SampleBlock {
        if (modulus < 1 || modulus and (modulus - 1) != 0) throw SpoolDecodeException("unsupported Rice modulus $modulus")
        require(maxSamples == null || maxSamples in 0..MAX_SAMPLES) { "maxSamples out of range" }
        val limit = maxSamples ?: MAX_SAMPLES
        // Blocks too short to hold two seeds and a code are stored uncompressed.
        if (blob.size <= 4) {
            val raw = decodeRaw(blob)
            val kept = if (raw.size > limit) raw.copyOf(limit) else raw
            return SampleBlock(kept, kept.size, unreadBits = (raw.size - kept.size) * 16L)
        }

        val remainderBits = Integer.numberOfTrailingZeros(modulus)
        val totalBits = (blob.size.toLong() - 4) * 8
        // Every code is at least one terminator bit plus the remainder, which bounds the count.
        val possible = 2L + totalBits / (1 + remainderBits)
        if (maxSamples == null && possible > MAX_SAMPLES) {
            throw SpoolDecodeException("sample block could hold more than $MAX_SAMPLES samples")
        }
        val out = IntArray(minOf(limit.toLong(), possible).toInt().coerceAtLeast(2))
        out[0] = int16(blob, 0)
        out[1] = int16(blob, 2)
        if (limit < 2) return SampleBlock(out.copyOf(limit), limit, unreadBits = totalBits + (2 - limit) * 16L)
        var count = 2
        var certain = 2
        var bit = 0L
        var exhausted = false

        fun next(): Int {
            val byte = blob[4 + (bit ushr 3).toInt()].toInt()
            return (byte ushr (7 - (bit and 7).toInt())) and 1
        }

        while (count < out.size) {
            val codeStart = bit
            // Anything that begins in the last seven bits may be padding.
            val mayBePadding = totalBits - codeStart < 8
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
            if (!terminated || totalBits - bit < remainderBits) {
                // The bits ran out part-way through a code. At the very end that is padding;
                // any earlier and the block has been cut short.
                if (!mayBePadding) throw SpoolDecodeException("sample block ends in the middle of a sample")
                exhausted = true
                break
            }
            var remainder = 0
            repeat(remainderBits) {
                remainder = (remainder shl 1) or next()
                bit++
            }
            val encoded = quotient.toLong() * modulus + remainder
            val difference = (encoded ushr 1) xor -(encoded and 1)
            val sample = 2L * out[count - 1] - out[count - 2] + difference
            if (sample < Short.MIN_VALUE || sample > Short.MAX_VALUE) {
                // Padding on a steep series runs out of range. That ends the series; it is not corruption.
                if (mayBePadding) {
                    exhausted = true
                    break
                }
                throw SpoolDecodeException("sample block decodes outside the 16-bit range")
            }
            out[count++] = sample.toInt()
            if (!mayBePadding) certain = count
        }
        return SampleBlock(out.copyOf(count), certain, unreadBits = if (exhausted) 0 else totalBits - bit)
    }

    /** Largest quotient a real second difference of int16 samples can need, at modulus 1. */
    private const val MAX_QUOTIENT = 4 * 65_536

    private fun int16(blob: ByteArray, at: Int): Int =
        ((blob[at].toInt() and 0xFF) or (blob[at + 1].toInt() shl 8)).toShort().toInt()
}
