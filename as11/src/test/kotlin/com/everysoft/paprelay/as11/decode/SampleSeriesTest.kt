// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11.decode

import java.lang.management.ManagementFactory
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SampleSeriesTest {
    /**
     * A straightforward encoder, written separately from the decoder, for
     * round-trip tests. [padWith] fills the spare bits of the last byte: ones
     * can never end a code, so they add no samples; zeros are what a real
     * block may carry, and read as extra samples.
     */
    private fun encode(samples: IntArray, modulus: Int, padWith: Int = 1): ByteArray {
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
        while (bits.size % 8 != 0) bits += padWith
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
        val block = SampleSeries.decode(byteArrayOf(10, 0, 12, 0, 0x06, 0xBF.toByte()), modulus = 4)
        assertContentEquals(intArrayOf(10, 12, 14, 15, 19), block.samples)
        // Every code began well before the last seven bits, so nothing is in doubt.
        assertEquals(5, block.certain)
        assertFalse(block.limited)
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
                val block = SampleSeries.decode(encode(samples, modulus), modulus)
                assertContentEquals(samples, block.samples, "modulus=$modulus")
                assertTrue(block.certain in 2..samples.size)
            }
        }
    }

    @Test
    fun `negative seeds and samples are sign-extended`() {
        val samples = intArrayOf(-5, -7, -9, -8, -200)
        assertContentEquals(samples, SampleSeries.decode(encode(samples, 4), 4).samples)
    }

    @Test
    fun `short blocks are stored uncompressed`() {
        assertContentEquals(intArrayOf(7, -1), SampleSeries.decode(byteArrayOf(7, 0, 0xFF.toByte(), 0xFF.toByte()), 4).samples)
        assertContentEquals(intArrayOf(7), SampleSeries.decode(byteArrayOf(7, 0), 4).samples)
        assertEquals(0, SampleSeries.decode(ByteArray(0), 4).samples.size)
        // A trailing odd byte is ignored, as the reference decoder does.
        assertContentEquals(intArrayOf(0x0201), SampleSeries.decode(byteArrayOf(1, 2, 3), 4).samples)
    }

    @Test
    fun `zero padding reads as extra samples, and the block says which are in doubt`() {
        // Seeds 100 and 110, one real sample 121 (d=+1), then zero padding, at modulus 2.
        val real = intArrayOf(100, 110, 121)
        val block = SampleSeries.decode(encode(real, modulus = 2, padWith = 0), modulus = 2)
        // The padding continues the slope: 132, 143, ...
        assertTrue(block.samples.size > real.size)
        assertContentEquals(real, block.samples.copyOf(real.size))
        assertEquals(132, block.samples[3])
        // What is certain never exceeds what is real, so a caller can always recover the truth.
        assertTrue(block.certain <= real.size, "certain=${block.certain}")
        assertContentEquals(real, block.take(real.size))
        // A length outside what the block allows is refused.
        assertFailsWith<SpoolDecodeException> { block.take(block.samples.size + 1) }
        if (block.certain > 0) assertFailsWith<SpoolDecodeException> { block.take(block.certain - 1) }
    }

    @Test
    fun `certain is exact when every real code ends before the last seven bits`() {
        // Six bytes of codes, then a whole byte of padding ones is not possible, so build it by hand:
        // seeds 0, 0 and eight codes of d=0 at modulus 1 (one bit each) fill a byte; add one more byte
        // holding one real code and seven zero bits of padding.
        val blob = byteArrayOf(0, 0, 0, 0, 0x00, 0x00)
        val block = SampleSeries.decode(blob, modulus = 1)
        // 16 one-bit codes fit. Those that began in the last seven bits are in doubt.
        assertEquals(18, block.samples.size)
        assertEquals(2 + 9, block.certain)
    }

    @Test
    fun `the requested count is honoured at every size`() {
        val samples = intArrayOf(10, 12, 14, 15, 19, 30)
        val blob = encode(samples, 4)
        for (count in 0..samples.size) {
            val block = SampleSeries.decode(blob, 4, maxSamples = count)
            assertContentEquals(samples.copyOf(count), block.samples, "count=$count")
            assertEquals(count < samples.size, block.limited, "count=$count")
        }
        val whole = SampleSeries.decode(blob, 4, maxSamples = 50)
        assertContentEquals(samples, whole.samples)
        assertFalse(whole.limited)
        assertFailsWith<IllegalArgumentException> { SampleSeries.decode(blob, 4, maxSamples = -1) }
        assertFailsWith<IllegalArgumentException> { SampleSeries.decode(blob, 4, maxSamples = SampleSeries.MAX_SAMPLES + 1) }
    }

    @Test
    fun `a block too large to be a real series is refused rather than cut short`() {
        // 40 kB of zero bits at modulus 1 would be 320,000 samples.
        val huge = ByteArray(4 + 40_000)
        assertFailsWith<SpoolDecodeException> { SampleSeries.decode(huge, modulus = 1) }
        // With an explicit count the caller has said how much to take, and is told there was more.
        val block = SampleSeries.decode(huge, modulus = 1, maxSamples = 1_440)
        assertEquals(1_440, block.samples.size)
        assertTrue(block.limited)
    }

    @Test
    fun `zero padding after a steep final slope ends the series instead of failing it`() {
        // Seeds 31000 and 32000, one real sample 32500. At modulus 4 its code is 249 ones, a zero
        // and two remainder bits: 252 bits, leaving four bits of padding. Zeros there read as one
        // more code with d=0, which would be 33000: out of range, and so recognised as padding.
        val real = intArrayOf(31_000, 32_000, 32_500)
        val blob = encode(real, modulus = 4, padWith = 0)
        assertEquals(4 + 32, blob.size)
        assertEquals(0, blob.last().toInt() and 0x0F) // the four padding bits really are zero
        val block = SampleSeries.decode(blob, modulus = 4)
        assertContentEquals(real, block.samples)
        assertEquals(3, block.certain)
    }

    @Test
    fun `an out-of-range sample that is not padding is an error`() {
        // Seeds 32767, 32767, then d=+2 (zigzag 4: bits 1 0 00), followed by a further whole byte.
        val runaway = byteArrayOf(0xFF.toByte(), 0x7F, 0xFF.toByte(), 0x7F, 0x80.toByte(), 0x00)
        assertFailsWith<SpoolDecodeException> { SampleSeries.decode(runaway, 4) }
    }

    @Test
    fun `a block cut short in the middle of a sample is refused`() {
        // A code of many ones that never reaches its terminating zero, well before the end.
        val cut = byteArrayOf(1, 0, 2, 0, -1, -1, -1)
        assertFailsWith<SpoolDecodeException> { SampleSeries.decode(cut, 4) }
        // The same thing confined to the last seven bits is padding, not damage.
        val padded = encode(intArrayOf(10, 12, 14), 4, padWith = 1)
        assertContentEquals(intArrayOf(10, 12, 14), SampleSeries.decode(padded, 4).samples)
    }

    @Test
    fun `decoding a small block does not allocate for the largest possible one`() {
        val blob = encode(intArrayOf(1, 2, 3, 4), 4)
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        repeat(50) { SampleSeries.decode(blob, 4) } // warm up
        val before = threads.currentThreadAllocatedBytes
        repeat(1_000) { SampleSeries.decode(blob, 4) }
        val perCall = (threads.currentThreadAllocatedBytes - before) / 1_000
        // Sizing the buffer for 100,000 samples would be 400,000 bytes a call.
        assertTrue(perCall < 2_000, "allocated $perCall bytes per call")
    }

    @Test
    fun `raw series are plain little-endian int16`() {
        assertContentEquals(intArrayOf(97, 96, -1), SampleSeries.decodeRaw(byteArrayOf(97, 0, 96, 0, 0xFF.toByte(), 0xFF.toByte())))
        assertContentEquals(intArrayOf(0x0201), SampleSeries.decodeRaw(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `an unsupported modulus is refused`() {
        assertFailsWith<SpoolDecodeException> { SampleSeries.decode(ByteArray(8), modulus = 3) }
        assertFailsWith<SpoolDecodeException> { SampleSeries.decode(ByteArray(8), modulus = 0) }
    }

    @Test
    fun `arbitrary bytes either decode or fail cleanly, and stay within bounds`() {
        val random = Random(9)
        repeat(20_000) {
            val junk = ByteArray(random.nextInt(64)).also(random::nextBytes)
            val modulus = 1 shl random.nextInt(5)
            try {
                val block = SampleSeries.decode(junk, modulus, maxSamples = 500)
                assertTrue(block.samples.size <= 500)
                assertTrue(block.certain in 0..block.samples.size)
                assertTrue(block.samples.all { it in Short.MIN_VALUE..Short.MAX_VALUE })
            } catch (e: SpoolDecodeException) {
                // expected for some inputs
            }
        }
    }
}
