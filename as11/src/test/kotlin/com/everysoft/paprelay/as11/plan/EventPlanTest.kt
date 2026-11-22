// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11.plan

import com.everysoft.paprelay.as11.decode.EventDecoder
import com.everysoft.paprelay.as11.decode.Firmware
import com.everysoft.paprelay.as11.decode.Proto
import com.everysoft.paprelay.as11.decode.RespiratoryEvent
import com.everysoft.paprelay.as11.decode.proto
import com.everysoft.paprelay.core.ClockOffset
import com.everysoft.paprelay.core.MachineTime
import com.everysoft.paprelay.core.nights.BreathingEventKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventPlanTest {
    private val t = 1_794_000_000_000L
    private val confirmed = Firmware.reportedByMachine("SW04600.19.8.8.0.dced143ae")

    /** The machine's clock six minutes fast, measured at [t]; the phone's clock then. */
    private val machineNow = MachineTime(t)
    private val trueNow = t - 361_600
    private val offset = ClockOffset.measured(machineNow, trueNow)

    private fun Proto.event(code: Long, at: Long, duration: Long? = null) = message(1) {
        varint(1, code); varint(2, at); varint(3, at)
        if (duration != null) varint(4, duration)
    }

    private fun planned(firmware: Firmware = confirmed, offset: ClockOffset? = this.offset, events: Proto.() -> Unit) =
        EventDecoder.respiratory(proto { message(4) { message(1, events) } }, firmware).let { log ->
            log.decoded.map { EventPlan.event(log, it, ClockCorrection(offset, machineNow, trueNow)) }
        }

    @Test
    fun `the confirmed kinds are passed on as stored, with the corrected time beside the machine's`() {
        val (obstructive, central, hypopnea) = planned {
            event(4, t - 3_600_000, duration = 10_500)
            event(3, t - 3_000_000, duration = 12_000)
            event(2, t - 2_000_000)
        }.map { it!! }
        assertEquals(BreathingEventKind.OBSTRUCTIVE_APNEA, obstructive.kind)
        assertEquals(MachineTime(t - 3_600_000), obstructive.machineTime)
        assertEquals(10_500, obstructive.durationMs)
        assertEquals("10.5", obstructive.durationSeconds!!.toPlainString())
        // The machine is six minutes fast: the true moment is that much earlier.
        assertEquals(t - 3_600_000 - 361_600, obstructive.correctedMs)
        assertEquals(BreathingEventKind.CENTRAL_APNEA, central.kind)
        assertEquals("12", central.durationSeconds!!.toPlainString())
        // A hypopnoea has no stored length, and none is given.
        assertEquals(BreathingEventKind.HYPOPNEA, hypopnea.kind)
        assertNull(hypopnea.durationMs)
        assertNull(hypopnea.durationSeconds)
        assertEquals("BreathingEvent", "$hypopnea")
    }

    @Test
    fun `what the stamp marks is what the decoder knows of it`() {
        // An apnoea's stamp is its end, so the decoder gives its onset; a hypopnoea's is not known, and it gives none.
        val log = EventDecoder.respiratory(proto { message(4) { message(1) { event(4, t, 10_000); event(3, t, 10_000); event(5, t, 10_000); event(2, t, 10_000) } } }, confirmed)
        for (record in log.decoded) {
            val kind = EventPlan.event(log, record, ClockCorrection(offset, machineNow, trueNow))!!.kind
            assertEquals(record.onset != null, kind.stampIsEnd, kind.key)
        }
        assertEquals(
            setOf(RespiratoryEvent.HYPOPNEA, RespiratoryEvent.CENTRAL_APNEA, RespiratoryEvent.OBSTRUCTIVE_APNEA, RespiratoryEvent.APNEA),
            RespiratoryEvent.entries.filter { it.confirmed }.toSet(),
        )
        // The kinds passed on are exactly the confirmed ones, each under a name of its own.
        assertEquals(RespiratoryEvent.entries.filter { it.confirmed }.toSet(), EventPlan.kinds.keys)
        assertEquals(BreathingEventKind.entries.toSet(), EventPlan.kinds.values.toSet())
    }

    @Test
    fun `kinds never seen in a capture, and codes not known at all, are not passed on`() {
        val all = planned { for (code in listOf(1L, 6L, 8L, 9L, 42L)) event(code, t - 60_000) }
        assertEquals(5, all.size)
        assertTrue(all.all { it == null })
    }

    @Test
    fun `an unclassified apnoea is passed on, stamped at its end with its length, and the version of the kinds says so`() {
        val event = planned { event(5, t - 60_000, 14_000) }.single()!!
        assertEquals(BreathingEventKind.UNCLASSIFIED_APNEA, event.kind)
        assertEquals(14_000, event.durationMs)
        assertTrue(event.stampIsEnd)
        // Stored without a length, its stamp is not claimed to be its end.
        assertTrue(!planned { event(5, t - 60_000) }.single()!!.stampIsEnd)
        assertEquals("central_apnea,hypopnea,obstructive_apnea,unclassified_apnea", EventPlan.VERSION)
    }

    @Test
    fun `nothing is passed on from software whose data is not read`() {
        val other = planned(Firmware.reportedByMachine("SW04700.20.1.0.0.abc")) { event(4, t - 60_000, 10_000) }
        assertEquals(listOf(null), other)
    }

    @Test
    fun `an event is corrected only where the clock's error is known well enough`() {
        val window = ClockCorrection.WINDOW_MS
        val events = planned {
            event(2, t - window)
            event(2, t - window - 1)
            // Stamped later than now: corrected, as long as that is not in the future by the phone's clock.
            event(2, t + 30_000)
            event(2, t + 361_600 + ClockCorrection.FUTURE_ALLOWANCE_MS + 1)
        }.map { it!! }
        assertEquals(t - window - 361_600, events[0].correctedMs)
        // Older than the window: the machine's time alone.
        assertNull(events[1].correctedMs)
        assertEquals(MachineTime(t - window - 1), events[1].machineTime)
        assertEquals(t + 30_000 - 361_600, events[2].correctedMs)
        assertNull(events[3].correctedMs)
        // With no offset to go by (the clock was changed), none is corrected.
        assertNull(planned(offset = null) { event(4, t - 60_000, 10_000) }.single()!!.correctedMs)
    }

    @Test
    fun `an event's identity is its kind, stamp and length, as stored`() {
        val (a, b, c, d) = planned {
            event(4, t - 60_000, 10_000)
            event(4, t - 60_000, 10_000)
            event(4, t - 60_000, 11_000)
            event(3, t - 60_000, 10_000)
        }.map { it!! }
        assertEquals(a.identity, b.identity)
        assertEquals(3, setOf(a.identity, c.identity, d.identity).size)
        // The corrected time is not part of it: corrected in one run and not in another, it is the same event.
        assertEquals(a.identity, planned(offset = null) { event(4, t - 60_000, 10_000) }.single()!!.identity)
    }
}
