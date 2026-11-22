// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.sync

import com.everysoft.paprelay.as11.plan.NightPlan
import com.everysoft.paprelay.core.MachineTime
import com.everysoft.paprelay.core.nights.BreathingEvent
import com.everysoft.paprelay.core.nights.BreathingEventKind
import com.everysoft.paprelay.core.nights.Night
import com.everysoft.paprelay.core.nights.NightFigure
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExportTablesTest {
    private val night = Night(LocalDate.of(2026, 11, 14), -420, 300, 1, mapOf(NightFigure.AHI to 230L, NightFigure.HUMIDIFIER_POWER_MEDIAN to 3300L), true)
    private val running = Night(LocalDate.of(2026, 11, 15), -420, 0, 0, emptyMap(), false)
    private val event = BreathingEvent(BreathingEventKind.HYPOPNEA, MachineTime(OffsetDateTime.parse("2026-11-15T01:00:00-07:00").toInstant().toEpochMilli()), null, null)

    @Test
    fun `the folder gets two files under fixed names, nights and events, with the columns the plan can fill`() {
        val tables = ExportTables.of(listOf(night, running), listOf(event))!!
        assertEquals(listOf("paprelay_nights.csv", "paprelay_events.csv"), tables.keys.toList())
        val nights = tables.getValue("paprelay_nights.csv").lines()
        assertEquals(4 + NightPlan.FIGURES.size + 3, nights[0].split(',').size)
        assertTrue(nights[0].contains("humidifier_power_median_percent"))
        // Named and not yet confirmed: no column.
        assertTrue(!nights[0].contains("target_ventilation"))
        assertTrue(nights[1].startsWith("2026-11-14,300,5,1,2.3,"))
        assertEquals("2026-11-14,hypopnea,,2026-11-15T01:00:00-07:00,start_or_end,", tables.getValue("paprelay_events.csv").lines()[1])
        // Built twice from the same nights and events: the same, to the letter.
        assertEquals(tables, ExportTables.of(listOf(running, night), listOf(event)))
    }

    @Test
    fun `with no finished night nothing is written, so that an empty table never replaces a full one`() {
        assertNull(ExportTables.of(emptyList(), listOf(event)))
        assertNull(ExportTables.of(listOf(running), listOf(event)))
        // With nights and no events yet, the events file is its header.
        assertEquals("night_of,event,time_corrected,time_machine_clock,time_is,duration_seconds\r\n", ExportTables.of(listOf(night), emptyList())!!.getValue("paprelay_events.csv"))
    }
}

class FolderWritesTest {
    private val listed = FolderWrites.Listing(mapOf("paprelay_nights.csv" to "doc-1", "other.csv" to "doc-2"), loading = false)

    @Test
    fun `a file there is written, one surely not there is made, and nothing is made from a listing that failed or is not whole`() {
        assertEquals("doc-1", (FolderWrites.target(listed, "paprelay_nights.csv") as FolderWrites.Target.Existing).documentId)
        assertEquals(FolderWrites.Target.Create, FolderWrites.target(listed, "paprelay_events.csv"))
        // The folder could not be listed: later, never a second file beside the first.
        assertEquals(FolderWrites.Target.Later, FolderWrites.target(null, "paprelay_events.csv"))
        // Still loading: what is there is written, and what is not yet seen is waited for, not made.
        val loading = FolderWrites.Listing(mapOf("paprelay_nights.csv" to "doc-1"), loading = true)
        assertEquals("doc-1", (FolderWrites.target(loading, "paprelay_nights.csv") as FolderWrites.Target.Existing).documentId)
        assertEquals(FolderWrites.Target.Wait, FolderWrites.target(loading, "paprelay_events.csv"))
        // Names are matched exactly.
        assertEquals(FolderWrites.Target.Create, FolderWrites.target(listed, "PAPRELAY_NIGHTS.csv"))
    }

    @Test
    fun `the truncating modes come first, and only a plain write is cut to length afterwards`() {
        assertEquals(listOf("rwt", "wt", "w"), FolderWrites.MODES)
        assertEquals(listOf(false, false, true), FolderWrites.MODES.map { FolderWrites.cutAfter(it) })
    }

    @Test
    fun `a folder still loading is asked again for about two seconds, and is then no failure, only not yet`() {
        // A few tries, about two seconds in all.
        assertEquals(2_000L, FolderWrites.LOADING_TRIES * FolderWrites.LOADING_PAUSE_MS)
        val written = FolderWrites.Outcome.WRITTEN
        val notYet = FolderWrites.Outcome.NOT_YET
        val failed = FolderWrites.Outcome.FAILED
        assertEquals(written, FolderWrites.outcome(listOf(written, written)))
        assertEquals(notYet, FolderWrites.outcome(listOf(written, notYet)))
        // A failure of either file is said, whatever the other did.
        assertEquals(failed, FolderWrites.outcome(listOf(notYet, failed)))
        assertEquals(failed, FolderWrites.outcome(listOf(failed, written)))
    }
}
