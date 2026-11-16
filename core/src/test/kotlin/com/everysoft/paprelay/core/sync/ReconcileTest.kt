// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.core.sync

import com.everysoft.paprelay.core.ClockOffset
import com.everysoft.paprelay.core.MachineTime
import java.time.Instant
import java.time.ZoneId
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReconcileTest {
    private val first = MachineTime(1_794_556_805_988)
    private val tag = "m-4f2a9c"
    private val offset = ClockOffset.restored(-361_600L)
    private val drifted = ClockOffset.restored(-360_700L)
    private val home = ZoneId.of("America/Phoenix")
    private val away = ZoneId.of("Europe/London")
    private val now = 1_800_000_000_000L

    /** A session with a reading in each of the given minutes. */
    private fun session(vararg minutes: Int, rate: (Int) -> Int = { 1200 + it * 25 }) =
        SessionReadings(tag, first, minutes.map { RateReading(first + it * 60_000L, rate(it)) })

    private fun id(minute: Int) = "rr-$tag-${first.epochMs + minute * 60_000L}"

    /** Reconciles and confirms, as a run that is not interrupted does. */
    private fun done(desired: SessionReadings, ledger: LedgerEntry?, at: Long = now, measured: ClockOffset = offset, zone: ZoneId = home): LedgerEntry? =
        Reconcile.session(desired, ledger, measured, zone, at)!!.confirmed

    @Test
    fun `a session not written before is written whole, with the offset and zone of now`() {
        val change = Reconcile.session(session(0, 1, 2), null, offset, home, now)!!
        assertEquals(listOf(id(0), id(1), id(2)), change.writes.map { it.recordId })
        assertEquals(listOf(12.0, 12.25, 12.5), change.writes.map { it.breathsPerMinute })
        assertEquals(listOf(first, first + 60_000, first + 120_000), change.writes.map { it.minuteEnd })
        // The machine's clock was fast, so the real moment is earlier than its stamp.
        assertEquals(Instant.ofEpochMilli(first.epochMs - 361_600), change.writes[0].time)
        assertTrue(change.writes.all { it.clockOffset == offset && it.zone == home && it.version == now })
        assertTrue(change.deleteRecordIds.isEmpty())
        // Before Health Connect is touched, the ledger already says what may be about to exist.
        assertEquals(emptyList(), change.intent.minutes)
        assertEquals(listOf(0, 1, 2), change.intent.pendingMinutes)
        assertEquals(now, change.intent.version)
        val entry = change.confirmed!!
        assertEquals(listOf(0, 1, 2), entry.minutes)
        assertNull(entry.pendingMinutes)
        assertEquals(offset, entry.clockOffset)
        assertEquals(home, entry.zone)
        assertEquals(now, entry.version)
        assertEquals("$tag/${first.epochMs}", entry.key)
    }

    @Test
    fun `the same download again changes nothing, unless a rewrite is asked for`() {
        val written = done(session(0, 1, 2), null)
        // Later, with a clock offset that has drifted a little, in another zone, at a later time.
        assertNull(Reconcile.session(session(0, 1, 2), written, drifted, away, now + 86_400_000))
        val forced = Reconcile.session(session(0, 1, 2), written, drifted, away, now + 86_400_000, force = true)!!
        assertEquals(3, forced.writes.size)
        assertTrue(forced.deleteRecordIds.isEmpty())
        assertTrue(forced.writes.all { it.version == now + 86_400_000 && it.clockOffset == offset && it.zone == home })
        // A session with nothing wanted and nothing known needs nothing, forced or not.
        assertNull(Reconcile.session(session(), null, offset, home, now))
        assertNull(Reconcile.session(session(), null, offset, home, now, force = true))
    }

    @Test
    fun `a session that has grown is rewritten whole, at the offset and zone it was first written with`() {
        val written = done(session(0, 1), null)
        val change = Reconcile.session(session(0, 1, 2, 3), written, drifted, away, now + 3_600_000)!!
        assertEquals(listOf(id(0), id(1), id(2), id(3)), change.writes.map { it.recordId })
        // The first two minutes do not move by 0.9 s, or change their label, because the phone has moved on.
        assertTrue(change.writes.all { it.clockOffset == offset && it.zone == home && it.version == now + 3_600_000 })
        assertTrue(change.deleteRecordIds.isEmpty())
        assertEquals(listOf(0, 1), change.intent.minutes)
        assertEquals(listOf(0, 1, 2, 3), change.intent.pendingMinutes)
        val after = change.confirmed!!
        assertEquals(listOf(0, 1, 2, 3), after.minutes)
        assertEquals(offset, after.clockOffset)
    }

    @Test
    fun `a rewrite always carries a higher version, even if the phone's clock has gone backwards`() {
        val written = done(session(0, 1), null)
        val change = Reconcile.session(session(0, 1, 2), written, offset, home, nowMs = now - 5_000_000)!!
        assertEquals(now + 1, change.intent.version)
        assertTrue(change.writes.all { it.version == now + 1 })
        // Even when that change was begun and never confirmed, the next goes out above it.
        val retry = Reconcile.session(session(0, 1, 2), change.intent, offset, home, nowMs = 0)!!
        assertEquals(now + 2, retry.intent.version)
        assertTrue(retry.writes.all { it.version == now + 2 })
    }

    @Test
    fun `a value that changed is rewritten though the minutes are the same`() {
        val written = done(session(0, 1, 2), null)!!
        val change = Reconcile.session(session(0, 1, 2) { if (it == 1) 1500 else 1200 + it * 25 }, written, offset, home, now + 1)!!
        assertEquals(3, change.writes.size)
        assertEquals(15.0, change.writes[1].breathsPerMinute)
        assertNotEquals(written.fingerprint, change.confirmed!!.fingerprint)
    }

    @Test
    fun `minutes no longer wanted are removed, and only those`() {
        val written = done(session(0, 1, 2, 3, 4), null)
        val change = Reconcile.session(session(0, 2, 4), written, offset, home, now + 1)!!
        assertEquals(listOf(id(1), id(3)), change.deleteRecordIds)
        assertEquals(listOf(id(0), id(2), id(4)), change.writes.map { it.recordId })
        assertEquals(listOf(0, 2, 4), change.confirmed!!.minutes)
        // Nothing left at all: everything goes, and so does the ledger entry, once confirmed.
        val emptied = Reconcile.session(session(), written, offset, home, now + 1)!!
        assertEquals((0..4).map(::id), emptied.deleteRecordIds)
        assertTrue(emptied.writes.isEmpty())
        assertNull(emptied.confirmed)
        assertEquals(listOf(0, 1, 2, 3, 4), emptied.intent.pendingMinutes)
    }

    @Test
    fun `a change begun and not confirmed is worked out again from everything that may be there`() {
        val written = done(session(0, 1, 2, 3, 4), null)
        // A rule is tightened and minutes 1 and 3 are to go. The deletions happen; the app dies.
        val interrupted = Reconcile.session(session(0, 2, 4), written, offset, home, now + 1)!!.intent
        // Before the next run the rule is put back. The ledger must not say "already in step".
        val restored = Reconcile.session(session(0, 1, 2, 3, 4), interrupted, offset, home, now + 2)!!
        assertEquals((0..4).map(::id), restored.writes.map { it.recordId })
        assertTrue(restored.deleteRecordIds.isEmpty())

        // The other way round: a first write is begun, lands, and is never confirmed.
        val begun = Reconcile.session(session(0, 1, 2, 3, 4), null, offset, home, now)!!.intent
        // The rule is then tightened. What the unconfirmed write left behind is removed.
        val narrowed = Reconcile.session(session(0, 2, 4), begun, offset, home, now + 1)!!
        assertEquals(listOf(id(1), id(3)), narrowed.deleteRecordIds)
        // And even if the very same thing is wanted as was begun, it is not taken on trust.
        val same = Reconcile.session(session(0, 1, 2, 3, 4), begun, offset, home, now + 1)!!
        assertEquals(5, same.writes.size)
        assertTrue(same.writes.all { it.version > begun.version })
    }

    /** Health Connect as an app sees it: records by client id, replaced only by a strictly higher version. */
    private class Model {
        class Held(val version: Long, val rate: Double, val time: Instant, val zone: ZoneId)

        val records = HashMap<String, Held>()

        fun delete(id: String) {
            records.remove(id)
        }

        fun insert(write: RecordWrite) {
            val held = records[write.recordId]
            if (held == null || write.version > held.version) records[write.recordId] = Held(write.version, write.breathsPerMinute, write.time, write.zone)
        }
    }

    @Test
    fun `whatever is interrupted, and whatever changes in between, Health Connect ends up holding exactly what is wanted`() {
        val random = Random(20261116)
        repeat(400) { run ->
            val model = Model()
            var ledger: LedgerEntry? = null
            var clock = now
            repeat(40) { step ->
                // What is wanted changes freely: minutes come and go, values change.
                val wantedMinutes = (0 until 12).filter { random.nextInt(4) != 0 }.let { if (random.nextInt(10) == 0) emptyList() else it }
                val desired = SessionReadings(tag, first, wantedMinutes.map { RateReading(first + it * 60_000L, 1000 + random.nextInt(4) * 25 + it) })
                // The phone's clock mostly advances, and sometimes jumps back.
                clock += if (random.nextInt(8) == 0) -random.nextInt(100_000).toLong() else random.nextInt(100_000).toLong()
                val change = Reconcile.session(desired, ledger, if (random.nextBoolean()) offset else drifted, if (random.nextBoolean()) home else away, clock, force = random.nextInt(15) == 0)
                val context = "run $run step $step"
                if (change == null) {
                    assertEquals(desired.minutes.map(::id).toSet(), model.records.keys, context)
                } else {
                    // The run is cut off at a random point, or completes.
                    val total = 1 + change.deleteRecordIds.size + change.writes.size + 1
                    val cutAfter = if (random.nextInt(3) == 0) random.nextInt(total) else total
                    var done = 0
                    fun reached() = done++ < cutAfter
                    if (reached()) ledger = change.intent
                    for (recordId in change.deleteRecordIds) if (reached()) model.delete(recordId)
                    for (write in change.writes) if (reached()) model.insert(write)
                    val completed = reached()
                    if (completed) ledger = change.confirmed
                    if (completed) {
                        assertEquals(desired.minutes.map(::id).toSet(), model.records.keys, context)
                        for ((index, reading) in desired.readings.withIndex()) {
                            assertEquals(reading.breathsPerMinute, model.records.getValue(id(desired.minutes[index])).rate, context)
                        }
                        // One offset and one zone for the whole session, whichever was measured when.
                        assertTrue(model.records.values.map { it.zone }.distinct().size <= 1, context)
                    }
                }
                // At every moment, interrupted or not: nothing in Health Connect is beyond what the ledger covers.
                val covered = ledger?.possiblyHeld.orEmpty().map(::id).toSet()
                assertTrue(covered.containsAll(model.records.keys), "$context: ${model.records.keys - covered} not covered by the ledger")
                assertTrue(model.records.values.all { it.version <= (ledger?.version ?: 0) }, context)
            }
            // Finally, with no interruption: "delete my data" leaves nothing behind.
            ledger?.let { entry ->
                val removal = Reconcile.remove(entry, clock)
                removal.deleteRecordIds.forEach(model::delete)
                assertNull(removal.confirmed)
            }
            assertTrue(model.records.isEmpty(), "run $run left ${model.records.keys}")
        }
    }

    @Test
    fun `removal covers a change that was never confirmed`() {
        val begun = Reconcile.session(session(0, 1, 2), done(session(0, 5), null), offset, home, now + 1)!!.intent
        val removal = Reconcile.remove(begun, now + 2)
        assertEquals(listOf(id(0), id(1), id(2), id(5)), removal.deleteRecordIds)
        assertTrue(removal.writes.isEmpty())
        assertNull(removal.confirmed)
        assertEquals(listOf(0, 1, 2, 5), removal.intent.pendingMinutes)
    }

    @Test
    fun `record ids come from the machine's own time stamps and nothing else`() {
        assertEquals("rr-m-4f2a9c-1794556805988", SessionReadings.recordId(tag, first, 0))
        assertEquals("rr-m-4f2a9c-1794564125988", SessionReadings.recordId(tag, first, 122))
        assertNotEquals(SessionReadings.recordId("m-000001", first, 0), SessionReadings.recordId(tag, first, 0))
        assertFailsWith<IllegalArgumentException> { SessionReadings.recordId(tag, first, -1) }
    }

    @Test
    fun `the fingerprint covers which minutes, every value and the rules in force, and nothing about when it was made`() {
        val a = session(0, 1, 2).fingerprint()
        assertEquals(a, session(0, 1, 2).fingerprint())
        assertEquals(64, a.length)
        assertNotEquals(a, session(0, 1, 3).fingerprint())
        assertNotEquals(a, session(0, 1).fingerprint())
        assertNotEquals(a, session(0, 1, 2) { 1300 }.fingerprint())
        assertNotEquals(session(1, 2).fingerprint(), session(12).fingerprint())
        val other = MachineTime(first.epochMs + 7_200_000)
        assertEquals(a, SessionReadings(tag, other, listOf(0, 1, 2).map { RateReading(other + it * 60_000L, 1200 + it * 25) }).fingerprint())
        // Pinned, so that a change to the rules version (or to the fingerprint itself) is a deliberate act:
        // it makes every session already written count as out of step.
        assertEquals(1, SessionReadings.RULES_VERSION)
        assertEquals("484fe356", session(0, 1, 2).fingerprint().take(8))
    }

    @Test
    fun `what cannot be a session, a reading or a ledger entry is refused`() {
        assertFailsWith<IllegalArgumentException> { RateReading(first, 0) }
        assertFailsWith<IllegalArgumentException> { RateReading(first, -100) }
        assertFailsWith<IllegalArgumentException> { RateReading(first, 9_001) }
        RateReading(first, 9_000)
        RateReading(first, 1)
        assertFailsWith<IllegalArgumentException> { SessionReadings(tag, first, listOf(RateReading(first + 30_000, 1200))) }
        assertFailsWith<IllegalArgumentException> { SessionReadings(tag, first, listOf(RateReading(first - 60_000, 1200))) }
        assertFailsWith<IllegalArgumentException> { SessionReadings(tag, first, listOf(RateReading(first + 60_000, 1200), RateReading(first, 1200))) }
        assertFailsWith<IllegalArgumentException> { SessionReadings(tag, first, listOf(RateReading(first, 1200), RateReading(first, 1300))) }
        for (bad in listOf("", "abc", "has space", "slash/inside", "x".repeat(33), "serial:23211234567")) {
            assertFailsWith<IllegalArgumentException>(bad) { SessionReadings(bad, first, emptyList()) }
            assertFailsWith<IllegalArgumentException>(bad) { LedgerEntry(bad, first, listOf(0), "", offset, home, 1, null) }
        }
        val written = done(session(0), null)
        val elsewhere = SessionReadings(tag, first + 60_000, emptyList())
        assertFailsWith<IllegalArgumentException> { Reconcile.session(elsewhere, written, offset, home, now) }
        val print = "f".repeat(64)
        LedgerEntry(tag, first, listOf(0, 1), print, offset, home, 1, null)
        assertFailsWith<IllegalArgumentException> { LedgerEntry(tag, first, listOf(2, 1), print, offset, home, 1, null) }
        assertFailsWith<IllegalArgumentException> { LedgerEntry(tag, first, listOf(1, 1), print, offset, home, 1, null) }
        assertFailsWith<IllegalArgumentException> { LedgerEntry(tag, first, listOf(-1), print, offset, home, 1, null) }
        assertFailsWith<IllegalArgumentException> { LedgerEntry(tag, first, listOf(0), print, offset, home, 0, null) }
        assertFailsWith<IllegalArgumentException> { LedgerEntry(tag, first, listOf(0), "not a fingerprint", offset, home, 1, null) }
        assertFailsWith<IllegalArgumentException> { LedgerEntry(tag, first, listOf(0), print, offset, home, 1, listOf(3, 2)) }
        // An entry that covers nothing is not an entry.
        assertFailsWith<IllegalArgumentException> { LedgerEntry(tag, first, emptyList(), "", offset, home, 1, null) }
        LedgerEntry(tag, first, emptyList(), "", offset, home, 1, emptyList())
    }

    @Test
    fun `the lists handed in cannot be changed afterwards`() {
        val readings = mutableListOf(RateReading(first, 1200))
        val session = SessionReadings(tag, first, readings)
        readings += RateReading(first + 60_000, 1300)
        assertEquals(1, session.readings.size)
        val minutes = mutableListOf(0, 1)
        val pending = mutableListOf(0, 1)
        val entry = LedgerEntry(tag, first, minutes, "f".repeat(64), offset, home, 1, pending)
        minutes += 2
        pending += 2
        assertEquals(listOf(0, 1), entry.minutes)
        assertEquals(listOf(0, 1), entry.pendingMinutes)
    }
}
