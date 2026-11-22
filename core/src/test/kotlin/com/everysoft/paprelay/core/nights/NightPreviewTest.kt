// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.core.nights

import com.everysoft.paprelay.core.MachineTime
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NightPreviewTest {
    private fun at(text: String) = OffsetDateTime.parse(text).toInstant().toEpochMilli()

    private fun on(text: String) = UsageMark(true, MachineTime(at(text)), null)

    private fun off(text: String) = UsageMark(false, MachineTime(at(text)), null)

    /** The newest finished night: the 14th, from midday on the 14th to midday on the 15th. */
    private val nights = listOf(Night(LocalDate.of(2026, 11, 13), -420, 300, 1, emptyMap(), true), Night(LocalDate.of(2026, 11, 14), -420, 300, 1, emptyMap(), true))
    private val morning = at("2026-11-16T07:30:00-07:00")

    @Test
    fun `the night after the newest finished one, from midday to midday, with its sessions summed to the whole minute`() {
        val marks = listOf(
            // The 14th's night: not this one's.
            on("2026-11-14T23:00:00-07:00"), off("2026-11-15T06:00:00-07:00"),
            on("2026-11-15T22:40:00-07:00"), off("2026-11-16T03:10:30-07:00"),
            on("2026-11-16T03:20:00-07:00"), off("2026-11-16T06:45:00-07:00"),
        )
        val preview = assertNotNull(NightPreview.of(nights, marks, morning))
        assertEquals(LocalDate.of(2026, 11, 15), preview.date)
        assertEquals(at("2026-11-15T12:00:00-07:00"), preview.startMs)
        assertEquals(at("2026-11-16T12:00:00-07:00"), preview.endMs)
        assertEquals(-420, preview.zoneOffsetMinutes)
        assertEquals(2, preview.sessions.size)
        // 4 h 30 min 30 s and 3 h 25 min: 7 h 55 min, the half minute not rounded up.
        assertEquals(475, preview.usageMinutes)
        assertTrue(!preview.running)
        assertEquals("NightPreview", "$preview")
    }

    @Test
    fun `midday by the machine's clock is the boundary, on both sides`() {
        // A mask-on at midday exactly is this night's; one a millisecond before is the night before's.
        val atNoon = NightPreview.of(nights, listOf(on("2026-11-15T12:00:00-07:00"), off("2026-11-15T13:00:00-07:00")), morning)
        assertEquals(60, atNoon?.usageMinutes)
        assertNull(NightPreview.of(nights, listOf(on("2026-11-15T11:59:59.999-07:00")), morning))
        // A mask-off after the night's start whose mask-on came before it belongs to the night before.
        val carried = NightPreview.of(nights, listOf(on("2026-11-15T11:30:00-07:00"), off("2026-11-15T12:30:00-07:00"), on("2026-11-15T23:00:00-07:00"), off("2026-11-16T01:00:00-07:00")), morning)
        assertEquals(120, carried?.usageMinutes)
        // Without that mask-on before midday, the mask-off has no pair: no sum, the known session still drawn.
        val orphan = assertNotNull(NightPreview.of(nights, listOf(off("2026-11-15T12:30:00-07:00"), on("2026-11-15T23:00:00-07:00"), off("2026-11-16T01:00:00-07:00")), morning))
        assertNull(orphan.usageMinutes)
        assertEquals(1, orphan.sessions.size)
        // A mask-off before midday closes nothing after it.
        assertNull(NightPreview.of(nights, listOf(off("2026-11-15T11:30:00-07:00"), off("2026-11-15T12:30:00-07:00"), on("2026-11-15T23:00:00-07:00"), off("2026-11-16T01:00:00-07:00")), morning)?.usageMinutes)
        // The next midday is the next night's.
        assertNull(NightPreview.of(nights, listOf(on("2026-11-16T12:00:00-07:00")), morning))
    }

    @Test
    fun `a session still running is counted up to when the machine was last read, and said to be running`() {
        val preview = assertNotNull(NightPreview.of(nights, listOf(on("2026-11-15T23:00:00-07:00"), off("2026-11-16T02:00:00-07:00"), on("2026-11-16T05:00:00-07:00")), morning))
        assertTrue(preview.running)
        assertTrue(preview.sessions.last().running)
        assertEquals(at("2026-11-16T07:30:00-07:00"), preview.sessions.last().end?.epochMs)
        assertEquals(180 + 150, preview.usageMinutes)
        // Never past the night's end, and never before the mask went on.
        val late = assertNotNull(NightPreview.of(nights, listOf(on("2026-11-16T11:00:00-07:00")), at("2026-11-16T11:30:00-07:00")))
        assertEquals(30, late.usageMinutes)
        // Never read to its end: a start, and no length or total, never a length of zero.
        val unknown = assertNotNull(NightPreview.of(nights, listOf(on("2026-11-16T05:00:00-07:00")), null))
        assertNull(unknown.usageMinutes)
        assertNull(unknown.sessions.single().end)
        assertNull(unknown.sessions.single().minutes)
        assertTrue(unknown.running)
        // Last read whole before this mask-on: a start, and no length, never 0 h 0 min.
        val stale = assertNotNull(NightPreview.of(nights, listOf(on("2026-11-16T05:00:00-07:00")), at("2026-11-16T04:00:00-07:00")))
        assertNull(stale.usageMinutes)
        assertNull(stale.sessions.single().end)
        assertNull(stale.sessions.single().minutes)
    }

    @Test
    fun `marks that do not pair up give no sum, never a guessed one`() {
        val twoOns = assertNotNull(NightPreview.of(nights, listOf(on("2026-11-15T23:00:00-07:00"), on("2026-11-16T01:00:00-07:00"), off("2026-11-16T02:00:00-07:00")), morning))
        assertNull(twoOns.usageMinutes)
        // The session whose both marks are known is still drawn; the one whose end is not, is not.
        assertEquals(listOf(at("2026-11-16T01:00:00-07:00")), twoOns.sessions.map { it.start.epochMs })
        val twoOffs = assertNotNull(NightPreview.of(nights, listOf(on("2026-11-15T23:00:00-07:00"), off("2026-11-16T01:00:00-07:00"), off("2026-11-16T02:00:00-07:00")), morning))
        assertNull(twoOffs.usageMinutes)
    }

    @Test
    fun `when the machine's own night arrives, the preview moves on to the next, and is gone without a mask-on there`() {
        val marks = listOf(on("2026-11-15T23:00:00-07:00"), off("2026-11-16T06:00:00-07:00"))
        assertEquals(LocalDate.of(2026, 11, 15), NightPreview.of(nights, marks, morning)?.date)
        val arrived = nights + Night(LocalDate.of(2026, 11, 15), -420, 420, 1, emptyMap(), true)
        assertNull(NightPreview.of(arrived, marks, at("2026-11-16T13:00:00-07:00")))
        // A night still running by the machine's clock is not one to start from.
        assertEquals(LocalDate.of(2026, 11, 15), NightPreview.of(nights + Night(LocalDate.of(2026, 11, 15), -420, 0, 0, emptyMap(), false), marks, morning)?.date)
        // No finished night at all: nothing to start from.
        assertNull(NightPreview.of(emptyList(), marks, morning))
    }

    @Test
    fun `after days without a summary, the night the machine was last read in is the one previewed`() {
        val marks = listOf(on("2026-11-17T23:00:00-07:00"), off("2026-11-18T06:00:00-07:00"))
        val preview = assertNotNull(NightPreview.of(nights, marks, at("2026-11-18T08:00:00-07:00")))
        assertEquals(LocalDate.of(2026, 11, 17), preview.date)
        assertEquals(420, preview.usageMinutes)
    }

    @Test
    fun `a record that could not be read near the night leaves it without a total, and a running session without time`() {
        val marks = listOf(on("2026-11-15T23:00:00-07:00"), off("2026-11-16T02:00:00-07:00"), on("2026-11-16T05:00:00-07:00"))
        // Between two marks of the night.
        val inside = assertNotNull(NightPreview.of(nights, marks, morning, gaps = listOf(UsageGap(at("2026-11-15T23:00:00-07:00"), at("2026-11-16T02:00:00-07:00")))))
        assertNull(inside.usageMinutes)
        assertEquals(2, inside.sessions.size)
        // The open session has a start and no length.
        assertNull(inside.sessions.last().end)
        assertTrue(inside.sessions.last().running)
        // Before the first mark read, and so possibly in this night: the same.
        assertNull(NightPreview.of(nights, marks, morning, gaps = listOf(UsageGap(null, at("2026-11-15T23:00:00-07:00"))))?.usageMinutes)
        // Wholly in the night before: this night's total stands.
        assertEquals(180 + 150, NightPreview.of(nights, marks, morning, gaps = listOf(UsageGap(at("2026-11-14T20:00:00-07:00"), at("2026-11-15T06:00:00-07:00"))))?.usageMinutes)
        // The last read, as given, travels with the preview.
        assertEquals(morning, NightPreview.of(nights, marks, morning, knownUntilCorrectedMs = morning - 360_000)?.lastReadMs)
        assertEquals(morning - 360_000, NightPreview.of(nights, marks, morning, knownUntilCorrectedMs = morning - 360_000)?.lastReadCorrectedMs)
    }

    @Test
    fun `a gap that ends at the night's midday reaches it, and one a moment before does not`() {
        val start = at("2026-11-15T12:00:00-07:00")
        val end = at("2026-11-16T12:00:00-07:00")
        // The first record read after the failed one is at midday: the failed one may be at midday too.
        assertTrue(UsageGap(null, start).reaches(start, end))
        assertTrue(!UsageGap(null, start - 1).reaches(start, end))
        // At the other end: a gap from the last record of the night onwards reaches it; one from midday on does not.
        assertTrue(UsageGap(end - 1, null).reaches(start, end))
        assertTrue(!UsageGap(end, null).reaches(start, end))
        val marks = listOf(on("2026-11-15T23:00:00-07:00"), off("2026-11-16T01:00:00-07:00"))
        assertNull(NightPreview.of(nights, marks, morning, gaps = listOf(UsageGap(null, start)))?.usageMinutes)
        assertEquals(120, NightPreview.of(nights, marks, morning, gaps = listOf(UsageGap(null, start - 1)))?.usageMinutes)
    }
}
