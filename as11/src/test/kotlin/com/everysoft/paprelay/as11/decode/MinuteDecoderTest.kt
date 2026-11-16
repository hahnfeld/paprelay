// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11.decode

import com.everysoft.paprelay.core.PapUnit
import java.math.BigDecimal
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MinuteDecoderTest {
    private val t = 1_794_000_000_000L

    private fun Proto.signal(signal: MinuteSignal, samples: IntArray, start: Long = t, padWith: Int = 1, status: Long? = 1) {
        message(signal.field) {
            if (status != null) varint(1, status)
            varint(2, start)
            bytes(3, signal.modulus?.let { riceBlock(samples, it, padWith) } ?: int16Block(samples))
        }
    }

    private val leak = intArrayOf(0, 3, 5, 5, 4, 20, 21, 19, 6, 2, 0, 0)
    private val rate = intArrayOf(56, 58, 60, 61, 60, 59, 64, 70, 66, 60, 58, 57)
    private val pressure = intArrayOf(40, 40, 42, 45, 50, 50, 50, 52, 52, 52, 50, 48)

    @Test
    fun `a record decodes every signal to what was stored`() {
        val payload = proto {
            message(5) {
                signal(MinuteSignal.LEAK, leak)
                signal(MinuteSignal.INSPIRATORY_PRESSURE, pressure)
                signal(MinuteSignal.RESPIRATORY_RATE, rate)
                varint(15, 1)
            }
        }
        val record = MinuteDecoder.decode(payload).records.single()
        assertEquals(t, record.startMs)
        assertEquals(60_000, record.intervalMs)
        assertEquals(12, record.sampleCount)
        assertEquals(0, record.uncertainTail)
        assertEquals(setOf(MinuteSignal.LEAK, MinuteSignal.INSPIRATORY_PRESSURE, MinuteSignal.RESPIRATORY_RATE), record.signals)
        assertContentEquals(leak, record.storedSamples(MinuteSignal.LEAK))
        assertContentEquals(rate, record.storedSamples(MinuteSignal.RESPIRATORY_RATE))
        assertContentEquals(pressure, record.storedSamples(MinuteSignal.INSPIRATORY_PRESSURE))
        assertNull(record.storedSamples(MinuteSignal.SPO2))
        assertEquals(t + 7 * 60_000, record.timeOf(7))
        assertEquals(0, record.unknownFields)
    }

    @Test
    fun `stored integers become physical values by the published factors`() {
        val payload = proto {
            message(5) {
                signal(MinuteSignal.LEAK, leak)
                signal(MinuteSignal.RESPIRATORY_RATE, rate)
                signal(MinuteSignal.INSPIRATORY_PRESSURE, pressure)
            }
        }
        val record = MinuteDecoder.decode(payload).records.single()
        // 20 stored -> 24 L/min; 70 stored -> 17.5 breaths/min; 52 stored -> 10.4 cmH2O.
        val leakAt5 = record.quantity(MinuteSignal.LEAK, 5)!!
        assertEquals(BigDecimal("24"), leakAt5.exact())
        assertEquals(24.0, leakAt5.value)
        assertEquals(PapUnit.LITRES_PER_MINUTE, leakAt5.unit)
        assertEquals(BigDecimal("17.5"), record.quantity(MinuteSignal.RESPIRATORY_RATE, 7)!!.exact())
        assertEquals(BigDecimal("10.4"), record.quantity(MinuteSignal.INSPIRATORY_PRESSURE, 7)!!.exact())
        assertNull(record.quantity(MinuteSignal.HEART_RATE, 0))
        assertFailsWith<IndexOutOfBoundsException> { record.quantity(MinuteSignal.LEAK, 12) }
    }

    @Test
    fun `every signal's field, modulus, factor and unit match the published table`() {
        // rpc_spools.md, "TherapyOneMinutePeriodic records", and the reference decoder's moduli,
        // except respiratory rate (8) and minute ventilation (4), which a real capture corrected.
        assertEquals(
            listOf(
                "LEAK 1 m4 6/5 LITRES_PER_MINUTE",
                "INSPIRATORY_PRESSURE 2 m4 1/5 CM_H2O",
                "EXPIRATORY_PRESSURE 3 m2 1/5 CM_H2O",
                "RESPIRATORY_RATE 4 m8 1/4 BREATHS_PER_MINUTE",
                "INSPIRATORY_DURATION 5 m4 1/25 SECONDS",
                "MINUTE_VENTILATION 6 m4 2/5 LITRES_PER_MINUTE",
                "IE_RATIO 7 m4 4/1 PERCENT",
                "SPO2 8 mnull 1/1 PERCENT",
                "HEART_RATE 9 mnull 1/1 BEATS_PER_MINUTE",
                "ALVEOLAR_MINUTE_VENTILATION 18 m8 2/5 LITRES_PER_MINUTE",
                "MEAN_INSPIRATORY_TIME 21 m4 1/10 SECONDS",
            ),
            MinuteSignal.entries.map { "${it.name} ${it.field} m${it.modulus} ${it.scale.numerator}/${it.scale.denominator} ${it.unit}" },
        )
    }

    @Test
    fun `the sample interval is one minute, however it is written`() {
        fun interval(token: Long?): Long = MinuteDecoder.decode(
            proto { message(5) { signal(MinuteSignal.LEAK, leak); if (token != null) varint(15, token) } },
        ).records.single().intervalMs
        assertEquals(60_000, interval(null))
        assertEquals(60_000, interval(1))
        assertEquals(60_000, interval(60_000))
        // Other intervals are possible in the format but have never been seen; a misread one
        // would misplace every sample, so they are not guessed at.
        for (token in listOf(0L, 2L, 10L, 999L, 1_000L, 59_999L, 600_000L)) {
            assertFailsWith<NoSuchElementException>("interval $token") { interval(token) }
        }
    }

    @Test
    fun `one signal that ends cleanly settles the length for all of them`() {
        // Flat pressure, zero padded: its tail cannot be told from padding on its own.
        val flat = IntArray(11) { 50 }
        val alone = MinuteDecoder.decode(proto { message(5) { signal(MinuteSignal.EXPIRATORY_PRESSURE, flat, padWith = 0) } }).records.single()
        assertTrue(alone.uncertainTail > 0, "the flat series alone must be ambiguous for this test to mean anything")

        // Beside an uncompressed oximeter signal, which has an exact length, nothing is in doubt.
        val settled = MinuteDecoder.decode(
            proto {
                message(5) {
                    signal(MinuteSignal.EXPIRATORY_PRESSURE, flat, padWith = 0)
                    signal(MinuteSignal.SPO2, IntArray(11) { 96 })
                }
            },
        ).records.single()
        assertEquals(11, settled.sampleCount)
        assertEquals(0, settled.uncertainTail)
        assertContentEquals(flat, settled.storedSamples(MinuteSignal.EXPIRATORY_PRESSURE))
    }

    @Test
    fun `a tail that may be padding is reported and kept out of the samples`() {
        val flat = IntArray(11) { 50 }
        val record = MinuteDecoder.decode(
            proto {
                message(5) {
                    signal(MinuteSignal.EXPIRATORY_PRESSURE, flat, padWith = 0)
                    signal(MinuteSignal.INSPIRATORY_PRESSURE, IntArray(11) { 60 }, padWith = 0)
                }
            },
        ).records.single()
        // What is certain never exceeds what is real, and the truth lies within what is offered.
        assertTrue(record.sampleCount <= 11)
        assertTrue(record.sampleCount + record.uncertainTail >= 11)
        assertTrue(record.uncertainTail > 0)
        assertEquals(record.sampleCount, record.storedSamples(MinuteSignal.EXPIRATORY_PRESSURE)!!.size)
        val withTail = record.storedSamples(MinuteSignal.EXPIRATORY_PRESSURE, record.sampleCount + record.uncertainTail)!!
        assertEquals(record.sampleCount + record.uncertainTail, withTail.size)
        assertContentEquals(flat, withTail.copyOf(11))
    }

    @Test
    fun `whatever the padding, the true length is never overstated and always within reach`() {
        val random = Random(20261115)
        repeat(300) {
            val n = 2 + random.nextInt(40)
            val chosen = MinuteSignal.entries.filter { random.nextBoolean() }.ifEmpty { listOf(MinuteSignal.LEAK) }
            val series = chosen.associateWith {
                var value = 40 + random.nextInt(20)
                IntArray(n) { value += random.nextInt(5) - 2; value }
            }
            val record = MinuteDecoder.decode(
                proto { message(5) { for ((signal, samples) in series) signal(signal, samples, padWith = random.nextInt(2)) } },
            ).records.single()
            assertTrue(record.sampleCount <= n, "certain ${record.sampleCount} of $n")
            assertTrue(record.sampleCount + record.uncertainTail >= n)
            for ((signal, samples) in series) {
                assertContentEquals(samples.copyOf(record.sampleCount), record.storedSamples(signal))
                assertContentEquals(samples, record.storedSamples(signal, record.sampleCount + record.uncertainTail)!!.copyOf(n))
            }
        }
    }

    @Test
    fun `padding of ones, as the machine writes it, leaves no doubt about the length`() {
        val random = Random(20261116)
        var sawPadding = 0
        repeat(200) {
            val n = 3 + random.nextInt(60)
            val series = MinuteSignal.entries.filter { it.modulus != null }.associateWith {
                var value = 40 + random.nextInt(20)
                IntArray(n) { value += random.nextInt(5) - 2; value }
            }
            val record = MinuteDecoder.decode(
                proto { message(5) { for ((signal, samples) in series) signal(signal, samples, padWith = 1) } },
            ).records.single()
            // Nine signals: at least one does not end on a byte boundary, shows its padding, and settles the rest.
            assertEquals(n, record.sampleCount)
            assertEquals(0, record.uncertainTail)
            for ((signal, samples) in series) assertContentEquals(samples, record.storedSamples(signal))
            sawPadding++
        }
        assertEquals(200, sawPadding)
    }

    @Test
    fun `a wrong modulus is caught by the other signals`() {
        // This is how the reference's respiratory-rate modulus was found to be wrong: decoded
        // with the wrong one, a series comes out a different length from its neighbours.
        val wrong = proto {
            message(5) {
                signal(MinuteSignal.LEAK, leak)
                message(MinuteSignal.RESPIRATORY_RATE.field) { varint(1, 1); varint(2, t); bytes(3, riceBlock(rate, 4)) }
            }
        }
        val log = MinuteDecoder.decode(wrong)
        assertTrue(log.records.isEmpty())
        assertEquals(1, log.failures.size)
    }

    @Test
    fun `several records decode independently`() {
        val records = MinuteDecoder.decode(
            proto {
                message(5) { signal(MinuteSignal.LEAK, leak) }
                message(5) { signal(MinuteSignal.LEAK, intArrayOf(1, 2, 3), start = t + 3_600_000) }
            },
        )
        assertEquals(listOf(12, 3), records.records.map { it.sampleCount })
        assertEquals(t + 3_600_000, records.records[1].startMs)
        assertTrue(MinuteDecoder.decode(ByteArray(0)).records.isEmpty())
        // A record that is not understood is reported and leaves its neighbours standing.
        val mixed = MinuteDecoder.decode(
            proto {
                message(5) { signal(MinuteSignal.LEAK, leak) }
                message(5) { signal(MinuteSignal.LEAK, leak, status = 2) }
                message(5) { signal(MinuteSignal.LEAK, intArrayOf(1, 2, 3), start = t + 3_600_000) }
            },
        )
        assertEquals(listOf(12, 3), mixed.records.map { it.sampleCount })
        assertEquals(listOf(1), mixed.failures.map { it.index })
    }

    @Test
    fun `fields this version does not know are counted and skipped`() {
        val record = MinuteDecoder.decode(
            proto {
                message(5) {
                    signal(MinuteSignal.LEAK, leak)
                    message(12) { varint(1, 1) }
                    varint(16, 3)
                    message(MinuteSignal.RESPIRATORY_RATE.field) { varint(1, 1); varint(2, t); bytes(3, riceBlock(rate, 8)); varint(7, 9) }
                }
            },
        ).records.single()
        assertEquals(3, record.unknownFields)
        assertContentEquals(rate, record.storedSamples(MinuteSignal.RESPIRATORY_RATE))
    }

    @Test
    fun `records that contradict themselves or the layout are refused`() {
        // Refused outright, or reported as a record that could not be decoded; never passed off as data.
        fun refused(payload: ByteArray) {
            val log = try {
                MinuteDecoder.decode(payload)
            } catch (e: SpoolDecodeException) {
                return
            }
            assertTrue(log.failures.isNotEmpty() && log.records.isEmpty(), "decoded to ${log.records.size} records")
        }
        // Another log's envelope.
        refused(proto { message(2) { signal(MinuteSignal.LEAK, leak) } })
        // Signals of different lengths, or starting at different times.
        refused(proto { message(5) { signal(MinuteSignal.LEAK, leak); signal(MinuteSignal.RESPIRATORY_RATE, rate.copyOf(8)) } })
        refused(proto { message(5) { signal(MinuteSignal.LEAK, leak); signal(MinuteSignal.SPO2, IntArray(9) { 95 }) } })
        refused(proto { message(5) { signal(MinuteSignal.LEAK, leak); signal(MinuteSignal.RESPIRATORY_RATE, rate, start = t + 60_000) } })
        // A kind of series never seen before.
        refused(proto { message(5) { signal(MinuteSignal.LEAK, leak, status = 2) } })
        // Missing parts.
        refused(proto { message(5) { message(1) { varint(1, 1); bytes(3, riceBlock(leak, 4)) } } }) // no start
        refused(proto { message(5) { message(1) { varint(1, 1); varint(2, t) } } })
        refused(proto { message(5) { varint(15, 1) } })
        // A signal given twice, the wrong kind of field, an odd-length plain series.
        refused(proto { message(5) { signal(MinuteSignal.LEAK, leak); signal(MinuteSignal.LEAK, leak) } })
        refused(proto { message(5) { varint(4, 60) } })
        refused(proto { message(5) { signal(MinuteSignal.LEAK, leak); bytes(15, byteArrayOf(1)) } })
        refused(proto { message(5) { message(8) { varint(1, 1); varint(2, t); bytes(3, byteArrayOf(96, 0, 95)) } } })
        // A compressed series cut in the middle.
        val block = riceBlock(IntArray(40) { it * it % 50 }, 4)
        refused(proto { message(5) { message(1) { varint(1, 1); varint(2, t); bytes(3, block.copyOf(block.size - 1).also { it[it.size - 1] = 0xFF.toByte(); it[it.size - 2] = 0xFF.toByte() }) } } })
    }

    @Test
    fun `a series with no kind marker is not assumed to be the usual kind`() {
        val log = MinuteDecoder.decode(proto { message(5) { signal(MinuteSignal.LEAK, leak, status = null) } })
        assertTrue(log.records.isEmpty())
        assertEquals(1, log.failures.size)
    }

    @Test
    fun `nothing outside the settled length can be read`() {
        val flat = IntArray(11) { 50 }
        val record = MinuteDecoder.decode(proto { message(5) { signal(MinuteSignal.EXPIRATORY_PRESSURE, flat, padWith = 0) } }).records.single()
        val most = record.sampleCount + record.uncertainTail
        assertTrue(record.uncertainTail > 0)
        // The tail is reachable only by naming a length, and only within what the bits allow.
        assertFailsWith<IndexOutOfBoundsException> { record.quantity(MinuteSignal.EXPIRATORY_PRESSURE, record.sampleCount) }
        assertEquals(BigDecimal("10"), record.quantity(MinuteSignal.EXPIRATORY_PRESSURE, record.sampleCount, length = most)!!.exact())
        assertFailsWith<IllegalArgumentException> { record.storedSamples(MinuteSignal.EXPIRATORY_PRESSURE, most + 1) }
        assertFailsWith<IllegalArgumentException> { record.storedSamples(MinuteSignal.EXPIRATORY_PRESSURE, record.sampleCount - 1) }
        assertFailsWith<IllegalArgumentException> { record.quantity(MinuteSignal.EXPIRATORY_PRESSURE, 0, length = most + 1) }
        assertEquals(record.startMs + (most - 1) * 60_000L, record.timeOf(most - 1))
        assertFailsWith<IndexOutOfBoundsException> { record.timeOf(most) }
        assertFailsWith<IndexOutOfBoundsException> { record.timeOf(-1) }
    }
}
