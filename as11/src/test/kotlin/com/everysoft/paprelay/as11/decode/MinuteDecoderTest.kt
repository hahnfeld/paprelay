// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11.decode

import com.everysoft.paprelay.core.MachineTime
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

    private companion object {
        /** The firmware of the machine the layouts were confirmed on. */
        const val FIRMWARE = "SW04600.19.8.8.0.dced143ae"
    }

    private fun Proto.signal(signal: MinuteSignal, samples: IntArray, start: Long = t, padWith: Int = 1, status: Long? = 1, modulus: Int? = signal.modulus) {
        message(signal.field) {
            if (status != null) varint(1, status)
            varint(2, start)
            bytes(3, modulus?.let { riceBlock(samples, it, padWith) } ?: int16Block(samples))
        }
    }

    /** A record as the machine writes it: signals 1 to 7, then the interval. */
    private fun Proto.record(series: Map<MinuteSignal, IntArray>, start: Long = t, padWith: Int = 1) {
        message(5) {
            for ((signal, samples) in series) signal(signal, samples, start, padWith)
            varint(15, 1)
        }
    }

    private val seen = listOf(
        MinuteSignal.LEAK, MinuteSignal.INSPIRATORY_PRESSURE, MinuteSignal.EXPIRATORY_PRESSURE, MinuteSignal.RESPIRATORY_RATE,
        MinuteSignal.INSPIRATORY_DURATION, MinuteSignal.MINUTE_VENTILATION, MinuteSignal.IE_RATIO,
    )

    private fun randomSeries(random: Random, n: Int, signals: List<MinuteSignal> = seen): Map<MinuteSignal, IntArray> =
        signals.associateWith { signal ->
            // Kept within what the signal can measure, so that nothing is set aside as misread.
            val top = minOf(signal.maxStored, 200)
            var value = top / 2 + random.nextInt(10)
            IntArray(n) { value = (value + random.nextInt(5) - 2).coerceIn(1, top); value }
        }

    private val leak = intArrayOf(0, 3, 5, 5, 4, 20, 21, 19, 6, 2, 0, 0)
    private val rate = intArrayOf(56, 58, 60, 61, 60, 59, 64, 70, 66, 60, 58, 57)
    private val pressure = intArrayOf(40, 40, 42, 45, 50, 50, 50, 52, 52, 52, 50, 48)

    private fun one(payload: ByteArray): MinuteRecord {
        val log = MinuteDecoder.decode(payload, FIRMWARE)
        assertTrue(log.complete, "failures: ${log.failures.map { it.reason }}")
        return log.decoded.single()
    }

    private fun refused(why: String, payload: ByteArray) {
        val log = try {
            MinuteDecoder.decode(payload, FIRMWARE)
        } catch (e: SpoolDecodeException) {
            return
        }
        assertTrue(log.decoded.isEmpty(), "$why: decoded")
        assertEquals(1, log.failures.size, why)
    }

    @Test
    fun `a session as the machine writes it, seven signals and one sample a minute`() {
        // Mask on at t; a session of 123.8 minutes has 123 completed minutes.
        val series = randomSeries(Random(1), 123)
        val record = one(proto { record(series, start = t + 60_000) })
        assertEquals(123, record.sampleCount)
        assertEquals(0, record.uncertainTail)
        assertEquals(seen.toSet(), record.signals)
        assertTrue(record.withheldSignals.isEmpty())
        assertEquals(0, record.unknownFields)
        for ((signal, samples) in series) assertContentEquals(samples, record.storedSamples(signal))
        // The first sample is stamped at the end of the first minute, the last at the end of the 123rd.
        assertEquals(MachineTime(t + 60_000), record.firstMinuteEnd)
        assertEquals(MachineTime(t + 60_000), record.minuteEnd(0))
        assertEquals(MachineTime(t), record.minuteStart(0)) // mask-on
        assertEquals(MachineTime(t + 123 * 60_000L), record.minuteEnd(122))
        assertEquals(MachineTime(t + 122 * 60_000L), record.minuteStart(122))
        assertFailsWith<IndexOutOfBoundsException> { record.minuteEnd(123) }
        assertFailsWith<IndexOutOfBoundsException> { record.minuteStart(-1) }
    }

    @Test
    fun `stored integers become physical values by the confirmed factors`() {
        val record = one(proto { record(mapOf(MinuteSignal.LEAK to leak, MinuteSignal.RESPIRATORY_RATE to rate, MinuteSignal.INSPIRATORY_PRESSURE to pressure)) })
        // 20 stored -> 24 L/min; 70 stored -> 17.5 breaths/min; 52 stored -> 10.4 cmH2O.
        val leakAt5 = record.quantity(MinuteSignal.LEAK, 5)!!
        assertEquals(BigDecimal("24"), leakAt5.exact())
        assertEquals(PapUnit.LITRES_PER_MINUTE, leakAt5.unit)
        assertEquals(BigDecimal("17.5"), record.quantity(MinuteSignal.RESPIRATORY_RATE, 7)!!.exact())
        assertEquals(BigDecimal("10.4"), record.quantity(MinuteSignal.INSPIRATORY_PRESSURE, 7)!!.exact())
        assertEquals(rate.map { BigDecimal(it).divide(BigDecimal(4)).stripTrailingZeros() }, record.quantities(MinuteSignal.RESPIRATORY_RATE)!!.map { it.exact().stripTrailingZeros() })
        assertNull(record.quantity(MinuteSignal.MINUTE_VENTILATION, 0))
        assertNull(record.quantities(MinuteSignal.MINUTE_VENTILATION))
        assertFailsWith<IndexOutOfBoundsException> { record.quantity(MinuteSignal.LEAK, 12) }
    }

    @Test
    fun `a value whose factor is unconfirmed is given out only on request`() {
        val record = one(proto { record(mapOf(MinuteSignal.LEAK to leak, MinuteSignal.INSPIRATORY_DURATION to pressure)) })
        assertFailsWith<IllegalStateException> { record.quantities(MinuteSignal.INSPIRATORY_DURATION) }
        assertFailsWith<IllegalStateException> { record.quantity(MinuteSignal.INSPIRATORY_DURATION, 0) }
        assertEquals(BigDecimal("1.6"), record.quantity(MinuteSignal.INSPIRATORY_DURATION, 0, allowUnconfirmed = true)!!.exact())
        // The stored integers are always there for export.
        assertContentEquals(pressure, record.storedSamples(MinuteSignal.INSPIRATORY_DURATION))
        assertEquals(
            setOf(MinuteSignal.LEAK, MinuteSignal.INSPIRATORY_PRESSURE, MinuteSignal.EXPIRATORY_PRESSURE, MinuteSignal.RESPIRATORY_RATE, MinuteSignal.MINUTE_VENTILATION),
            MinuteSignal.entries.filter { it.factorConfirmed }.toSet(),
        )
    }

    @Test
    fun `a signal whose compression is unconfirmed is not decoded, and cannot vouch for the record's length`() {
        val record = one(
            proto {
                message(5) {
                    signal(MinuteSignal.LEAK, leak)
                    // Written with a modulus other than the table's: decoded, it would disagree or mislead.
                    signal(MinuteSignal.ALVEOLAR_MINUTE_VENTILATION, rate, modulus = 4)
                    signal(MinuteSignal.MEAN_INSPIRATORY_TIME, pressure)
                    varint(15, 1)
                }
            },
        )
        assertEquals(setOf(MinuteSignal.LEAK), record.signals)
        assertEquals(setOf(MinuteSignal.ALVEOLAR_MINUTE_VENTILATION, MinuteSignal.MEAN_INSPIRATORY_TIME), record.withheldSignals)
        assertEquals(12, record.sampleCount)
        assertNull(record.storedSamples(MinuteSignal.ALVEOLAR_MINUTE_VENTILATION))
        refused("only withheld signals") { message(5) { signal(MinuteSignal.MEAN_INSPIRATORY_TIME, pressure); varint(15, 1) } }
        assertEquals(
            setOf(MinuteSignal.SPO2, MinuteSignal.HEART_RATE, MinuteSignal.ALVEOLAR_MINUTE_VENTILATION, MinuteSignal.MEAN_INSPIRATORY_TIME),
            MinuteSignal.entries.filterNot { it.layoutConfirmed }.toSet(),
        )
        // A withheld signal of another length altogether does not cost the record either.
        val odd = one(proto { message(5) { signal(MinuteSignal.LEAK, leak); signal(MinuteSignal.SPO2, IntArray(5) { 96 }); varint(15, 1) } })
        assertEquals(12, odd.sampleCount)
        assertEquals(setOf(MinuteSignal.SPO2), odd.withheldSignals)
    }

    private fun refused(why: String, build: Proto.() -> Unit) = refused(why, proto(build))

    @Test
    fun `every signal's field, modulus, factor and unit match the table as corrected by a real capture`() {
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
    fun `only the one-minute interval that has been seen is accepted`() {
        fun withInterval(token: Long?) = proto { message(5) { signal(MinuteSignal.LEAK, leak); if (token != null) varint(15, token) } }
        assertEquals(60_000, one(withInterval(1)).intervalMs)
        for (token in listOf(null, 0L, 2L, 10L, 999L, 1_000L, 60_000L)) refused("interval $token", withInterval(token))
        refused("interval twice") { message(5) { signal(MinuteSignal.LEAK, leak); varint(15, 1); varint(15, 1) } }
        refused("interval of the wrong kind") { message(5) { signal(MinuteSignal.LEAK, leak); bytes(15, byteArrayOf(1)) } }
    }

    @Test
    fun `padding of ones, as the machine writes it, leaves no doubt about the length`() {
        val random = Random(20261116)
        repeat(300) {
            val n = 3 + random.nextInt(60)
            val series = randomSeries(random, n)
            val record = one(proto { record(series) })
            assertEquals(n, record.sampleCount)
            assertEquals(0, record.uncertainTail)
            for ((signal, samples) in series) assertContentEquals(samples, record.storedSamples(signal))
        }
    }

    @Test
    fun `whatever the padding, the true length is never overstated and always within reach`() {
        val random = Random(20261115)
        repeat(300) {
            val n = 2 + random.nextInt(40)
            val series = randomSeries(random, n, seen.filter { random.nextBoolean() }.ifEmpty { listOf(MinuteSignal.LEAK) })
            val record = one(proto { message(5) { for ((signal, samples) in series) signal(signal, samples, padWith = random.nextInt(2)); varint(15, 1) } })
            val most = record.sampleCount + record.uncertainTail
            assertTrue(record.sampleCount <= n, "certain ${record.sampleCount} of $n")
            assertTrue(most >= n)
            for ((signal, samples) in series) {
                assertContentEquals(samples.copyOf(record.sampleCount), record.storedSamples(signal))
                assertContentEquals(samples, record.storedSamples(signal, most)!!.copyOf(n))
            }
        }
    }

    @Test
    fun `a tail that may be padding is reported, and reachable only by naming a settled length`() {
        val flat = IntArray(11) { 50 }
        val record = one(proto { message(5) { signal(MinuteSignal.EXPIRATORY_PRESSURE, flat, padWith = 0); varint(15, 1) } })
        val most = record.sampleCount + record.uncertainTail
        assertTrue(record.uncertainTail > 0)
        assertTrue(record.sampleCount <= 11 && most >= 11)
        assertEquals(record.sampleCount, record.storedSamples(MinuteSignal.EXPIRATORY_PRESSURE)!!.size)
        assertEquals(record.sampleCount, record.quantities(MinuteSignal.EXPIRATORY_PRESSURE)!!.size)
        assertFailsWith<IndexOutOfBoundsException> { record.quantity(MinuteSignal.EXPIRATORY_PRESSURE, record.sampleCount) }
        assertFailsWith<IndexOutOfBoundsException> { record.minuteEnd(record.sampleCount) }
        assertEquals(BigDecimal("10"), record.quantity(MinuteSignal.EXPIRATORY_PRESSURE, record.sampleCount, length = most)!!.exact())
        assertEquals(record.firstMinuteEnd + (most - 1) * 60_000L, record.minuteEnd(most - 1, length = most))
        assertFailsWith<IllegalArgumentException> { record.storedSamples(MinuteSignal.EXPIRATORY_PRESSURE, most + 1) }
        assertFailsWith<IllegalArgumentException> { record.quantities(MinuteSignal.EXPIRATORY_PRESSURE, record.sampleCount - 1) }
        assertFailsWith<IllegalArgumentException> { record.minuteEnd(0, length = most + 1) }
        // Beside a signal that shows its padding, nothing is in doubt.
        val settled = one(
            proto {
                message(5) {
                    signal(MinuteSignal.EXPIRATORY_PRESSURE, flat, padWith = 0)
                    signal(MinuteSignal.LEAK, IntArray(11) { 3 }, padWith = 1)
                    varint(15, 1)
                }
            },
        )
        assertEquals(11, settled.sampleCount)
        assertEquals(0, settled.uncertainTail)
    }

    @Test
    fun `a confirmed signal written with another modulus is caught in a record of realistic length`() {
        // In a record of a few minutes a wrong modulus can slip through, which is why unconfirmed
        // signals are not decoded at all. At the length of a real session it does not.
        val random = Random(5)
        repeat(50) {
            val series = randomSeries(random, 60 + random.nextInt(120))
            val payload = proto {
                message(5) {
                    for ((signal, samples) in series) signal(signal, samples, modulus = if (signal == MinuteSignal.RESPIRATORY_RATE) 4 else signal.modulus)
                    varint(15, 1)
                }
            }
            refused("respiratory rate at the reference's modulus", payload)
        }
    }

    @Test
    fun `a value that cannot be a measurement takes its signal out of what is served`() {
        val impossible = rate.copyOf().also { it[4] = -4 }
        val tooHigh = pressure.copyOf().also { it[9] = 251 }
        val record = one(proto { record(mapOf(MinuteSignal.LEAK to leak, MinuteSignal.RESPIRATORY_RATE to impossible, MinuteSignal.INSPIRATORY_PRESSURE to tooHigh)) })
        assertEquals(setOf(MinuteSignal.RESPIRATORY_RATE, MinuteSignal.INSPIRATORY_PRESSURE), record.misreadSignals)
        assertEquals(setOf(MinuteSignal.LEAK), record.signals)
        assertNull(record.quantities(MinuteSignal.RESPIRATORY_RATE))
        assertNull(record.storedSamples(MinuteSignal.INSPIRATORY_PRESSURE))
        // The rest of the record stands, at the length all three series agree on.
        assertEquals(12, record.sampleCount)
        assertContentEquals(leak, record.storedSamples(MinuteSignal.LEAK))
        // The limits themselves are measurements.
        val atLimits = one(proto { record(mapOf(MinuteSignal.RESPIRATORY_RATE to rate.copyOf().also { it[0] = 0; it[1] = 360 })) })
        assertTrue(atLimits.misreadSignals.isEmpty())
        assertEquals(setOf(MinuteSignal.RESPIRATORY_RATE), one(proto { record(mapOf(MinuteSignal.RESPIRATORY_RATE to rate.copyOf().also { it[1] = 361 })) }).misreadSignals)
    }

    @Test
    fun `data from firmware that has not been confirmed is not decoded`() {
        val payload = proto { record(mapOf(MinuteSignal.LEAK to leak)) }
        assertEquals(1, MinuteDecoder.decode(payload, "SW04600.19.8.8.0.0123456789").decoded.size)
        for (other in listOf("SW04600.19.8.9.0.dced143ae", "SW04600.19.8.8.01.x", "SW04601.19.8.8.0.x", "", "19.8.8.0")) {
            assertFailsWith<SpoolDecodeException>(other) { MinuteDecoder.decode(payload, other) }
        }
    }

    @Test
    fun `signals that disagree about one record's length condemn the whole download`() {
        val log = MinuteDecoder.decode(
            proto {
                record(mapOf(MinuteSignal.LEAK to leak))
                message(5) { signal(MinuteSignal.LEAK, leak); signal(MinuteSignal.RESPIRATORY_RATE, rate.copyOf(8)); varint(15, 1) }
                record(mapOf(MinuteSignal.LEAK to intArrayOf(1, 2, 3)), start = t + 3_600_000)
            },
            FIRMWARE,
        )
        // A wrong modulus can pass in a short record, so its neighbours are not believed either.
        assertTrue(log.decoded.isEmpty())
        assertEquals(listOf(0, 1, 2), log.failures.map { it.ordinal })
        assertNull(log.allOrNull())
    }

    @Test
    fun `several records decode independently, and a bad one keeps its place`() {
        val log = MinuteDecoder.decode(
            proto {
                record(mapOf(MinuteSignal.LEAK to leak))
                message(5) { signal(MinuteSignal.LEAK, leak, status = 2); varint(15, 1) }
                record(mapOf(MinuteSignal.LEAK to intArrayOf(1, 2, 3)), start = t + 3_600_000)
            },
            FIRMWARE,
        )
        assertEquals(listOf(12, 3), log.decoded.map { it.sampleCount })
        assertEquals(MachineTime(t + 3_600_000), log.decoded[1].firstMinuteEnd)
        assertEquals(listOf(1), log.failures.map { it.ordinal })
        assertEquals(3, log.entries.size)
        assertTrue(MinuteDecoder.decode(ByteArray(0), FIRMWARE).entries.isEmpty())
    }

    @Test
    fun `fields this version does not know are counted and skipped`() {
        val record = one(
            proto {
                message(5) {
                    signal(MinuteSignal.LEAK, leak)
                    message(12) { varint(1, 1) }
                    varint(16, 3)
                    message(MinuteSignal.RESPIRATORY_RATE.field) { varint(1, 1); varint(2, t); bytes(3, riceBlock(rate, 8)); varint(7, 9) }
                    varint(15, 1)
                }
            },
        )
        assertEquals(3, record.unknownFields)
        assertContentEquals(rate, record.storedSamples(MinuteSignal.RESPIRATORY_RATE))
    }

    @Test
    fun `records that contradict themselves or the layout are refused`() {
        val block = riceBlock(leak, 4)
        fun leakSeries(build: Proto.() -> Unit) = proto { message(5) { message(1, build); varint(15, 1) } }
        assertFailsWith<SpoolDecodeException> { MinuteDecoder.decode(proto { message(2) { signal(MinuteSignal.LEAK, leak) } }, FIRMWARE) }
        refused("signals starting at different times") { message(5) { signal(MinuteSignal.LEAK, leak); signal(MinuteSignal.RESPIRATORY_RATE, rate, start = t + 60_000); varint(15, 1) } }
        refused("a kind of series never seen") { message(5) { signal(MinuteSignal.LEAK, leak, status = 2); varint(15, 1) } }
        refused("no kind marker") { message(5) { signal(MinuteSignal.LEAK, leak, status = null); varint(15, 1) } }
        refused("no start", leakSeries { varint(1, 1); bytes(3, block) })
        refused("no block", leakSeries { varint(1, 1); varint(2, t) })
        refused("a start before 2015", leakSeries { varint(1, 1); varint(2, t / 1000); bytes(3, block) })
        refused("kind twice", leakSeries { varint(1, 1); varint(1, 1); varint(2, t); bytes(3, block) })
        refused("start twice", leakSeries { varint(1, 1); varint(2, t); varint(2, t); bytes(3, block) })
        refused("block twice", leakSeries { varint(1, 1); varint(2, t); bytes(3, block); bytes(3, block) })
        refused("kind of the wrong type", leakSeries { bytes(1, byteArrayOf(1)); varint(2, t); bytes(3, block) })
        refused("start of the wrong type", leakSeries { varint(1, 1); bytes(2, byteArrayOf(1)); bytes(3, block) })
        refused("block of the wrong type", leakSeries { varint(1, 1); varint(2, t); varint(3, 5) })
        refused("no signals") { message(5) { varint(15, 1) } }
        refused("a signal twice") { message(5) { signal(MinuteSignal.LEAK, leak); signal(MinuteSignal.LEAK, leak); varint(15, 1) } }
        refused("a signal of the wrong type") { message(5) { varint(4, 60); varint(15, 1) } }
        val long = riceBlock(IntArray(40) { it * it % 50 }, 4)
        refused("a series cut in the middle", leakSeries { varint(1, 1); varint(2, t); bytes(3, long.copyOf(long.size - 1).also { it[it.size - 1] = 0xFF.toByte(); it[it.size - 2] = 0xFF.toByte() }) })
    }
}
