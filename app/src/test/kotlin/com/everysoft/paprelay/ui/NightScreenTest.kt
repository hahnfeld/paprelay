// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.ui

import com.everysoft.paprelay.as11.plan.NightPlan
import com.everysoft.paprelay.core.MachineTime
import com.everysoft.paprelay.core.nights.BreathingEvent
import com.everysoft.paprelay.core.nights.BreathingEventKind
import com.everysoft.paprelay.core.nights.Night
import com.everysoft.paprelay.sync.Events
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NightScreenTest {
    @Test
    fun `the night screen shows every figure a night can have, once, and none that it cannot`() {
        assertEquals(NIGHT_SCREEN_FIGURES.size, NIGHT_SCREEN_FIGURES.toSet().size)
        assertEquals(NightPlan.FIGURES.toSet(), NIGHT_SCREEN_FIGURES.toSet())
    }

    private fun at(text: String) = MachineTime(OffsetDateTime.parse(text).toInstant().toEpochMilli())

    private fun night(date: LocalDate) = Night(date, -420, 300, 1, emptyMap(), true)

    @Test
    fun `events are listed under their nights, newest night and newest event first, and those outside every night apart`() {
        val nights = listOf(night(LocalDate.of(2026, 11, 14)), night(LocalDate.of(2026, 11, 15)), night(LocalDate.of(2026, 11, 17)))
        val events = Events(
            null,
            listOf(
                BreathingEvent(BreathingEventKind.HYPOPNEA, at("2026-11-15T01:00:00-07:00"), null, null),
                BreathingEvent(BreathingEventKind.OBSTRUCTIVE_APNEA, at("2026-11-15T02:00:00-07:00"), 10_000, null),
                BreathingEvent(BreathingEventKind.CENTRAL_APNEA, at("2026-11-15T23:00:00-07:00"), 12_000, null),
                BreathingEvent(BreathingEventKind.HYPOPNEA, at("2026-11-17T01:00:00-07:00"), null, null),
                // After the last finished night: kept, and not shown until that night is finished.
                BreathingEvent(BreathingEventKind.HYPOPNEA, at("2026-11-18T13:00:00-07:00"), null, null),
            ),
            null,
        )
        val groups = groupByNight(events, nights)
        assertEquals(listOf(null, LocalDate.of(2026, 11, 15), LocalDate.of(2026, 11, 14)), groups.map { it.night?.date })
        assertEquals(listOf(BreathingEventKind.OBSTRUCTIVE_APNEA, BreathingEventKind.HYPOPNEA), groups[2].events.map { it.kind })
        assertEquals(groups.size, groups.map { it.key }.toSet().size)
        assertEquals(emptyList(), groupByNight(Events.EMPTY, nights))
        assertNull(groups[0].night)
        // Outside every night, at the offset of the nearest.
        assertEquals(listOf(-420), groups[0].offsets)
        assertEquals(groups.map { it.events.size }, groups.map { it.offsets.size })
        assertEquals(3, groups.sumOf { it.events.size } - 1)
    }

    private fun mark(on: Boolean, text: String) = com.everysoft.paprelay.core.nights.UsageMark(on, at(text), null)

    private val finished = listOf(night(LocalDate.of(2026, 11, 13)), night(LocalDate.of(2026, 11, 14)))
    private val preview = com.everysoft.paprelay.core.nights.NightPreview.of(
        finished,
        listOf(mark(true, "2026-11-15T23:00:00-07:00"), mark(false, "2026-11-16T03:00:00-07:00"), mark(true, "2026-11-16T03:30:00-07:00")),
        at("2026-11-16T07:00:00-07:00").epochMs,
    )!!
    private val previewNightEvents = Events(
        null,
        listOf(
            BreathingEvent(BreathingEventKind.OBSTRUCTIVE_APNEA, at("2026-11-16T01:00:00-07:00"), 10_000, null),
            BreathingEvent(BreathingEventKind.HYPOPNEA, at("2026-11-16T04:00:00-07:00"), null, null),
            // The night before's: not in the preview.
            BreathingEvent(BreathingEventKind.HYPOPNEA, at("2026-11-15T02:00:00-07:00"), null, null),
        ),
        null,
    )

    @Test
    fun `the preview's picture is drawn from its sessions and its night's events, by the same model as a night's`() {
        val model = TimelineModel.of(preview, previewEvents(previewNightEvents, preview))
        assertEquals(preview.startMs, model.startMs)
        assertEquals(preview.endMs, model.endMs)
        assertEquals(-420, model.offsetMinutes)
        assertEquals(listOf(at("2026-11-15T23:00:00-07:00").epochMs, at("2026-11-16T03:30:00-07:00").epochMs), model.bars.map { it.fromMs })
        // The session still on reaches the last read, and is marked as running.
        assertEquals(at("2026-11-16T07:00:00-07:00").epochMs, model.bars.last().toMs)
        assertEquals(listOf(false, true), model.bars.map { it.running })
        assertTrue(model.running)
        // The night's two events: an apnoea tall, a hypopnoea short. The night before's is not there.
        assertEquals(listOf(true, false), model.marks.sortedBy { it.atMs }.map { it.tall })
        // A finished night's picture comes from the same model, with no session running.
        val listed = Night(LocalDate.of(2026, 11, 14), -420, 60, 1, emptyMap(), true, sessionList = listOf(com.everysoft.paprelay.core.nights.NightSession(at("2026-11-14T23:00:00-07:00"), 60)))
        val night = TimelineModel.of(listed, previewNightEvents.all)
        assertEquals(listOf(at("2026-11-15T00:00:00-07:00").epochMs), night.bars.map { it.toMs })
        assertTrue(!night.running)
        assertEquals(1, night.marks.size)
    }

    @Test
    fun `the preview is the last page, and gives way to the night once the machine has summed it up`() {
        val pages = NightPageItem.pages(finished, preview)
        assertEquals(listOf(LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 15)), pages.map { it.date })
        assertTrue(pages.last() is NightPageItem.Preview)
        val arrived = finished + night(LocalDate.of(2026, 11, 15))
        assertTrue(NightPageItem.pages(arrived, preview).none { it is NightPageItem.Preview })
        assertEquals(2, NightPageItem.pages(finished, null).size)
    }

    @Test
    fun `the preview's events are listed first, marked so far, and the night before keeps its own`() {
        val groups = groupByNight(previewNightEvents, finished, preview)
        assertTrue(groups.first().soFar)
        assertEquals(LocalDate.of(2026, 11, 15), groups.first().date)
        assertEquals(listOf(BreathingEventKind.HYPOPNEA, BreathingEventKind.OBSTRUCTIVE_APNEA), groups.first().events.map { it.kind })
        assertEquals(LocalDate.of(2026, 11, 14), groups[1].date)
        assertTrue(!groups[1].soFar)
        // Without a preview, the night's events wait, as they do in the file.
        assertTrue(groupByNight(previewNightEvents, finished).none { it.date == LocalDate.of(2026, 11, 15) })
    }

    @Test
    fun `a session still running is said to be so at the last read, corrected where it can be, else by the machine's clock with a star`() {
        val formatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        val lastRead = at("2026-11-16T07:00:00-07:00").epochMs
        val corrected = com.everysoft.paprelay.core.nights.NightPreview.of(
            finished, listOf(mark(true, "2026-11-16T03:30:00-07:00")), lastRead, knownUntilCorrectedMs = lastRead - 360_000,
        )!!
        assertEquals("06:54", lastReadTime(corrected, formatter))
        assertEquals("06:54", TimelineModel.of(corrected, emptyList()).runningAt(formatter))
        assertEquals("07:00*", lastReadTime(preview, formatter))
        assertEquals("07:00*", TimelineModel.of(preview, emptyList()).runningAt(formatter))
    }

    @Test
    fun `a session on whose length is not known is drawn as its start alone, and no time is said without a last read`() {
        val formatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        // Never read to its end: no last read.
        val unread = com.everysoft.paprelay.core.nights.NightPreview.of(finished, listOf(mark(true, "2026-11-16T03:30:00-07:00")), null)!!
        val model = TimelineModel.of(unread, emptyList())
        assertEquals(null, model.bars.single().toMs)
        assertTrue(model.bars.single().running)
        assertEquals("", model.runningAt(formatter))
        // The headline then says the plain time (here none), never "mask still on at " with no time.
        assertEquals(null, stillOnAt(unread, formatter))
        assertEquals("07:00*", stillOnAt(preview, formatter))
    }
}
