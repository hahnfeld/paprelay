// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11.plan

import com.everysoft.paprelay.as11.decode.MinuteDecoder
import com.everysoft.paprelay.as11.decode.MinuteRecord
import com.everysoft.paprelay.as11.decode.MinuteSignal
import com.everysoft.paprelay.as11.decode.Proto
import com.everysoft.paprelay.as11.decode.int16Block
import com.everysoft.paprelay.as11.decode.proto
import com.everysoft.paprelay.as11.decode.riceBlock
import com.everysoft.paprelay.core.MachineTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RespiratoryRatePlanTest {
    private val t = 1_794_556_805_988L
    private val tag = "m-4f2a9c"

    private fun Proto.signal(signal: MinuteSignal, samples: IntArray, padWith: Int = 1) {
        message(signal.field) {
            varint(1, 1)
            varint(2, t)
            bytes(3, signal.modulus?.let { riceBlock(samples, it, padWith) } ?: int16Block(samples))
        }
    }

    private fun record(vararg series: Pair<MinuteSignal, IntArray>, padWith: Int = 1): MinuteRecord =
        MinuteDecoder.decode(proto { message(5) { for ((signal, samples) in series) signal(signal, samples, padWith); varint(15, 1) } }, com.everysoft.paprelay.as11.decode.Firmware.reportedByMachine("SW04600.19.8.8.0.abc123")).decoded.single()

    //                       minute:   0   1   2   3   4   5    6   7
    private val rate = intArrayOf(43, 66, 52, 0, 54, 56, 360, 58) // quarters of a breath a minute
    private val leak = intArrayOf(1, 1, 20, 1, 21, 1, 1, 100) // 1.2 L/min each; 20 is exactly 24 L/min

    @Test
    fun `each minute is passed on as the machine recorded it, or left out for a stated reason`() {
        val plan = assertIs<SessionPlan.Planned>(RespiratoryRatePlan.session(record(MinuteSignal.LEAK to leak, MinuteSignal.RESPIRATORY_RATE to rate), tag, 24))
        // Minute 3 has a rate of zero; minutes 4 and 7 leak more than 24 L/min. Minute 2, at exactly 24, stays.
        assertEquals(listOf(0, 1, 2, 5, 6), plan.readings.minutes)
        assertEquals(listOf(1075, 1650, 1300, 1400, 9000), plan.readings.readings.map { it.hundredthsPerMinute })
        assertEquals(listOf(10.75, 16.5, 13.0, 14.0, 90.0), plan.readings.readings.map { it.breathsPerMinute })
        assertEquals(MachineTime(t), plan.readings.firstMinuteEnd)
        assertEquals(MachineTime(t + 5 * 60_000L), plan.readings.readings[3].minuteEnd)
        assertEquals(1, plan.omitted.zeroRate)
        assertEquals(2, plan.omitted.highLeak)
        assertEquals(0, plan.omitted.uncertain)
        // Every minute is accounted for.
        assertEquals(rate.size, plan.readings.readings.size + plan.omitted.zeroRate + plan.omitted.highLeak + plan.omitted.uncertain)
        assertEquals("rr-$tag-$t", com.everysoft.paprelay.core.sync.SessionReadings.recordId(tag, plan.readings.firstMinuteEnd, 0))
    }

    @Test
    fun `the leak rule can be moved or switched off, and then needs no leak signal`() {
        val both = record(MinuteSignal.LEAK to leak, MinuteSignal.RESPIRATORY_RATE to rate)
        assertEquals(listOf(0, 1, 2, 4, 5, 6, 7), assertIs<SessionPlan.Planned>(RespiratoryRatePlan.session(both, tag, null)).readings.minutes)
        assertEquals(listOf(0, 1, 5, 6), assertIs<SessionPlan.Planned>(RespiratoryRatePlan.session(both, tag, 23)).readings.minutes)
        assertEquals(listOf(0, 1, 2, 4, 5, 6), assertIs<SessionPlan.Planned>(RespiratoryRatePlan.session(both, tag, 26)).readings.minutes)
        assertTrue(assertIs<SessionPlan.Planned>(RespiratoryRatePlan.session(both, tag, 0)).readings.readings.isEmpty())
        val rateOnly = record(MinuteSignal.RESPIRATORY_RATE to rate)
        assertEquals(7, assertIs<SessionPlan.Planned>(RespiratoryRatePlan.session(rateOnly, tag, null)).readings.readings.size)
        // With a limit set and nothing to apply it with, nothing is written: not "everything".
        val noLeak = assertIs<SessionPlan.Unplanned>(RespiratoryRatePlan.session(rateOnly, tag, 24))
        assertEquals(SessionPlan.Reason.NO_USABLE_LEAK, noLeak.reason)
        assertTrue(noLeak.minutesUnknown)
        assertFailsWith<IllegalArgumentException> { RespiratoryRatePlan.session(both, tag, -1) }
    }

    @Test
    fun `a session with no rate that can be trusted is not planned at all`() {
        val leakOnly = record(MinuteSignal.LEAK to leak)
        val noRate = assertIs<SessionPlan.Unplanned>(RespiratoryRatePlan.session(leakOnly, tag, 24))
        assertEquals(SessionPlan.Reason.NO_USABLE_RATE, noRate.reason)
        // The machine recorded no rate at all: nothing is missing, and there is nothing to wait for.
        assertTrue(!noRate.minutesUnknown)
        // A rate series holding an impossible value is set aside by the decoder, whole.
        val wild = record(MinuteSignal.LEAK to leak, MinuteSignal.RESPIRATORY_RATE to rate.copyOf().also { it[2] = 361 })
        val misread = assertIs<SessionPlan.Unplanned>(RespiratoryRatePlan.session(wild, tag, 24))
        assertEquals(SessionPlan.Reason.NO_USABLE_RATE, misread.reason)
        // Here the minutes exist and could not be read: they are unknown, not absent.
        assertTrue(misread.minutesUnknown)
        // And the same for a leak series, when the leak rule is on.
        val wildLeak = record(MinuteSignal.LEAK to leak.copyOf().also { it[2] = -1 }, MinuteSignal.RESPIRATORY_RATE to rate)
        assertEquals(SessionPlan.Reason.NO_USABLE_LEAK, assertIs<SessionPlan.Unplanned>(RespiratoryRatePlan.session(wildLeak, tag, 24)).reason)
        assertIs<SessionPlan.Planned>(RespiratoryRatePlan.session(wildLeak, tag, null))
    }

    @Test
    fun `minutes that may be padding are not planned`() {
        // A flat series padded with zeros: its last minutes cannot be told from padding.
        val flat = IntArray(11) { 52 }
        val doubtful = record(MinuteSignal.RESPIRATORY_RATE to flat, padWith = 0)
        assertTrue(doubtful.uncertainTail > 0)
        val plan = assertIs<SessionPlan.Planned>(RespiratoryRatePlan.session(doubtful, tag, null))
        assertEquals(doubtful.sampleCount, plan.readings.readings.size)
        assertTrue(plan.readings.readings.size <= 11)
        // They are counted, so that nothing is left out without a trace.
        assertEquals(doubtful.uncertainTail, plan.omitted.uncertain)
        assertTrue(plan.readings.readings.size + plan.omitted.uncertain >= 11)
    }

    @Test
    fun `the same record planned twice gives the same content`() {
        val a = assertIs<SessionPlan.Planned>(RespiratoryRatePlan.session(record(MinuteSignal.LEAK to leak, MinuteSignal.RESPIRATORY_RATE to rate), tag, 24))
        val b = assertIs<SessionPlan.Planned>(RespiratoryRatePlan.session(record(MinuteSignal.LEAK to leak, MinuteSignal.RESPIRATORY_RATE to rate), tag, 24))
        assertEquals(a.readings.fingerprint(), b.readings.fingerprint())
        assertEquals(a.readings.key, b.readings.key)
    }
}
