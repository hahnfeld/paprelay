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
 * seven of its last bits are padding. The machine has been seen to pad with
 * ones (firmware 19.8.8.0: every one of 21 captured blocks either ended in a
 * run of ones or ended exactly on a code), and such padding is recognised.
 * Padding of zeros would decode as extra samples that continue the last
 * slope, and nothing in the block could tell those from real ones. The true length is therefore somewhere from [certain] to
 * `samples.size`, and only the caller can settle it, from what else it knows:
 * the other signals in the same record, an uncompressed signal's length, or the
 * time until the next record. A caller that cannot settle it must not store
 * the uncertain tail.
 */
internal class SampleBlock(
    /** Everything the bits can express, padding included. */
    val samples: IntArray,
    /**
     * How many leading samples are definitely real. When the block ends in an
     * unfinished run of ones, that run is the padding and every sample is
     * real. Otherwise only those whose code began before the last seven bits
     * are counted: a block that ends exactly on a code cannot show whether its
     * last codes were data or zero padding. Equal to `samples.size` when
     * nothing is in doubt.
     */
    val certain: Int,
    /**
     * Bits left unread because the caller's limit was reached; zero when
     * decoding ran to the end of the block. Eight or more means the block
     * definitely holds more data than was taken (not necessarily whole
     * samples: a limit also stops before damage further on is seen). One to
     * seven may be nothing but padding, so they say nothing either way.
     */
    val unreadBits: Long,
) {
    /** True when the block certainly holds more data than the caller's limit let through. */
    val definitelyMore: Boolean get() = unreadBits >= 8

    /** The series cut to [count] samples, which must lie within what is possible. */
    fun take(count: Int): IntArray {
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
internal object SampleSeries {
    /** Largest series accepted. A day of per-minute samples is 1440. */
    const val MAX_SAMPLES: Int = 100_000

    /**
     * Plain little-endian int16 samples, used for signals the machine does not
     * compress. A trailing odd byte is ignored, as the reference decoder does.
     */
    fun decodeRaw(blob: ByteArray): IntArray = IntArray(blob.size / 2) { int16(blob, it * 2) }

    /**
     * @param modulus the Rice modulus for this signal: a power of two, at least 1.
     * @param maxSamples decode at most this many samples.
     * @throws SpoolDecodeException if the block is malformed or cut short, a
     *   sample leaves the int16 range, or (with no [maxSamples]) the block could
     *   hold more than [MAX_SAMPLES].
     */
    fun decode(blob: ByteArray, modulus: Int, maxSamples: Int? = null): SampleBlock {
        if (modulus < 1 || modulus and (modulus - 1) != 0) throw SpoolDecodeException("unsupported Rice modulus $modulus")
        require(maxSamples == null || maxSamples in 0..MAX_SAMPLES) { "maxSamples out of range" }
        val limit = maxSamples ?: MAX_SAMPLES
        // Blocks too short to hold two seeds and a code are stored uncompressed.
        if (blob.size <= 4) {
            if (blob.size % 2 != 0) throw SpoolDecodeException("sample block has half a sample")
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

        fun bitAt(at: Long): Int {
            val byte = blob[4 + (at ushr 3).toInt()].toInt()
            return (byte ushr (7 - (at and 7).toInt())) and 1
        }

        fun next(): Int = bitAt(bit)

        /** True when one to seven bits remain from [from] and every one of them is a one: the block's padding. */
        fun onlyOnesFrom(from: Long): Boolean {
            if (totalBits - from !in 1..7) return false
            var at = from
            while (at < totalBits) if (bitAt(at++) == 0) return false
            return true
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
                // An unfinished run of ones is the padding itself, in plain sight: the machine
                // fills its last byte with ones, which can never complete a code. Everything
                // before such a run was written on purpose.
                if (!terminated && bit > codeStart) certain = count
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
                // Zero padding on a steep series runs out of range. That ends the series; it is
                // not corruption. But a code with the block's real padding (ones) after it was
                // written on purpose, and a real sample cannot be out of range.
                if (mayBePadding && !onlyOnesFrom(bit)) {
                    exhausted = true
                    break
                }
                throw SpoolDecodeException("sample block decodes outside the 16-bit range")
            }
            out[count++] = sample.toInt()
            if (!mayBePadding) certain = count
        }
        // Decoding can stop with the padding still unread: at the caller's limit, or because
        // the block can hold no more codes. A few ones left over are that padding all the same.
        if (!exhausted && onlyOnesFrom(bit)) certain = count
        var unread = if (exhausted) 0 else totalBits - bit
        if (!exhausted && count.toLong() == possible) {
            // Everything the block can hold was read, so what is left is too short to be a
            // sample. Up to seven such bits are padding; more means the block is damaged.
            if (unread >= 8) throw SpoolDecodeException("sample block ends in the middle of a sample")
            unread = 0
        }
        return SampleBlock(out.copyOf(count), certain, unreadBits = unread)
    }

    /** Largest quotient a real second difference of int16 samples can need, at modulus 1. */
    private const val MAX_QUOTIENT = 4 * 65_536

    private fun int16(blob: ByteArray, at: Int): Int =
        ((blob[at].toInt() and 0xFF) or (blob[at + 1].toInt() shl 8)).toShort().toInt()
}
