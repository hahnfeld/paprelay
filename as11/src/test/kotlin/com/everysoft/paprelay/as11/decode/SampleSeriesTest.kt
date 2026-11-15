// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11.decode

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SampleSeriesTest {
    /** A straightforward encoder, written separately from the decoder, for round-trip tests. */
    private fun encode(samples: IntArray, modulus: Int): ByteArray {
        require(samples.size >= 2)
        val bits = ArrayList<Int>()
        val k = Integer.numberOfTrailingZeros(modulus)
        for (n in 2 until samples.size) {
            val d = samples[n] - 2 * samples[n - 1] + samples[n - 2]
            val encoded = if (d >= 0) 2L * d else -2L * d - 1
            repeat((encoded / modulus).toInt()) { bits += 1 }
            bits += 0
            for (i in k - 1 downTo 0) bits += ((encoded % modulus) shr i).toInt() and 1
        }
        while (bits.size % 8 != 0) bits += 1 // pad with ones: cannot terminate a code, so adds no samples
        val out = ByteArray(4 + bits.size / 8)
        for (i in 0 until 2) {
            out[i * 2] = samples[i].toByte()
            out[i * 2 + 1] = (samples[i] shr 8).toByte()
        }
        for (i in bits.indices) if (bits[i] == 1) out[4 + i / 8] = (out[4 + i / 8].toInt() or (0x80 ushr (i % 8))).toByte()
        return out
    }

    @Test
    fun `a hand-worked stream decodes to the expected samples`() {
        // Seeds 10 and 12. Modulus 4 (two remainder bits).
        //   d=0  -> zigzag 0 -> q=0 r=0 -> bits 0 00   -> 14
        //   d=-1 -> zigzag 1 -> q=0 r=1 -> bits 0 01   -> 15
        //   d=+3 -> zigzag 6 -> q=1 r=2 -> bits 10 10  -> 19
        // Stream: 000 001 1010 + padding 111111 = 0000 0110 1011 1111 = 06 BF
        val blob = byteArrayOf(10, 0, 12, 0, 0x06, 0xBF.toByte())
        assertContentEquals(intArrayOf(10, 12, 14, 15, 19), SampleSeries.decode(blob, modulus = 4))
    }

    @Test
    fun `series round-trip at every modulus the machine uses`() {
        val random = Random(5)
        for (modulus in listOf(1, 2, 4, 8, 16)) {
            repeat(200) {
                var value = random.nextInt(400) - 200
                val samples = IntArray(2 + random.nextInt(300)) {
                    value = (value + random.nextInt(21) - 10).coerceIn(-30_000, 30_000)
                    value
                }
                assertContentEquals(samples, SampleSeries.decode(encode(samples, modulus), modulus), "modulus=$modulus")
            }
        }
    }

    @Test
    fun `negative seeds and samples are sign-extended`() {
        val samples = intArrayOf(-5, -7, -9, -8, -200)
        assertContentEquals(samples, SampleSeries.decode(encode(samples, 4), 4))
    }

    @Test
    fun `short blocks are stored uncompressed`() {
        assertContentEquals(intArrayOf(7, -1), SampleSeries.decode(byteArrayOf(7, 0, 0xFF.toByte(), 0xFF.toByte()), 4))
        assertContentEquals(intArrayOf(7), SampleSeries.decode(byteArrayOf(7, 0), 4))
        assertEquals(0, SampleSeries.decode(ByteArray(0), 4).size)
    }

    @Test
    fun `zero padding reads as extra samples unless the expected count is given`() {
        // Seeds 10, 12, then one byte of zeros: with modulus 2 that is four codes of d=0.
        val blob = byteArrayOf(10, 0, 12, 0, 0)
        assertContentEquals(intArrayOf(10, 12, 14, 16, 18, 20), SampleSeries.decode(blob, modulus = 2))
        // A caller that knows the record holds three samples gets exactly three.
        assertContentEquals(intArrayOf(10, 12, 14), SampleSeries.decode(blob, modulus = 2, maxSamples = 3))
    }

    @Test
    fun `raw series are plain little-endian int16`() {
        assertContentEquals(intArrayOf(97, 96, -1), SampleSeries.decodeRaw(byteArrayOf(97, 0, 96, 0, 0xFF.toByte(), 0xFF.toByte())))
        assertFailsWith<SpoolDecodeException> { SampleSeries.decodeRaw(byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun `malformed streams are refused`() {
        assertFailsWith<SpoolDecodeException> { SampleSeries.decode(ByteArray(8), modulus = 3) }
        assertFailsWith<SpoolDecodeException> { SampleSeries.decode(ByteArray(8), modulus = 0) }
        // Seeds 32767, 32767, then d=+2 (zigzag 4: bits 1 0 00, padded with ones) would give 32769.
        val runaway = byteArrayOf(0xFF.toByte(), 0x7F, 0xFF.toByte(), 0x7F, 0x8F.toByte())
        assertFailsWith<SpoolDecodeException> { SampleSeries.decode(runaway, 4) }
        // A stream that is all ones never ends a code, so it yields nothing beyond the seeds.
        assertContentEquals(intArrayOf(1, 2), SampleSeries.decode(byteArrayOf(1, 0, 2, 0, -1, -1, -1), 4))
    }

    @Test
    fun `arbitrary bytes either decode or fail cleanly, and stay within bounds`() {
        val random = Random(9)
        repeat(20_000) {
            val junk = ByteArray(random.nextInt(64)).also(random::nextBytes)
            val modulus = 1 shl random.nextInt(5)
            try {
                val out = SampleSeries.decode(junk, modulus, maxSamples = 500)
                assertTrue(out.size <= 500)
                assertTrue(out.all { it in Short.MIN_VALUE..Short.MAX_VALUE })
            } catch (e: SpoolDecodeException) {
                // expected for some inputs
            }
        }
    }
}
