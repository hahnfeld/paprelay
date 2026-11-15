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
    fun `the requested count is honoured at every size`() {
        val samples = intArrayOf(10, 12, 14, 15, 19, 30)
        val blob = encode(samples, 4)
        for (count in 0..samples.size) {
            assertContentEquals(samples.copyOf(count), SampleSeries.decode(blob, 4, maxSamples = count), "count=$count")
        }
        assertContentEquals(samples, SampleSeries.decode(blob, 4, maxSamples = 50))
        assertFailsWith<IllegalArgumentException> { SampleSeries.decode(blob, 4, maxSamples = -1) }
        assertFailsWith<IllegalArgumentException> { SampleSeries.decode(blob, 4, maxSamples = SampleSeries.MAX_SAMPLES + 1) }
    }

    @Test
    fun `a block too large to be a real series is refused rather than cut short`() {
        // 40 kB of zero bits at modulus 1 would be 320,000 samples.
        val huge = ByteArray(4 + 40_000)
        assertFailsWith<SpoolDecodeException> { SampleSeries.decode(huge, modulus = 1) }
        // With an explicit count the caller has said how much to take.
        assertEquals(1_440, SampleSeries.decode(huge, modulus = 1, maxSamples = 1_440).size)
    }

    @Test
    fun `zero padding after a steep final slope ends the series instead of failing it`() {
        // Seeds 31000 and 32000, one real sample 32500 (d=-500), then zero padding. Padding
        // repeats the slope: 33000, which is out of range, so it must be recognised as padding.
        val real = intArrayOf(31_000, 32_000, 32_500)
        val blob = encode(real, 8).let { encoded ->
            // Replace the encoder's ones-padding with zeros.
            val bitsUsed = run {
                val zigzag = 999L // -500 -> 999
                (zigzag / 8).toInt() + 1 + 3
            }
            val out = encoded.copyOf()
            for (i in bitsUsed until (out.size - 4) * 8) {
                out[4 + i / 8] = (out[4 + i / 8].toInt() and (0x80 ushr (i % 8)).inv()).toByte()
            }
            out
        }
        assertContentEquals(real, SampleSeries.decode(blob, 8))
    }

    @Test
    fun `decoding many small blocks does not allocate for the worst case each time`() {
        val blob = encode(intArrayOf(1, 2, 3, 4), 4)
        val started = System.nanoTime()
        repeat(20_000) { SampleSeries.decode(blob, 4) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs < 2_000, "took $elapsedMs ms")
    }

    @Test
    fun `raw series are plain little-endian int16`() {
        assertContentEquals(intArrayOf(97, 96, -1), SampleSeries.decodeRaw(byteArrayOf(97, 0, 96, 0, 0xFF.toByte(), 0xFF.toByte())))
        // A trailing odd byte is ignored, as the reference decoder does.
        assertContentEquals(intArrayOf(0x0201), SampleSeries.decodeRaw(byteArrayOf(1, 2, 3)))
        assertContentEquals(intArrayOf(0x0201), SampleSeries.decode(byteArrayOf(1, 2, 3), 4))
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
