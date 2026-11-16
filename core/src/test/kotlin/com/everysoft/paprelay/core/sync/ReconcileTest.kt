// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.core.sync

import com.everysoft.paprelay.core.MachineTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReconcileTest {
    private val first = MachineTime(1_794_556_805_988)
    private val tag = "m-4f2a9c"
    private val offset = -361_600L
    private val now = 1_800_000_000_000L

    /** A session with a reading in each of the given minutes; the rate is the minute number plus twelve. */
    private fun session(vararg minutes: Int, rate: (Int) -> Int = { 1200 + it * 25 }) =
        SessionReadings(tag, first, minutes.map { RateReading(first + it * 60_000L, rate(it)) })

    private fun id(minute: Int) = "rr-$tag-${first.epochMs + minute * 60_000L}"

    @Test
    fun `a session not written before is written whole, with the offset measured now`() {
        val change = Reconcile.session(session(0, 1, 2), ledger = null, measuredClockOffsetMs = offset, nowMs = now)
        assertEquals(listOf(id(0), id(1), id(2)), change.writes.map { it.recordId })
        assertEquals(listOf(12.0, 12.25, 12.5), change.writes.map { it.breathsPerMinute })
        assertEquals(listOf(first, first + 60_000, first + 120_000), change.writes.map { it.minuteEnd })
        assertTrue(change.writes.all { it.clockOffsetMs == offset && it.version == now })
        assertTrue(change.deleteRecordIds.isEmpty())
        val entry = change.ledgerAfter!!
        assertEquals(listOf(0, 1, 2), entry.minutes)
        assertEquals(offset, entry.clockOffsetMs)
        assertEquals(now, entry.version)
        assertEquals("$tag/${first.epochMs}", entry.key)
    }

    @Test
    fun `the same download again changes nothing`() {
        val written = Reconcile.session(session(0, 1, 2), null, offset, now).ledgerAfter!!
        // Later, with a clock offset that has drifted a little, and a later clock.
        val again = Reconcile.session(session(0, 1, 2), written, offset + 900, now + 86_400_000)
        assertTrue(again.nothingToDo)
        assertSame(written, again.ledgerAfter)
    }

    @Test
    fun `a session that has grown is rewritten whole, at the offset it was first written with`() {
        val written = Reconcile.session(session(0, 1), null, offset, now).ledgerAfter!!
        val change = Reconcile.session(session(0, 1, 2, 3), written, offset + 900, now + 3_600_000)
        assertEquals(listOf(id(0), id(1), id(2), id(3)), change.writes.map { it.recordId })
        // The first two minutes do not move by 0.9 s because the clock was measured again.
        assertTrue(change.writes.all { it.clockOffsetMs == offset })
        assertTrue(change.writes.all { it.version == now + 3_600_000 })
        assertTrue(change.deleteRecordIds.isEmpty())
        val after = change.ledgerAfter!!
        assertEquals(listOf(0, 1, 2, 3), after.minutes)
        assertEquals(offset, after.clockOffsetMs)
    }

    @Test
    fun `a rewrite always carries a higher version, even if the phone's clock has gone backwards`() {
        val written = Reconcile.session(session(0, 1), null, offset, now).ledgerAfter!!
        val change = Reconcile.session(session(0, 1, 2), written, offset, nowMs = now - 5_000_000)
        assertEquals(now + 1, change.ledgerAfter!!.version)
        assertTrue(change.writes.all { it.version == now + 1 })
        val third = Reconcile.session(session(0, 1, 2, 3), change.ledgerAfter, offset, nowMs = 0)
        assertEquals(now + 2, third.ledgerAfter!!.version)
    }

    @Test
    fun `a value that changed is rewritten though the minutes are the same`() {
        val written = Reconcile.session(session(0, 1, 2), null, offset, now).ledgerAfter!!
        val change = Reconcile.session(session(0, 1, 2) { if (it == 1) 1500 else 1200 + it * 25 }, written, offset, now + 1)
        assertEquals(3, change.writes.size)
        assertEquals(15.0, change.writes[1].breathsPerMinute)
        assertNotEquals(written.fingerprint, change.ledgerAfter!!.fingerprint)
    }

    @Test
    fun `minutes no longer wanted are removed, and only those`() {
        val written = Reconcile.session(session(0, 1, 2, 3, 4), null, offset, now).ledgerAfter!!
        // The leak limit was lowered, say, and minutes 1 and 3 dropped out.
        val change = Reconcile.session(session(0, 2, 4), written, offset, now + 1)
        assertEquals(listOf(id(1), id(3)), change.deleteRecordIds)
        assertEquals(listOf(id(0), id(2), id(4)), change.writes.map { it.recordId })
        assertEquals(listOf(0, 2, 4), change.ledgerAfter!!.minutes)
        // Nothing left at all: everything goes, and so does the ledger entry.
        val emptied = Reconcile.session(session(), written, offset, now + 1)
        assertEquals((0..4).map(::id), emptied.deleteRecordIds)
        assertTrue(emptied.writes.isEmpty())
        assertNull(emptied.ledgerAfter)
        // A session with nothing wanted and nothing held needs nothing.
        val nothing = Reconcile.session(session(), null, offset, now)
        assertTrue(nothing.nothingToDo)
        assertNull(nothing.ledgerAfter)
    }

    @Test
    fun `a change worked out twice is the same change, so an interrupted one can simply be repeated`() {
        val written = Reconcile.session(session(0, 1, 2, 3), null, offset, now).ledgerAfter!!
        val once = Reconcile.session(session(0, 2, 3, 4), written, offset, now + 10)
        val twice = Reconcile.session(session(0, 2, 3, 4), written, offset, now + 10)
        assertEquals(once.writes.map { "${it.recordId}@${it.version}=${it.breathsPerMinute}" }, twice.writes.map { "${it.recordId}@${it.version}=${it.breathsPerMinute}" })
        assertEquals(once.deleteRecordIds, twice.deleteRecordIds)
        assertEquals(once.ledgerAfter!!.fingerprint, twice.ledgerAfter!!.fingerprint)
    }

    @Test
    fun `a session missing from a download is left alone, and one is removed only on request`() {
        val written = Reconcile.session(session(0, 1, 2), null, offset, now).ledgerAfter!!
        val absent = Reconcile.absent(written)
        assertTrue(absent.nothingToDo)
        assertSame(written, absent.ledgerAfter)
        val removed = Reconcile.remove(written)
        assertEquals(listOf(id(0), id(1), id(2)), removed.deleteRecordIds)
        assertTrue(removed.writes.isEmpty())
        assertNull(removed.ledgerAfter)
    }

    @Test
    fun `record ids come from the machine's own time stamps and nothing else`() {
        assertEquals("rr-m-4f2a9c-1794556805988", SessionReadings.recordId(tag, first, 0))
        assertEquals("rr-m-4f2a9c-1794564125988", SessionReadings.recordId(tag, first, 122))
        // Another machine, or another session, can never collide.
        assertNotEquals(SessionReadings.recordId("m-000001", first, 0), SessionReadings.recordId(tag, first, 0))
        assertFailsWith<IllegalArgumentException> { SessionReadings.recordId(tag, first, -1) }
    }

    @Test
    fun `the fingerprint covers which minutes and every value, and nothing about when it was made`() {
        val a = session(0, 1, 2).fingerprint()
        assertEquals(a, session(0, 1, 2).fingerprint())
        assertEquals(64, a.length)
        assertNotEquals(a, session(0, 1, 3).fingerprint())
        assertNotEquals(a, session(0, 1).fingerprint())
        assertNotEquals(a, session(0, 1, 2) { 1300 }.fingerprint())
        // Minute 1 at 12.25 then minute 2 at 12.5 is not minute 12 at ... : no two sessions run together.
        assertNotEquals(session(1, 2).fingerprint(), session(12).fingerprint())
        // The same readings in a session that started at another time have the same content.
        val other = MachineTime(first.epochMs + 7_200_000)
        assertEquals(a, SessionReadings(tag, other, listOf(0, 1, 2).map { RateReading(other + it * 60_000L, 1200 + it * 25) }).fingerprint())
    }

    @Test
    fun `what cannot be a session or a reading is refused`() {
        assertFailsWith<IllegalArgumentException> { RateReading(first, 0) }
        assertFailsWith<IllegalArgumentException> { RateReading(first, -100) }
        assertFailsWith<IllegalArgumentException> { RateReading(first, 9_001) }
        RateReading(first, 9_000)
        RateReading(first, 1)
        // Off the minute grid, before the session, out of order, the same minute twice.
        assertFailsWith<IllegalArgumentException> { SessionReadings(tag, first, listOf(RateReading(first + 30_000, 1200))) }
        assertFailsWith<IllegalArgumentException> { SessionReadings(tag, first, listOf(RateReading(first - 60_000, 1200))) }
        assertFailsWith<IllegalArgumentException> { SessionReadings(tag, first, listOf(RateReading(first + 60_000, 1200), RateReading(first, 1200))) }
        assertFailsWith<IllegalArgumentException> { SessionReadings(tag, first, listOf(RateReading(first, 1200), RateReading(first, 1300))) }
        // A tag that could carry something identifying, or break an id apart.
        for (bad in listOf("", "abc", "has space", "slash/inside", "x".repeat(33), "serial:23211234567")) {
            assertFailsWith<IllegalArgumentException>(bad) { SessionReadings(bad, first, emptyList()) }
        }
        // A ledger entry for one session cannot be applied to another.
        val written = Reconcile.session(session(0), null, offset, now).ledgerAfter!!
        val elsewhere = SessionReadings(tag, first + 60_000, emptyList())
        assertFailsWith<IllegalArgumentException> { Reconcile.session(elsewhere, written, offset, now) }
        assertFailsWith<IllegalArgumentException> { LedgerEntry(tag, first, listOf(2, 1), "f", offset, 1) }
        assertFailsWith<IllegalArgumentException> { LedgerEntry(tag, first, listOf(1, 1), "f", offset, 1) }
        assertFailsWith<IllegalArgumentException> { LedgerEntry(tag, first, listOf(0), "f", offset, 0) }
    }

    @Test
    fun `the lists handed in cannot be changed afterwards`() {
        val readings = mutableListOf(RateReading(first, 1200))
        val session = SessionReadings(tag, first, readings)
        readings += RateReading(first + 60_000, 1300)
        assertEquals(1, session.readings.size)
        val minutes = mutableListOf(0, 1)
        val entry = LedgerEntry(tag, first, minutes, "f", offset, 1)
        minutes += 2
        assertEquals(listOf(0, 1), entry.minutes)
    }
}
