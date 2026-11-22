// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.core.nights

import com.everysoft.paprelay.core.MachineTime
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventReportTest {
    private fun at(text: String) = OffsetDateTime.parse(text).toInstant().toEpochMilli()

    private fun night(date: LocalDate, offsetMinutes: Int = -420, hours: Int = 24, complete: Boolean = true) =
        Night(date, offsetMinutes, 300, 1, emptyMap(), complete, hours)

    private fun event(kind: BreathingEventKind, machine: String, durationMs: Long? = null, correctedMs: Long? = null) =
        BreathingEvent(kind, MachineTime(at(machine)), durationMs, correctedMs)

    @Test
    fun `one line for each event, newest first, with its night, both times, what the stamp marks, and its length`() {
        val nights = listOf(night(LocalDate.of(2026, 11, 14)), night(LocalDate.of(2026, 11, 15)))
        val apnea = event(BreathingEventKind.OBSTRUCTIVE_APNEA, "2026-11-15T02:10:00.500-07:00", 10_500, at("2026-11-15T02:04:00.250-07:00"))
        val hypopnea = event(BreathingEventKind.HYPOPNEA, "2026-11-15T23:40:00-07:00")
        val central = event(BreathingEventKind.CENTRAL_APNEA, "2026-11-15T02:11:00-07:00", 12_000, at("2026-11-15T02:05:00-07:00"))
        val lines = EventReport.csv(listOf(apnea, hypopnea, central), nights).split("\r\n")
        assertEquals("night_of,event,time_corrected,time_machine_clock,time_is,duration_seconds", lines[0])
        assertEquals("2026-11-15,hypopnea,,2026-11-15T23:40:00-07:00,start_or_end,", lines[1])
        assertEquals("2026-11-14,central_apnea,2026-11-15T02:05:00-07:00,2026-11-15T02:11:00-07:00,end,12", lines[2])
        assertEquals("2026-11-14,obstructive_apnea,2026-11-15T02:04:00.250-07:00,2026-11-15T02:10:00.500-07:00,end,10.5", lines[3])
        assertEquals("", lines[4])
        assertEquals(5, lines.size)
        // Every line has the same number of cells, and no cell needs quoting.
        assertEquals(setOf(6), lines.dropLast(1).map { it.split(',').size }.toSet())
        assertFalse(lines.any { '"' in it })
        // The same events in any order make the same table, to the letter.
        assertEquals(EventReport.csv(listOf(apnea, hypopnea, central), nights), EventReport.csv(listOf(central, apnea, hypopnea), nights.reversed()))
        // No events: the header alone.
        assertEquals("night_of,event,time_corrected,time_machine_clock,time_is,duration_seconds\r\n", EventReport.csv(emptyList(), nights))
        // An apnoea stored without a length is not claimed to be stamped at its end; an unclassified one with a length is.
        val bare = event(BreathingEventKind.CENTRAL_APNEA, "2026-11-15T03:00:00-07:00")
        val unclassified = event(BreathingEventKind.UNCLASSIFIED_APNEA, "2026-11-15T03:01:00-07:00", 15_000)
        val more = EventReport.csv(listOf(bare, unclassified), nights).split("\r\n")
        assertEquals("2026-11-14,unclassified_apnea,,2026-11-15T03:01:00-07:00,end,15", more[1])
        assertEquals("2026-11-14,central_apnea,,2026-11-15T03:00:00-07:00,start_or_end,", more[2])
    }

    @Test
    fun `an event belongs to the night whose day holds its stamp, from noon up to the next noon`() {
        val nights = listOf(night(LocalDate.of(2026, 11, 14)), night(LocalDate.of(2026, 11, 15)))
        assertEquals(LocalDate.of(2026, 11, 14), EventReport.nightOf(at("2026-11-14T12:00:00-07:00"), nights)?.date)
        assertEquals(LocalDate.of(2026, 11, 14), EventReport.nightOf(at("2026-11-15T11:59:59.999-07:00"), nights)?.date)
        assertEquals(LocalDate.of(2026, 11, 15), EventReport.nightOf(at("2026-11-15T12:00:00-07:00"), nights)?.date)
        // Before the first night kept, or after the last ends: no night, and the cell says nothing.
        assertNull(EventReport.nightOf(at("2026-11-14T11:59:59.999-07:00"), nights))
        assertNull(EventReport.nightOf(at("2026-11-16T12:00:00-07:00"), nights))
        // After the last finished night (a day still running, or not yet summed up): not written yet, though kept.
        val later = event(BreathingEventKind.HYPOPNEA, "2026-11-20T01:00:00-07:00")
        assertEquals(1, EventReport.csv(listOf(later), nights).split("\r\n").filter { it.isNotEmpty() }.size)
        val running = nights + night(LocalDate.of(2026, 11, 16), complete = false)
        assertTrue(EventReport.shown(listOf(event(BreathingEventKind.HYPOPNEA, "2026-11-16T23:00:00-07:00")), running).isEmpty())
        assertEquals(1, EventReport.shown(listOf(event(BreathingEventKind.HYPOPNEA, "2026-11-15T23:00:00-07:00")), running).size)
        // With no night finished, nothing at all.
        assertTrue(EventReport.shown(listOf(event(BreathingEventKind.HYPOPNEA, "2026-11-15T23:00:00-07:00")), listOf(night(LocalDate.of(2026, 11, 15), complete = false))).isEmpty())
        // Between two nights kept (a day missing): no night, and the time at the nearest night's offset.
        val gap = listOf(night(LocalDate.of(2026, 11, 14), offsetMinutes = -420), night(LocalDate.of(2026, 11, 17), offsetMinutes = -480))
        val near14 = EventReport.csv(listOf(event(BreathingEventKind.HYPOPNEA, "2026-11-15T13:00:00-07:00")), gap).lines()[1]
        assertEquals(",hypopnea,,2026-11-15T13:00:00-07:00,start_or_end,", near14)
        val near17 = EventReport.csv(listOf(event(BreathingEventKind.HYPOPNEA, "2026-11-17T10:00:00-08:00")), gap).lines()[1]
        assertEquals(",hypopnea,,2026-11-17T10:00:00-08:00,start_or_end,", near17)
        assertEquals(0, EventReport.offsetNear(0, emptyList()))
    }

    @Test
    fun `where the clocks change in a night, its day is as long as the machine said, and the next begins where it ends`() {
        // Clocks go back at 02:00 on 1 November 2026 in North America: that day runs 25 hours.
        val autumn = listOf(night(LocalDate.of(2026, 10, 31), offsetMinutes = -360, hours = 25), night(LocalDate.of(2026, 11, 1), offsetMinutes = -420))
        assertEquals(LocalDate.of(2026, 10, 31), EventReport.nightOf(at("2026-11-01T11:30:00-07:00"), autumn)?.date)
        assertEquals(LocalDate.of(2026, 11, 1), EventReport.nightOf(at("2026-11-01T12:00:00-07:00"), autumn)?.date)
        // The event is written at its night's offset, which is the one the night began with.
        val line = EventReport.csv(listOf(event(BreathingEventKind.HYPOPNEA, "2026-11-01T03:30:00-07:00")), autumn).lines()[1]
        assertEquals("2026-10-31,hypopnea,,2026-11-01T04:30:00-06:00,start_or_end,", line)
        // Clocks go forward at 02:00 on 8 March 2026: that day runs 23 hours, and nothing falls between two nights.
        val spring = listOf(night(LocalDate.of(2026, 3, 7), offsetMinutes = -420, hours = 23), night(LocalDate.of(2026, 3, 8), offsetMinutes = -360))
        assertEquals(LocalDate.of(2026, 3, 7), EventReport.nightOf(at("2026-03-08T11:59:59-06:00"), spring)?.date)
        assertEquals(LocalDate.of(2026, 3, 8), EventReport.nightOf(at("2026-03-08T12:00:00-06:00"), spring)?.date)
    }

    @Test
    fun `an event's length is as stored, and one that cannot be is refused`() {
        assertEquals("0", event(BreathingEventKind.OBSTRUCTIVE_APNEA, "2026-11-15T02:10:00-07:00", 0).durationSeconds!!.toPlainString())
        assertEquals("10.001", event(BreathingEventKind.OBSTRUCTIVE_APNEA, "2026-11-15T02:10:00-07:00", 10_001).durationSeconds!!.toPlainString())
        assertFailsWith<IllegalArgumentException> { event(BreathingEventKind.OBSTRUCTIVE_APNEA, "2026-11-15T02:10:00-07:00", -1) }
        assertFailsWith<IllegalArgumentException> { event(BreathingEventKind.OBSTRUCTIVE_APNEA, "2026-11-15T02:10:00-07:00", BreathingEvent.MAX_DURATION_MS + 1) }
        assertTrue(BreathingEventKind.entries.all { BreathingEventKind.ofKey(it.key) == it })
        assertNull(BreathingEventKind.ofKey("rera"))
    }
}
