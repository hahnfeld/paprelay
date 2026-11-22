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
}
