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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MinuteDecoderTest {
    private val t = 1_794_000_000_000L

    private companion object {
        /** The software of the machine the layouts were confirmed on. */
        val FIRMWARE = Firmware.reportedByMachine("SW04600.19.8.8.0.dced143ae")
    }

    private fun MinuteRecord.quantities(signal: MinuteSignal, length: Int = sampleCount, allowUnconfirmed: Boolean = false): List<com.everysoft.paprelay.core.Quantity?>? =
        (readings(signal, length, allowUnconfirmed) as? SignalReadings.Values)?.values

    private fun MinuteRecord.quantity(signal: MinuteSignal, index: Int, length: Int = sampleCount, allowUnconfirmed: Boolean = false): com.everysoft.paprelay.core.Quantity? {
        minuteEnd(index, length) // the same bounds
        return quantities(signal, length, allowUnconfirmed)?.get(index)
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
        assertEquals(rate.map { BigDecimal(it).divide(BigDecimal(4)).stripTrailingZeros() }, record.quantities(MinuteSignal.RESPIRATORY_RATE)!!.map { it!!.exact().stripTrailingZeros() })
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
        assertEquals(SignalReadings.Withheld, record.readings(MinuteSignal.ALVEOLAR_MINUTE_VENTILATION))
        assertEquals(SignalReadings.Withheld, record.readings(MinuteSignal.ALVEOLAR_MINUTE_VENTILATION, allowUnconfirmed = true))
        assertEquals(SignalReadings.NotRecorded, record.readings(MinuteSignal.RESPIRATORY_RATE))
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
        assertNull(record.storedSamples(MinuteSignal.INSPIRATORY_PRESSURE))
        // Each of the four answers is told apart: here, misread is not "not recorded".
        assertEquals(SignalReadings.Misread, record.readings(MinuteSignal.RESPIRATORY_RATE))
        assertEquals(SignalReadings.NotRecorded, record.readings(MinuteSignal.MINUTE_VENTILATION))
        assertIs<SignalReadings.Values>(record.readings(MinuteSignal.LEAK))
        // The rest of the record stands, at the length all three series agree on.
        assertEquals(12, record.sampleCount)
        assertContentEquals(leak, record.storedSamples(MinuteSignal.LEAK))
        // The limits themselves are measurements.
        val atLimits = one(proto { record(mapOf(MinuteSignal.RESPIRATORY_RATE to rate.copyOf().also { it[0] = 0; it[1] = 360 })) })
        assertTrue(atLimits.misreadSignals.isEmpty())
        assertEquals(setOf(MinuteSignal.RESPIRATORY_RATE), one(proto { record(mapOf(MinuteSignal.RESPIRATORY_RATE to rate.copyOf().also { it[1] = 361 })) }).misreadSignals)
    }

    @Test
    fun `a series that will not decode fails its record and casts doubt on the short ones beside it`() {
        // A long session whose respiratory rate was written with another modulus fails to decode,
        // and a short one beside it can pass with wrong values. The long one is not served, and
        // the download says that its short records are not to be believed on their own.
        val random = Random(11)
        var condemned = 0
        repeat(40) {
            val long = randomSeries(random, 150)
            val short = randomSeries(random, 4)
            val log = MinuteDecoder.decode(
                proto {
                    message(5) { for ((signal, samples) in long) signal(signal, samples, modulus = if (signal == MinuteSignal.RESPIRATORY_RATE) 4 else signal.modulus); varint(15, 1) }
                    message(5) { for ((signal, samples) in short) signal(signal, samples, start = t + 12 * 3_600_000L, modulus = if (signal == MinuteSignal.RESPIRATORY_RATE) 4 else signal.modulus); varint(15, 1) }
                },
                FIRMWARE,
            )
            assertTrue(log.decoded.none { it.sampleCount > 4 }, "served the long record")
            assertTrue(log.doubted)
            assertTrue(log.failures.isNotEmpty())
            condemned++
        }
        assertEquals(40, condemned)
    }

    @Test
    fun `the ceilings are the ranges the machine itself declares`() {
        assertEquals(
            listOf("LEAK=100", "INSPIRATORY_PRESSURE=250", "EXPIRATORY_PRESSURE=250", "RESPIRATORY_RATE=360", "INSPIRATORY_DURATION=32767", "MINUTE_VENTILATION=75", "IE_RATIO=32767"),
            MinuteSignal.entries.filter { it.layoutConfirmed }.map { "${it.name}=${it.maxStored}" },
        )
        // Each one bites: a value one above it sets the signal aside, the value itself does not.
        for (signal in MinuteSignal.entries.filter { it.layoutConfirmed && it.maxStored < 32767 }) {
            val top = signal.maxStored
            val within = one(proto { record(mapOf(signal to intArrayOf(top - 1, top, top - 1, top - 2))) })
            assertTrue(within.misreadSignals.isEmpty(), "$signal at its ceiling")
            val above = one(proto { record(mapOf(signal to intArrayOf(top - 1, top + 1, top - 1, top - 2))) })
            assertEquals(setOf(signal), above.misreadSignals, "$signal above its ceiling")
        }
    }

    @Test
    fun `a stored zero is no value at all, except for leak`() {
        val record = one(proto { record(mapOf(MinuteSignal.LEAK to intArrayOf(0, 0, 3, 4), MinuteSignal.RESPIRATORY_RATE to intArrayOf(0, 56, 0, 60))) })
        // Nobody breathes zero times a minute; what the machine means by it is not known.
        assertEquals(listOf(null, "14", null, "15"), record.quantities(MinuteSignal.RESPIRATORY_RATE)!!.map { it?.exact()?.toPlainString() })
        // A leak of zero is an ordinary, and welcome, reading.
        assertEquals(listOf("0", "0", "3.6", "4.8"), record.quantities(MinuteSignal.LEAK)!!.map { it?.exact()?.toPlainString() })
        assertTrue(record.misreadSignals.isEmpty())
        assertEquals(listOf(MinuteSignal.LEAK), MinuteSignal.entries.filterNot { it.zeroIsUnknown })
    }

    @Test
    fun `records must follow one another in time`() {
        val hour = 3_600_000L
        fun log(vararg starts: Long) = MinuteDecoder.decode(proto { for (start in starts) record(mapOf(MinuteSignal.LEAK to leak), start = start) }, FIRMWARE)
        assertTrue(log(t, t + hour, t + 2 * hour).complete)
        // Out of order, the same session twice, and one that begins inside the one before it.
        assertEquals(listOf(1), log(t + hour, t, t + 2 * hour).failures.map { it.ordinal })
        assertEquals(listOf(1), log(t, t, t + hour).failures.map { it.ordinal })
        assertEquals(listOf(1), log(t, t + 11 * 60_000L).failures.map { it.ordinal }) // twelve samples: the last ends at t + 11 min
        assertTrue(log(t, t + 12 * 60_000L).complete)
        // The record after a refused one is measured against the last one accepted.
        assertEquals(listOf(1, 2), log(t + hour, t, t + hour).failures.map { it.ordinal })
    }

    @Test
    fun `a doubtful tail neither condemns a signal nor can be read when it cannot be real`() {
        // A steep, steady climb, zero padded: the padding carries the climb on past anything measurable.
        val climbing = IntArray(11) { 40 + it * 30 } // ends at 340, within 360; the next step would be 370
        val record = one(proto { message(5) { signal(MinuteSignal.RESPIRATORY_RATE, climbing, padWith = 0); varint(15, 1) } })
        assertTrue(record.uncertainTail > 0, "this needs a tail to mean anything")
        // The certain samples are fine, so the signal is served.
        assertTrue(record.misreadSignals.isEmpty())
        assertEquals(record.sampleCount, record.quantities(MinuteSignal.RESPIRATORY_RATE)!!.size)
        // Naming a length that takes in impossible values is refused: they show the tail to be padding.
        val most = record.sampleCount + record.uncertainTail
        if (record.storedSamples(MinuteSignal.RESPIRATORY_RATE, most)!!.any { it > 360 }) {
            assertFailsWith<IllegalArgumentException> { record.readings(MinuteSignal.RESPIRATORY_RATE, most) }
        }
    }

    @Test
    fun `the log is decoded for every version whose data is read, and for no other software`() {
        val payload = proto { record(mapOf(MinuteSignal.LEAK to leak)) }
        for (version in listOf("SW04600.19.8.8.0.0123456789", "SW04600.19.8.9.0.dced143ae", "SW04600.20.0.0.0.abc", "SW04600.18.1.0.0.abc")) {
            assertEquals(1, MinuteDecoder.decode(payload, Firmware.reportedByMachine(version)).decoded.size, version)
        }
        for (other in listOf("SW04601.19.8.8.0.x", "SW04700.19.8.8.0.abc", "", "19.8.8.0", "SW04600.19.8.8.0.", "xSW04600.19.8.8.0.abc", "sw04600.19.8.8.0.abc")) {
            val firmware = Firmware.reportedByMachine(other)
            assertTrue(!firmware.layoutsAccepted, other)
            assertFailsWith<SpoolDecodeException>(other) { MinuteDecoder.decode(payload, firmware) }
        }
    }

    @Test
    fun `signals that disagree about one record's length fail that record and cast doubt on the download`() {
        val log = MinuteDecoder.decode(
            proto {
                record(mapOf(MinuteSignal.LEAK to leak))
                message(5) { signal(MinuteSignal.LEAK, leak); signal(MinuteSignal.RESPIRATORY_RATE, rate.copyOf(8)); varint(15, 1) }
                record(mapOf(MinuteSignal.LEAK to intArrayOf(1, 2, 3)), start = t + 3_600_000)
            },
            FIRMWARE,
        )
        // It may be one record cut short; it may be a wrong modulus, which can pass in a short record.
        // The record fails, its neighbours keep their places, and the caller is told of the doubt.
        assertEquals(listOf(12, 3), log.decoded.map { it.sampleCount })
        assertEquals(listOf(1), log.failures.map { it.ordinal })
        assertTrue(log.doubted)
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
        // A record that is merely of a kind not known says nothing against the layout.
        assertTrue(!log.doubted)
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
