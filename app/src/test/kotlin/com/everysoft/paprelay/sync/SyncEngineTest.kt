// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.sync

import com.everysoft.paprelay.as11.As11AuthException
import com.everysoft.paprelay.as11.As11TransportException
import com.everysoft.paprelay.as11.MachineClockReading
import com.everysoft.paprelay.as11.SpoolAddress
import com.everysoft.paprelay.as11.SpoolName
import com.everysoft.paprelay.as11.SpoolRound
import com.everysoft.paprelay.as11.decode.Proto
import com.everysoft.paprelay.as11.decode.proto
import com.everysoft.paprelay.as11.decode.riceBlock
import com.everysoft.paprelay.ble.BluetoothOffException
import com.everysoft.paprelay.ble.NoAnswerException
import com.everysoft.paprelay.core.sync.SessionChange
import com.everysoft.paprelay.core.sync.SessionReadings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncEngineTest {
    private val serial = "23211234567"
    private val software = "SW04600.19.8.8.0.dced143ae"
    private val hour = 3_600_000L

    /** The phone's clock, and how far ahead of it the machine's runs. */
    private var phoneNow = 1_800_000_000_000L
    private var machineAhead = 361_600L
    private val machineNow get() = phoneNow + machineAhead

    /**
     * A session on the machine: its first minute's stamp, and a rate and leak for each minute.
     * [whole] gives it all seven signals a real session has; without it, the three these tests need.
     */
    private class Night(
        val firstMinuteEnd: Long,
        val rates: IntArray,
        val leaks: IntArray = IntArray(rates.size) { 2 },
        val kind: Long = 1,
        val whole: Boolean = false,
    )

    private fun Proto.night(night: Night) {
        val count = night.rates.size
        // A record whose series state no start time, as a damaged one might be.
        val stated = night.firstMinuteEnd != machine.dropStart
        fun Proto.series(field: Int, samples: IntArray, modulus: Int) = message(field) {
            varint(1, night.kind)
            if (stated) varint(2, night.firstMinuteEnd)
            bytes(3, riceBlock(samples, modulus))
        }
        message(5) {
            series(1, night.leaks, 4)
            if (night.whole) series(2, IntArray(count) { 50 + it % 3 }, 4)
            series(3, pressure(count), 2)
            series(4, night.rates, 8)
            if (night.whole) {
                series(5, IntArray(count) { 38 + it % 4 }, 4)
                series(6, IntArray(count) { 18 + it % 2 }, 4)
                series(7, IntArray(count) { 12 + it % 5 }, 4)
            }
            varint(15, 1)
        }
    }

    /**
     * A pressure series whose compressed form does not end exactly on a byte. A real session has
     * seven signals, and one of them practically always shows its padding, which is what proves
     * the session's length; with the two or three signals of a test that has to be arranged.
     */
    private fun pressure(count: Int): IntArray {
        for (step in 0..7) {
            val series = IntArray(count) { 40 + if (it >= count - 1 - step % count.coerceAtLeast(1)) 1 else 0 }
            if (!riceBlock(series, 2, padWith = 1).contentEquals(riceBlock(series, 2, padWith = 0))) return series
        }
        error("no padded series of $count samples")
    }

    private fun rates(count: Int, base: Int = 52) = IntArray(count) { base + it % 5 }

    private inner class Machine : MachineConnector, MachineSession {
        var nights = listOf<Night>()
        var serialNumber = serial
        var applicationIdentifier = software
        var notReady: SyncOutcome? = null
        var pairedSerial: String? = serial
        var failConnect: Exception? = null
        var clockJitterMs = listOf(0L, 0L, 0L)
        var roundTripMs = 40L
        var perRound = 100
        var failPullAfterRounds: Int? = null
        var emptyForEver = false

        /** The start of a session whose signals disagree about its length, or null for none. */
        var torn: Long? = null

        /** The start of a session whose series state no start time, or null for none. */
        var dropStart: Long? = null
        val starts = ArrayList<Long>()
        var connections = 0
        private var reads = 0
        private var pulls = 0

        override fun notReady() = notReady

        override fun pairedSerial() = pairedSerial

        override suspend fun <T> withSession(block: suspend (MachineSession) -> T): T {
            failConnect?.let { throw it }
            connections++
            reads = 0
            pulls = 0
            return block(this)
        }

        override suspend fun identity() = MachineIdentity(serialNumber, applicationIdentifier)

        override suspend fun clock(): MachineClockReading {
            val jitter = clockJitterMs[reads++ % clockJitterMs.size]
            // Sent at phoneNow, answered half a round trip later, read off the machine's clock then.
            return MachineClockReading(machineEpochMs = machineNow + roundTripMs / 2 + jitter, sentAtMs = phoneNow, roundTripMs = roundTripMs)
        }

        override suspend fun pull(log: SpoolName, from: SpoolAddress): SpoolRound {
            assertEquals(SpoolName.ONE_MINUTE, log)
            failPullAfterRounds?.let { if (pulls >= it) throw As11TransportException("link lost") }
            pulls++
            if (emptyForEver) return SpoolRound(ByteArray(0), ByteArray(32), JsonObject(mapOf("again" to JsonPrimitive(pulls))))
            val (wanted, offset) = when (from) {
                is SpoolAddress.FromMachineTime -> {
                    starts += from.machineEpochMs
                    nights.filter { it.firstMinuteEnd + (it.rates.size - 1) * 60_000L >= from.machineEpochMs } to 0
                }
                is SpoolAddress.Continuation -> nights.filter { it.firstMinuteEnd >= from.next.getValue("since").toString().toLong() } to
                    from.next.getValue(SpoolName.ONE_MINUTE.wireName).toString().toInt()
            }
            val since = wanted.firstOrNull()?.firstMinuteEnd ?: 0
            val page = wanted.drop(offset).take(perRound)
            val more = offset + page.size < wanted.size
            val next = if (more) JsonObject(mapOf(SpoolName.ONE_MINUTE.wireName to JsonPrimitive(offset + page.size), "since" to JsonPrimitive(since))) else null
            val tornAt = torn
            val data = proto {
                var placed = tornAt == null || offset > 0
                for (night in page) {
                    if (!placed && night.firstMinuteEnd > tornAt!!) {
                        placed = true
                        message(5) {
                            message(1) { varint(1, 1); varint(2, tornAt); bytes(3, riceBlock(IntArray(50) { 2 }, 4)) }
                            message(4) { varint(1, 1); varint(2, tornAt); bytes(3, riceBlock(rates(80), 8)) }
                            varint(15, 1)
                        }
                    }
                    night(night)
                }
            }
            return SpoolRound(data, ByteArray(32), next)
        }
    }

    /** Health Connect as the app sees it: records by client id, replaced only by a higher version. */
    private inner class HealthConnect : HealthSink {
        inner class Held(val version: Long, val rate: Double, val time: Instant)

        val records = HashMap<String, Held>()
        var notReady: SyncOutcome? = null
        var failOnApply: Int? = null
        var failAfterWrites: Int? = null
        var notAllowed = false
        var applies = 0
        var writes = 0

        override suspend fun notReady() = notReady

        override suspend fun apply(change: SessionChange) {
            applies++
            if (failOnApply == applies) throw HealthSinkException(notAllowed)
            for (id in change.deleteRecordIds) records.remove(id)
            for ((index, write) in change.writes.withIndex()) {
                if (failAfterWrites == index) throw HealthSinkException(notAllowed)
                val held = records[write.recordId]
                if (held == null || write.version > held.version) records[write.recordId] = Held(write.version, write.breathsPerMinute, write.time)
                writes++
            }
        }
    }

    private val machine = Machine()
    private val health = HealthConnect()
    private val directory: File = Files.createTempDirectory("paprelay-engine").toFile()
    private val store = SyncStore(File(directory, "sync.json"))
    private val kept = ArrayList<Int>()
    private val engine = SyncEngine(machine, health, store, nowMs = { phoneNow }, keepRaw = { _, round -> kept += round.data.size })

    /** These tests are about the minute-by-minute respiratory rate, which is written only when switched on. */
    private val switchedOn = SyncState.EMPTY.with(respiratoryRate = true)

    init {
        store.write(switchedOn)
    }

    private val tag = SyncEngine.machineTag(serial)

    /** What Health Connect should hold for these nights: every minute (within the leak limit, if one is set), at its corrected time. */
    private fun expected(nights: List<Night>, leakLimit: Int? = null): Map<String, Pair<Double, Instant>> = nights.flatMap { night ->
        night.rates.indices.filter { leakLimit == null || night.leaks[it] * 1.2 <= leakLimit }.map { minute ->
            val stamp = night.firstMinuteEnd + minute * 60_000L
            "rr-$tag-$stamp" to (night.rates[minute] / 4.0 to Instant.ofEpochMilli(stamp - machineAhead))
        }
    }.toMap()

    private fun assertHolds(nights: List<Night>, leakLimit: Int? = null) {
        val want = expected(nights, leakLimit)
        assertEquals(want.keys, health.records.keys)
        for ((id, value) in want) {
            assertEquals(value.first, health.records.getValue(id).rate, id)
            assertEquals(value.second, health.records.getValue(id).time, id)
        }
    }

    private val first get() = Night(machineNow - 50 * hour, rates(123))
    private val second get() = Night(machineNow - 20 * hour, rates(203, base = 48))

    @Test
    fun `the first synchronisation writes every minute, at its corrected time, and the second writes nothing`() = runTest {
        val nights = listOf(first, second)
        machine.nights = nights
        val report = engine.sync()!!
        assertEquals(SyncOutcome.COMPLETE, report.outcome)
        assertEquals(2, report.sessions)
        assertEquals(326, report.readingsWritten)
        assertHolds(nights)
        // It asked for as far back as the machine might hold.
        assertEquals(listOf(machineNow + 20 - SyncEngine.FIRST_WINDOW_MS), machine.starts)
        assertEquals(1, kept.size)

        val state = store.read()
        assertEquals(tag, state.machineTag)
        assertEquals(2, state.ledger.size)
        assertEquals(phoneNow, state.lastCompleteAtMs)
        assertEquals(nights[1].firstMinuteEnd, state.resumeFromMs)

        phoneNow += hour
        val again = engine.sync()!!
        assertEquals(SyncOutcome.COMPLETE, again.outcome)
        assertEquals(0, again.readingsWritten)
        assertEquals(2, health.applies) // nothing more was sent to Health Connect
        // And this time it began a day before the newest session, not a year back.
        assertEquals(nights[1].firstMinuteEnd - SyncEngine.OVERLAP_MS, machine.starts.last())
    }

    @Test
    fun `a session still in progress is written again whole when it has grown, without moving in time`() = runTest {
        val started = machineNow - 2 * hour
        machine.nights = listOf(Night(started, rates(60)))
        engine.sync()
        assertEquals(60, health.records.size)
        val firstMinute = health.records.getValue("rr-$tag-$started")

        // Two hours later the same session is 180 minutes long, and the clock has been measured a little differently.
        phoneNow += 2 * hour
        machine.clockJitterMs = listOf(700L, 700L, 700L)
        machine.nights = listOf(Night(started, rates(180)))
        val report = engine.sync()!!
        assertEquals(SyncOutcome.COMPLETE, report.outcome)
        assertEquals(180, report.readingsWritten)
        assertEquals(180, health.records.size)
        val rewritten = health.records.getValue("rr-$tag-$started")
        assertEquals(firstMinute.time, rewritten.time)
        assertTrue(rewritten.version > firstMinute.version)
        assertEquals(1, store.read().ledger.size)
    }

    @Test
    fun `a run cut off in Health Connect keeps what was finished and completes next time`() = runTest {
        val nights = listOf(first, second, Night(machineNow - 3 * hour, rates(101)))
        machine.nights = nights
        health.failOnApply = 2
        val report = engine.sync()!!
        assertEquals(SyncOutcome.HEALTH_CONNECT_FAILED, report.outcome)
        assertEquals(1, report.sessions)
        // The first session is confirmed; the second is recorded as begun and not confirmed.
        assertEquals(listOf(false, true), store.read().ledger.values.sortedBy { it.firstMinuteEnd }.map { it.pendingMinutes != null })
        assertNull(store.read().lastCompleteAtMs)
        assertHolds(nights.take(1))

        health.failOnApply = null
        val next = engine.sync()!!
        assertEquals(SyncOutcome.COMPLETE, next.outcome)
        assertEquals(203 + 101, next.readingsWritten)
        assertHolds(nights)
    }

    @Test
    fun `a write that got half-way through a session is put right by the next run`() = runTest {
        val nights = listOf(first)
        machine.nights = nights
        health.failAfterWrites = 50
        assertEquals(SyncOutcome.HEALTH_CONNECT_FAILED, engine.sync()!!.outcome)
        assertEquals(50, health.records.size)
        // Nothing is confirmed, but the ledger knows a write was begun and how far it may have got.
        val begun = store.read().ledger.values.single()
        assertTrue(begun.minutes.isEmpty())
        assertEquals(123, begun.pendingMinutes!!.size)

        health.failAfterWrites = null
        phoneNow += 1_000
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertHolds(nights)
    }

    @Test
    fun `losing the link during the download keeps the sessions already finished`() = runTest {
        val nights = listOf(first, second, Night(machineNow - 3 * hour, rates(101)))
        machine.nights = nights
        machine.perRound = 1
        machine.failPullAfterRounds = 2
        val report = engine.sync()!!
        assertEquals(SyncOutcome.CONNECTION_LOST, report.outcome)
        assertEquals(2, report.sessions)
        assertHolds(nights.take(2))

        machine.failPullAfterRounds = null
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertHolds(nights)
        assertEquals(3, store.read().ledger.size)
    }

    @Test
    fun `a night that cannot be read does not hold up the nights after it, and is read again next time`() = runTest {
        val earlier = first
        val broken = Night(machineNow - 20 * hour, rates(203), kind = 2)
        val after = Night(machineNow - 3 * hour, rates(101))
        machine.nights = listOf(earlier, broken, after)
        val report = engine.sync()!!
        assertEquals(SyncOutcome.PARTIAL, report.outcome)
        assertEquals(2, report.sessions)
        assertHolds(listOf(earlier, after))
        // The place to resume from stays before the gap, so the next run reads it again.
        assertEquals(earlier.firstMinuteEnd, store.read().resumeFromMs)
        assertNull(store.read().lastCompleteAtMs)

        // A later version of the machine's record (or of this app) that can be read fills the gap.
        val mended = Night(broken.firstMinuteEnd, broken.rates)
        machine.nights = listOf(earlier, mended, after)
        phoneNow += hour
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertHolds(listOf(earlier, mended, after))
        assertEquals(after.firstMinuteEnd, store.read().resumeFromMs)
    }

    @Test
    fun `a night that stays unreadable for a month stops holding the place back`() = runTest {
        val day = 24 * hour
        val old = Night(machineNow - 40 * day, rates(60))
        val broken = Night(machineNow - 39 * day, rates(61), kind = 2)
        val recent = Night(machineNow - 2 * hour, rates(62))
        machine.nights = listOf(old, broken, recent)
        assertEquals(SyncOutcome.PARTIAL, engine.sync()!!.outcome)
        assertHolds(listOf(old, recent))
        // Not at the old night: every later run would otherwise re-read forty days for nothing.
        assertEquals(machineNow + 20 - SyncEngine.HOLD_LIMIT_MS, store.read().resumeFromMs)
        // The next run no longer sees it, and has nothing to complain of.
        phoneNow += hour
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertEquals(recent.firstMinuteEnd, store.read().resumeFromMs)
    }

    @Test
    fun `a night that cannot be read is asked for again even when nothing readable came before it`() = runTest {
        val broken = Night(machineNow - 30 * hour, rates(90), kind = 2)
        val after = Night(machineNow - 6 * hour, rates(101))
        machine.nights = listOf(broken, after)
        assertEquals(SyncOutcome.PARTIAL, engine.sync()!!.outcome)
        assertHolds(listOf(after))
        // Still not readable an hour later: still said, and not passed over.
        phoneNow += hour
        assertEquals(SyncOutcome.PARTIAL, engine.sync()!!.outcome)
        assertNull(store.read().lastCompleteAtMs)
        // Once it can be read, it is.
        val mended = Night(broken.firstMinuteEnd, broken.rates)
        machine.nights = listOf(mended, after)
        phoneNow += hour
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertHolds(listOf(mended, after))

        // And a machine none of whose nights can be read is not reported as in step either.
        val lone = Night(machineNow - 2 * hour, rates(50), kind = 2)
        machine.nights = listOf(mended, after, lone)
        phoneNow += hour
        assertEquals(SyncOutcome.PARTIAL, engine.sync()!!.outcome)
        phoneNow += hour
        assertEquals(SyncOutcome.PARTIAL, engine.sync()!!.outcome)
    }

    @Test
    fun `the leak rule is off unless switched on, and changing it brings every night into step`() = runTest {
        // Minutes 10 to 19 leak 36 L/min.
        val leaky = Night(machineNow - 5 * hour, rates(60), leaks = IntArray(60) { if (it in 10..19) 30 else 2 })
        val older = Night(machineNow - 20 * 24 * hour, rates(45), leaks = IntArray(45) { if (it < 5) 40 else 1 })
        machine.nights = listOf(older, leaky)
        engine.sync()
        // By default every minute the machine recorded is passed on.
        assertHolds(listOf(older, leaky))
        assertEquals(105, health.records.size)

        // Switched on, the leaky minutes go: from the old night too, not only the recent one.
        store.write(store.read().withLeakLimit(24))
        phoneNow += 1_000
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertHolds(listOf(older, leaky), leakLimit = 24)
        assertEquals(90, health.records.size)

        // Switched off again, they come back.
        store.write(store.read().withLeakLimit(null))
        phoneNow += 1_000
        engine.sync()
        assertHolds(listOf(older, leaky))
    }

    @Test
    fun `a change is recorded as begun before Health Connect is touched, so nothing is orphaned whatever happens next`() = runTest {
        val leaky = Night(machineNow - 5 * hour, rates(60), leaks = IntArray(60) { if (it in 10..19) 30 else 2 })
        machine.nights = listOf(leaky)
        // The first write gets half-way and fails. The ledger already says what may be there.
        health.failAfterWrites = 30
        assertEquals(SyncOutcome.HEALTH_CONNECT_FAILED, engine.sync()!!.outcome)
        val begun = store.read().ledger.values.single()
        assertEquals((0 until 60).toList(), begun.pendingMinutes)
        assertTrue(begun.minutes.isEmpty())
        assertEquals(30, health.records.size)

        // Before the next run the leak rule is switched on. The twenty minutes already written that
        // are no longer wanted include ten the failed run did write; they must go.
        health.failAfterWrites = null
        store.write(store.read().withLeakLimit(24))
        phoneNow += 1_000
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertHolds(listOf(leaky), leakLimit = 24)
        assertNull(store.read().ledger.values.single().pendingMinutes)
    }

    @Test
    fun `when the machine's clock has been set, nothing is written until the user has decided`() = runTest {
        val earlier = first
        machine.nights = listOf(earlier)
        engine.sync()
        assertEquals(123, health.records.size)

        // The machine's clock is corrected by six minutes; a new night is recorded afterwards.
        val before = machineAhead
        machineAhead = 0
        phoneNow += 24 * hour
        val later = Night(machineNow - 3 * hour, rates(90))
        machine.nights = listOf(earlier, later)
        assertEquals(SyncOutcome.CLOCK_CHANGED, engine.sync()!!.outcome)
        assertEquals(123, health.records.size)
        // It keeps saying so; it does not quietly settle on the new clock.
        phoneNow += hour
        assertEquals(SyncOutcome.CLOCK_CHANGED, engine.sync()!!.outcome)

        // The user accepts the machine's clock as it now is. The new night is written with the new
        // correction; the old one stays exactly where it was.
        store.write(store.read().with(clock = null))
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertEquals(123 + 90, health.records.size)
        assertEquals(Instant.ofEpochMilli(later.firstMinuteEnd), health.records.getValue("rr-$tag-${later.firstMinuteEnd}").time)
        assertEquals(Instant.ofEpochMilli(earlier.firstMinuteEnd - before), health.records.getValue("rr-$tag-${earlier.firstMinuteEnd}").time)
    }

    @Test
    fun `ordinary drift between runs is not mistaken for a clock that was set`() = runTest {
        machine.nights = listOf(first)
        engine.sync()
        // Ten days on, the machine has gained another twenty seconds.
        phoneNow += 10 * 24 * hour
        machineAhead += 20_000
        machine.nights = listOf(Night(machineNow - 3 * hour, rates(90)))
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertEquals(123 + 90, health.records.size)
    }

    @Test
    fun `a reading that would lie in the future is refused`() = runTest {
        // A session stamped two hours ahead of the machine's own clock cannot be placed.
        machine.nights = listOf(Night(machineNow + 2 * hour, rates(30)))
        assertEquals(SyncOutcome.CLOCK_UNRELIABLE, engine.sync()!!.outcome)
        assertTrue(health.records.isEmpty())
        assertTrue(store.read().ledger.isEmpty())
    }

    @Test
    fun `asked to write everything again, it does so once, and Health Connect ends up whole`() = runTest {
        val nights = listOf(first, second)
        machine.nights = nights
        engine.sync()
        // (The nights are fixed above: the clock moves on below, and they must not move with it.)
        // Health Connect's data is cleared behind the app's back; the ledger knows nothing of it.
        health.records.clear()
        phoneNow += hour
        assertEquals(0, engine.sync()!!.readingsWritten)
        assertTrue(health.records.isEmpty())

        store.write(store.read().with(rewriteRequested = true).fromTheStart())
        phoneNow += hour
        val report = engine.sync()!!
        assertEquals(SyncOutcome.COMPLETE, report.outcome)
        assertEquals(123 + 203, report.readingsWritten)
        assertHolds(nights)
        assertTrue(!store.read().rewriteRequested)
        phoneNow += hour
        assertEquals(0, engine.sync()!!.readingsWritten)
    }

    @Test
    fun `nothing is written for another machine, software of another line, or a clock that cannot be read`() = runTest {
        machine.nights = listOf(first)
        machine.serialNumber = "23219999999"
        assertEquals(SyncOutcome.WRONG_MACHINE, engine.sync()!!.outcome)
        machine.serialNumber = serial

        machine.applicationIdentifier = "SW04700.20.1.0.0.abcdef"
        assertEquals(SyncOutcome.SOFTWARE_NOT_CONFIRMED, engine.sync()!!.outcome)
        machine.applicationIdentifier = software

        machine.clockJitterMs = listOf(0L, 5_000L, 0L)
        assertEquals(SyncOutcome.CLOCK_UNRELIABLE, engine.sync()!!.outcome)
        machine.clockJitterMs = listOf(0L, 0L, 0L)
        machine.roundTripMs = 5_000
        assertEquals(SyncOutcome.CLOCK_UNRELIABLE, engine.sync()!!.outcome)
        machine.roundTripMs = 40

        assertTrue(health.records.isEmpty())
        assertEquals(0, health.applies)
        assertTrue(store.read().ledger.isEmpty())
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertHolds(listOf(first))
    }

    @Test
    fun `each way of not getting through is told apart, and none of them touches the record of what was written`() = runTest {
        machine.nights = listOf(first)
        engine.sync()
        val before = store.read()
        fun unchanged() {
            val now = store.read()
            assertEquals(before.ledger.keys, now.ledger.keys)
            assertEquals(before.resumeFromMs, now.resumeFromMs)
            assertEquals(before.lastCompleteAtMs, now.lastCompleteAtMs)
            assertEquals(123, health.records.size)
        }
        val cases = listOf<Pair<SyncOutcome, () -> Unit>>(
            SyncOutcome.MACHINE_AWAY to { machine.failConnect = NoAnswerException("nothing answered") },
            SyncOutcome.BLUETOOTH_OFF to { machine.failConnect = BluetoothOffException("off") },
            SyncOutcome.PAIRING_REJECTED to { machine.failConnect = As11AuthException.PairingUnknown(null) },
            SyncOutcome.CONNECTION_LOST to { machine.failConnect = As11TransportException("link lost") },
            SyncOutcome.INTERNAL to { machine.failConnect = IllegalStateException("a defect") },
            SyncOutcome.BLUETOOTH_NOT_ALLOWED to { machine.notReady = SyncOutcome.BLUETOOTH_NOT_ALLOWED },
            SyncOutcome.HEALTH_CONNECT_NOT_ALLOWED to { health.notReady = SyncOutcome.HEALTH_CONNECT_NOT_ALLOWED },
        )
        for ((outcome, arrange) in cases) {
            machine.failConnect = null
            machine.notReady = null
            health.notReady = null
            arrange()
            phoneNow += 60_000
            val report = engine.sync()!!
            assertEquals(outcome, report.outcome)
            assertEquals(outcome, store.read().lastReport!!.outcome)
            unchanged()
        }
        // Only the last got as far as a session: Health Connect not allowing the respiratory rate is
        // found out after the nights have been fetched, which need nothing of it. The others never connected.
        assertEquals(2, machine.connections)
    }

    @Test
    fun `a write refused for want of permission is reported as that`() = runTest {
        machine.nights = listOf(first)
        health.failOnApply = 1
        health.notAllowed = true
        assertEquals(SyncOutcome.HEALTH_CONNECT_NOT_ALLOWED, engine.sync()!!.outcome)
    }

    @Test
    fun `a long history arrives in several rounds and all of it is written`() = runTest {
        val nights = (1..12).map { Night(machineNow - (13 - it) * 24 * hour, rates(30 + it)) }
        machine.nights = nights
        machine.perRound = 5
        val report = engine.sync()!!
        assertEquals(SyncOutcome.COMPLETE, report.outcome)
        assertEquals(12, report.sessions)
        assertEquals(3, kept.size)
        assertHolds(nights)
    }

    @Test
    fun `only one synchronisation runs at a time`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val slow = object : MachineConnector by machine {
            override suspend fun <T> withSession(block: suspend (MachineSession) -> T): T {
                gate.await()
                return machine.withSession(block)
            }
        }
        val waiting = SyncEngine(slow, health, store, nowMs = { phoneNow })
        machine.nights = listOf(first)
        val running = async { waiting.sync() }
        testScheduler.runCurrent()
        assertNull(waiting.sync())
        gate.complete(Unit)
        assertEquals(SyncOutcome.COMPLETE, running.await()!!.outcome)
    }

    @Test
    fun `short sessions wait until a long one has shown that this software is read correctly`() = runTest {
        val nap = Night(machineNow - 70 * hour, rates(12))
        val later = Night(machineNow - 60 * hour, rates(SyncEngine.VOUCH_SAMPLES - 1, base = 44))
        machine.nights = listOf(nap, later)
        // Only short ones: nothing is written, and the place is held so that they are read again.
        val held = engine.sync()!!
        assertEquals(SyncOutcome.PARTIAL, held.outcome)
        assertEquals(0, held.sessions)
        assertTrue(health.records.isEmpty())
        assertNull(store.read().vouchedSoftware)

        // A long session arrives after them. It vouches for the software, and the short ones
        // that came before it in the same download are written with it.
        phoneNow += hour
        val long = first
        machine.nights = listOf(nap, later, long)
        val whole = engine.sync()!!
        assertEquals(SyncOutcome.COMPLETE, whole.outcome)
        assertEquals(3, whole.sessions)
        assertHolds(listOf(nap, later, long))
        assertEquals(software, store.read().vouchedSoftware)

        // From then on a short session is written without waiting.
        phoneNow += 48 * hour
        val brief = Night(machineNow - 2 * hour, rates(10, base = 56))
        machine.nights = listOf(nap, later, long, brief)
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertHolds(listOf(nap, later, long, brief))
    }

    private val uncompared = "SW04600.20.1.0.0.abcdef"

    private fun ids(vararg nights: Night) = nights.flatMap { night -> night.rates.indices.map { "rr-$tag-${night.firstMinuteEnd + it * 60_000L}" } }.toSet()

    @Test
    fun `on software that was not compared, nothing that was on the machine when its log was first read is written`() = runTest {
        // A machine full of sessions, any of which an earlier version may have recorded. One is
        // stamped later than now, as a clock that was set back leaves them.
        machine.applicationIdentifier = uncompared
        val old = Night(machineNow - 30 * hour, rates(400), whole = true)
        val small = Night(machineNow - 3 * hour, rates(10, base = 60), whole = true)
        val ahead = Night(machineNow + 2 * hour, rates(300, base = 50), whole = true)
        machine.nights = listOf(old, small, ahead)
        val report = engine.sync()!!
        // Nothing is wrong, and nothing is written: none of them may vouch, and none is the software's own.
        assertEquals(SyncOutcome.COMPLETE, report.outcome)
        assertEquals(0, report.readingsWritten)
        assertHolds(emptyList())
        assertNull(store.read().vouchedSoftware)
        // Its own sessions begin after the last minute that was there, not at "now".
        assertEquals(ahead.firstMinuteEnd + 299 * 60_000L + 1, store.read().softwareOwnFromMs)
        // Switched on and waiting, which the screen says.
        assertTrue(store.read().rateWaiting)

        // So it stays when that session's time has come: it was there before.
        phoneNow += 24 * hour
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertHolds(emptyList())
        assertNull(store.read().vouchedSoftware)
        assertEquals(ahead.firstMinuteEnd + 299 * 60_000L + 1, store.read().softwareOwnFromMs)

        // A night the software records itself vouches, and is the only thing written.
        phoneNow += 24 * hour
        val own = Night(machineNow - 8 * hour, rates(310, base = 46), whole = true)
        machine.nights = listOf(old, small, ahead, own)
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertHolds(listOf(own))
        assertEquals(uncompared, store.read().vouchedSoftware)
        assertTrue(!store.read().rateWaiting)
    }

    @Test
    fun `on software that was not compared, an hour of it decoding whole vouches for what it recorded`() = runTest {
        machine.applicationIdentifier = uncompared
        val old = Night(machineNow - 30 * hour, rates(400), whole = true)
        machine.nights = listOf(old)
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        val since = store.read().softwareOwnFromMs

        // A short session it recorded waits. That is how things stand, and nothing has gone wrong.
        phoneNow += 12 * hour
        val short = Night(machineNow - 2 * hour, rates(20, base = 60), whole = true)
        machine.nights = listOf(old, short)
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertHolds(emptyList())
        assertTrue(store.read().rateWaiting)
        // The place to go on from stays before it.
        assertEquals(old.firstMinuteEnd, store.read().resumeFromMs)

        // Just under an hour is not enough for software nothing is known of.
        phoneNow += 12 * hour
        val middling = Night(machineNow - 3 * hour, rates(59, base = 48), whole = true)
        machine.nights = listOf(old, short, middling)
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertHolds(emptyList())
        assertNull(store.read().vouchedSoftware)

        // An hour is. What waited is written with it; what was there before never is.
        phoneNow += 12 * hour
        val night = Night(machineNow - 8 * hour, rates(60, base = 50), whole = true)
        machine.nights = listOf(old, short, middling, night)
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertHolds(listOf(short, middling, night))
        assertEquals(uncompared, store.read().vouchedSoftware)
        assertEquals(since, store.read().softwareOwnFromMs)

        // From then on its short sessions are written as they come.
        phoneNow += 12 * hour
        val nap = Night(machineNow - hour, rates(15, base = 44), whole = true)
        machine.nights = listOf(short, middling, night, nap)
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertHolds(listOf(short, middling, night, nap))
    }

    @Test
    fun `on software that was not compared, a session that is not whole and clean vouches for nothing`() = runTest {
        machine.applicationIdentifier = uncompared
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)

        // Long, but with three of the seven signals: their agreeing proves little.
        phoneNow += 12 * hour
        val thin = Night(machineNow - 8 * hour, rates(300))
        machine.nights = listOf(thin)
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertHolds(emptyList())

        // Long and whole, with a leak no machine reports: something is not read as it was meant.
        phoneNow += 24 * hour
        val absurd = Night(machineNow - 8 * hour, rates(300, base = 50), leaks = IntArray(300) { 101 }, whole = true)
        machine.nights = listOf(thin, absurd)
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertHolds(emptyList())
        assertNull(store.read().vouchedSoftware)

        // Long, whole and clean, but stamped later than now.
        val early = Night(machineNow + hour, rates(300, base = 46), whole = true)
        machine.nights = listOf(thin, absurd, early)
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertHolds(emptyList())
        assertNull(store.read().vouchedSoftware)
    }

    @Test
    fun `on software that was not compared, a torn record does not keep a clean night from vouching`() = runTest {
        // One record was cut short before the software's log was first read, and the machine keeps it.
        machine.applicationIdentifier = uncompared
        val old = Night(machineNow - 30 * hour, rates(400), whole = true)
        val older = Night(machineNow - 80 * hour, rates(200), whole = true)
        machine.nights = listOf(older, old)
        val tornAt = machineNow - 20 * hour
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)

        // It began before the software's own sessions, as its own start time says (no older session
        // follows it to say so). It is nothing to wait for or to read again.
        phoneNow += 24 * hour
        val night = Night(machineNow - 8 * hour, rates(300, base = 50), whole = true)
        machine.nights = listOf(older, old, night)
        machine.torn = tornAt
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertHolds(listOf(night))
        assertEquals(uncompared, store.read().vouchedSoftware)
        assertEquals(night.firstMinuteEnd, store.read().resumeFromMs)

        // One torn among the software's own is a night that could not be read; the next clean one is written all the same.
        phoneNow += 24 * hour
        val next = Night(machineNow - 8 * hour, rates(280, base = 44), whole = true)
        machine.nights = listOf(old, night, next)
        machine.torn = next.firstMinuteEnd - 3 * hour
        assertEquals(SyncOutcome.PARTIAL, engine.sync()!!.outcome)
        assertHolds(listOf(night, next))
    }

    @Test
    fun `a torn record among its own does not keep software that was not compared from being vouched for`() = runTest {
        machine.applicationIdentifier = uncompared
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)

        phoneNow += 24 * hour
        val night = Night(machineNow - 8 * hour, rates(300, base = 50), whole = true)
        machine.nights = listOf(night)
        machine.torn = night.firstMinuteEnd - 3 * hour
        // The torn record is a night that could not be read, and is said. The clean night stands on its own evidence.
        assertEquals(SyncOutcome.PARTIAL, engine.sync()!!.outcome)
        assertHolds(listOf(night))
        assertEquals(uncompared, store.read().vouchedSoftware)
    }

    @Test
    fun `beside a torn record, a short session is believed only on the strength of a long one of the software's own`() = runTest {
        // An old long night is still within reach of each run; it answers nothing for the new software.
        val under = Night(machineNow - 8 * hour, rates(400))
        machine.nights = listOf(under)
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        phoneNow += 2 * hour
        machine.applicationIdentifier = uncompared
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        phoneNow += 4 * hour
        val own = Night(machineNow - 3 * hour, rates(90, base = 50), whole = true)
        machine.nights = listOf(under, own)
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertHolds(listOf(under, own))

        // A torn record of the software's own, and a nap after it, with nothing long of its own beside them.
        phoneNow += 5 * hour
        val nap = Night(machineNow - hour, rates(12, base = 60), whole = true)
        machine.nights = listOf(under, nap)
        machine.torn = nap.firstMinuteEnd - hour
        assertEquals(SyncOutcome.PARTIAL, engine.sync()!!.outcome)
        assertHolds(listOf(under, own))
    }

    @Test
    fun `a record that fails without saying when it began is dated by what follows it`() = runTest {
        machine.applicationIdentifier = uncompared
        // A record that states no start time, between two that can be read: all three were there before.
        val first = Night(machineNow - 60 * hour, rates(100), whole = true)
        val strange = Night(machineNow - 40 * hour, rates(100), whole = true)
        val last = Night(machineNow - 20 * hour, rates(100), whole = true)
        machine.dropStart = strange.firstMinuteEnd
        machine.nights = listOf(first, strange, last)
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        // Read again within the day, it is still older than what follows it, and nothing to report.
        phoneNow += hour
        store.write(store.read().fromTheStart())
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)

        // One that comes after the software's own sessions began is a night that could not be read.
        phoneNow += 24 * hour
        val own = Night(machineNow - 10 * hour, rates(300, base = 50), whole = true)
        val odd = Night(machineNow - 4 * hour, rates(40), whole = true)
        val nap = Night(machineNow - 2 * hour, rates(15, base = 44), whole = true)
        machine.dropStart = odd.firstMinuteEnd
        machine.nights = listOf(first, strange, last, own, odd, nap)
        assertEquals(SyncOutcome.PARTIAL, engine.sync()!!.outcome)
        assertHolds(listOf(own, nap))
        // And so is one with nothing after it to date it by.
        phoneNow += 24 * hour
        store.write(store.read().fromTheStart())
        val lastOdd = Night(machineNow - 3 * hour, rates(40), whole = true)
        machine.dropStart = lastOdd.firstMinuteEnd
        machine.nights = listOf(own, nap, lastOdd)
        assertEquals(SyncOutcome.PARTIAL, engine.sync()!!.outcome)
    }

    @Test
    fun `a record of a kind not known is dated by the start time it states`() = runTest {
        machine.applicationIdentifier = uncompared
        // The last record on the machine when the log is first read is of a kind not known.
        val old = Night(machineNow - 30 * hour, rates(400), whole = true)
        val strange = Night(machineNow - 4 * hour, rates(100), kind = 2, whole = true)
        machine.nights = listOf(old, strange)
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        // Every later run reads it again before the software's own nights: it is older, not theirs.
        repeat(2) {
            phoneNow += 24 * hour
            machine.nights = machine.nights + Night(machineNow - 8 * hour, rates(300, base = 50 + it), whole = true)
            assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        }
        assertHolds(machine.nights.drop(2))
    }

    @Test
    fun `what one version owns is settled afresh for the next`() = runTest {
        machine.applicationIdentifier = uncompared
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        phoneNow += 24 * hour
        val night = Night(machineNow - 8 * hour, rates(300, base = 50), whole = true)
        machine.nights = listOf(night)
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertHolds(listOf(night))

        // Updated again. A night recorded under the first version, not yet fetched, is not the second's.
        phoneNow += 24 * hour
        val unfetched = Night(machineNow - 8 * hour, rates(300, base = 46), whole = true)
        machine.nights = listOf(night, unfetched)
        machine.applicationIdentifier = "SW04600.20.2.0.0.abcdef"
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertHolds(listOf(night))
        assertNull(store.read().vouchedSoftware)
    }

    @Test
    fun `when the machine is updated to software that was not compared, only what follows is newly written`() = runTest {
        val under = Night(machineNow - 8 * hour, rates(400))
        machine.nights = listOf(under)
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertHolds(listOf(under))

        // A night that the phone did not fetch before the update: which software recorded it is not known.
        phoneNow += 24 * hour
        val between = Night(machineNow - 8 * hour, rates(300, base = 50), whole = true)
        machine.applicationIdentifier = uncompared
        machine.nights = listOf(under, between)
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertHolds(listOf(under))
        assertNull(store.read().vouchedSoftware)

        // The first night after it vouches, and is written. The one between is not, then or later.
        phoneNow += 24 * hour
        val after = Night(machineNow - 8 * hour, rates(310, base = 46), whole = true)
        machine.nights = listOf(under, between, after)
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertHolds(listOf(under, after))
        phoneNow += hour
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertHolds(listOf(under, after))

        // Health Connect is cleared and everything is to be written again. What this app wrote
        // before the update it writes again: it read those very bytes under software it knew.
        health.records.clear()
        store.write(store.read().with(rewriteRequested = true).fromTheStart())
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertHolds(listOf(under, after))
        assertTrue(store.read().ledger.values.all { it.pendingMinutes.isNullOrEmpty() })
    }

    @Test
    fun `a write that was cut short before the update is finished after it`() = runTest {
        val under = Night(machineNow - 8 * hour, rates(400))
        machine.nights = listOf(under)
        health.failOnApply = 1
        assertEquals(SyncOutcome.HEALTH_CONNECT_FAILED, engine.sync()!!.outcome)
        health.failOnApply = null

        phoneNow += 24 * hour
        machine.applicationIdentifier = uncompared
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertHolds(listOf(under))
        assertTrue(store.read().ledger.values.all { it.pendingMinutes.isNullOrEmpty() })
    }

    @Test
    fun `on software that was not compared, accepting a changed clock keeps what is the software's own`() = runTest {
        machine.applicationIdentifier = uncompared
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        phoneNow += 24 * hour
        val night = Night(machineNow - 8 * hour, rates(300, base = 50), whole = true)
        machine.nights = listOf(night)
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        val since = store.read().softwareOwnFromMs

        // The clock is set back an hour, and a nap follows before the app is opened.
        machineAhead -= hour
        phoneNow += 24 * hour
        val nap = Night(machineNow - 2 * hour, rates(10, base = 60), whole = true)
        machine.nights = listOf(night, nap)
        assertEquals(SyncOutcome.CLOCK_CHANGED, engine.sync()!!.outcome)
        // The user has the clock used as it is. The nap gets its time from it, as the question said.
        store.write(store.read().clockAccepted())
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertEquals(ids(night, nap), health.records.keys)
        assertEquals(uncompared, store.read().vouchedSoftware)
        assertEquals(since, store.read().softwareOwnFromMs)
    }

    @Test
    fun `an update and a clock set back in the same gap leave nothing of the old software to be taken for the new`() = runTest {
        val under = Night(machineNow - 8 * hour, rates(400))
        machine.nights = listOf(under)
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)

        // A night under the old software, not fetched. Then the update, and the clock goes back a
        // day: that night is now stamped later than the machine's "now".
        phoneNow += 24 * hour
        val unfetched = Night(machineNow - 8 * hour, rates(300, base = 50), whole = true)
        machine.applicationIdentifier = uncompared
        machineAhead -= 24 * hour
        machine.nights = listOf(under, unfetched)
        assertEquals(SyncOutcome.CLOCK_CHANGED, engine.sync()!!.outcome)
        store.write(store.read().clockAccepted())
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertEquals(ids(under), health.records.keys)
        assertNull(store.read().vouchedSoftware)

        // Nor when its time has come.
        phoneNow += 30 * hour
        val nap = Night(machineNow - 2 * hour, rates(10, base = 60), whole = true)
        machine.nights = listOf(under, unfetched, nap)
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertEquals(ids(under), health.records.keys)
        assertNull(store.read().vouchedSoftware)
    }

    @Test
    fun `a moment that lies after the machine's time, or is missing, is settled again`() = runTest {
        // As a clock set far back leaves it; and as a version of the app that kept no such moment left it.
        for (since in listOf<Long?>(machineNow + 30 * 24 * hour, null)) {
            health.records.clear()
            store.write(switchedOn.with(machineTag = tag, softwareSeen = uncompared, vouchedSoftware = uncompared, softwareOwnFromMs = since))
            machine.applicationIdentifier = uncompared
            val old = Night(machineNow - 30 * hour, rates(400), whole = true)
            val small = Night(machineNow - 3 * hour, rates(10, base = 60), whole = true)
            machine.nights = listOf(old, small)
            assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
            assertHolds(emptyList())
            assertNull(store.read().vouchedSoftware)
            assertEquals(machineNow + 20, store.read().softwareOwnFromMs)
        }
    }

    @Test
    fun `what is the software's own is settled only by a run that read the log to its end`() = runTest {
        machine.applicationIdentifier = uncompared
        val ahead = Night(machineNow + 2 * hour, rates(300, base = 50), whole = true)
        machine.nights = listOf(ahead)
        // The machine keeps saying there is more and sends nothing: what it holds has not been seen.
        machine.emptyForEver = true
        assertEquals(SyncOutcome.PARTIAL, engine.sync()!!.outcome)
        assertNull(store.read().softwareOwnFromMs)

        machine.emptyForEver = false
        phoneNow += hour
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertEquals(ahead.firstMinuteEnd + 299 * 60_000L + 1, store.read().softwareOwnFromMs)
        assertHolds(emptyList())
    }

    @Test
    fun `on software that never vouches, the wait does not hold the place for ever`() = runTest {
        machine.applicationIdentifier = uncompared
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        // Forty nights that are never whole.
        val nights = ArrayList<Night>()
        repeat(40) {
            phoneNow += 24 * hour
            nights += Night(machineNow - 8 * hour, rates(300))
        }
        machine.nights = nights
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertHolds(emptyList())
        assertTrue(store.read().rateWaiting)
        assertEquals(machineNow + 20 - SyncEngine.HOLD_LIMIT_MS, store.read().resumeFromMs)
    }

    @Test
    fun `the user is told that respiratory rate waits only when it does`() {
        val waiting = switchedOn.with(softwareSeen = uncompared)
        assertTrue(waiting.rateWaiting)
        assertTrue(!waiting.with(vouchedSoftware = uncompared).rateWaiting)
        assertTrue(!waiting.with(respiratoryRate = false).rateWaiting)
        // Never on the compared version, and not before any software has been seen.
        assertTrue(!switchedOn.with(softwareSeen = software).rateWaiting)
        assertTrue(!switchedOn.rateWaiting)
    }

    @Test
    fun `accepting a changed clock drops the moment the compared version was seen and keeps what other software owns`() {
        val state = switchedOn.with(softwareSinceMs = 1_000L, softwareOwnFromMs = 3_000L, resumeFromMs = 2_000L, clock = ClockBaseline(5L, 6L))
        for (seen in listOf(software, uncompared, null)) {
            val accepted = state.with(softwareSeen = seen).clockAccepted()
            assertNull(accepted.softwareSinceMs)
            assertEquals(3_000L, accepted.softwareOwnFromMs)
            assertNull(accepted.clock)
            assertNull(accepted.resumeFromMs)
        }
    }

    @Test
    fun `an install upgraded from a version that kept only when the software was first seen settles what it owns afresh`() = runTest {
        // An earlier build saw the update, stored "now" as the moment, and may have vouched. One
        // night of the old software was not fetched, and the clock was set back a day in the same
        // gap: that night is stamped after the stored moment.
        val under = Night(machineNow - 8 * hour, rates(400))
        machine.nights = listOf(under)
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        phoneNow += 24 * hour
        val unfetched = Night(machineNow - 8 * hour, rates(300, base = 50), whole = true)
        machineAhead -= 24 * hour
        machine.applicationIdentifier = uncompared
        machine.nights = listOf(under, unfetched)
        store.write(store.read().with(softwareSeen = uncompared, softwareSinceMs = machineNow, vouchedSoftware = uncompared).clockAccepted())
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        // Nothing of the old software is taken for the new, and the earlier vouch is gone.
        assertEquals(ids(under), health.records.keys)
        assertNull(store.read().vouchedSoftware)
        assertTrue(store.read().softwareOwnFromMs!! > unfetched.firstMinuteEnd)

        // An earlier build's moment, from before that night was recorded, as it kept it: a day on,
        // the night is in the past and stamped after that moment.
        phoneNow += 30 * hour
        store.write(store.read().with(softwareSinceMs = unfetched.firstMinuteEnd - hour, softwareOwnFromMs = null, vouchedSoftware = uncompared).fromTheStart())
        assertTrue(unfetched.firstMinuteEnd + 299 * 60_000L < machineNow)
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertEquals(ids(under), health.records.keys)
        assertNull(store.read().vouchedSoftware)
    }

    @Test
    fun `a record that cannot be decoded is dated by the start time it states`() = runTest {
        machine.applicationIdentifier = uncompared
        // The last record on the machine when the log is first read is torn.
        val old = Night(machineNow - 30 * hour, rates(400), whole = true)
        val tornAt = machineNow - 4 * hour
        machine.nights = listOf(old)
        machine.torn = tornAt
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        // In every later run it is still older than what the software owns, whatever comes after it.
        repeat(3) {
            phoneNow += 24 * hour
            machine.nights = machine.nights + Night(machineNow - 8 * hour, rates(300, base = 50 + it), whole = true)
            assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        }
        assertHolds(machine.nights.drop(1))
    }

    @Test
    fun `on software that was not compared, a session still going on is left until it has ended`() = runTest {
        machine.applicationIdentifier = uncompared
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        phoneNow += 24 * hour
        val night = Night(machineNow - 8 * hour, rates(300, base = 50), whole = true)
        val going = Night(machineNow - 30 * 60_000L, rates(30, base = 44), whole = true)
        machine.nights = listOf(night, going)
        val report = engine.sync()!!
        assertEquals(SyncOutcome.COMPLETE, report.outcome)
        assertTrue(report.sessionInProgress)
        assertHolds(listOf(night))
    }

    @Test
    fun `sessions older than what the software owns do not hold the place to resume from`() = runTest {
        machine.applicationIdentifier = uncompared
        val old = (1..5).map { Night(machineNow - (100 - it * 10) * hour, rates(200), whole = true) }
        machine.nights = old
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertEquals(old.last().firstMinuteEnd, store.read().resumeFromMs)
    }

    @Test
    fun `a record that fails without a time, at the end of the run that settles what the software owns, is older`() = runTest {
        machine.applicationIdentifier = uncompared
        val first = Night(machineNow - 60 * hour, rates(100), whole = true)
        val strange = Night(machineNow - 20 * hour, rates(100), kind = 2, whole = true)
        machine.nights = listOf(first, strange)
        machine.dropStart = strange.firstMinuteEnd
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
    }

    @Test
    fun `new software is vouched for only by a session it recorded itself`() = runTest {
        // A long night under the first software, ending an hour ago.
        val under = Night(machineNow - 8 * hour, rates(400))
        machine.nights = listOf(under)
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)

        // The machine is updated, and a short session follows. The long night is read again (it
        // is the newest but one), but it was recorded by the old software and proves nothing.
        phoneNow += 2 * hour
        machine.applicationIdentifier = "SW04600.19.8.8.0.0123abc"
        phoneNow += hour
        val short = Night(machineNow - 40 * 60_000L, rates(10, base = 60))
        machine.nights = listOf(under, short)
        assertEquals(SyncOutcome.PARTIAL, engine.sync()!!.outcome)
        assertHolds(listOf(under))
        assertNull(store.read().vouchedSoftware)

        // A night recorded after the update vouches, and the short session comes across with it.
        phoneNow += 12 * hour
        val night = Night(machineNow - 6 * hour, rates(300, base = 50))
        machine.nights = listOf(under, short, night)
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertHolds(listOf(under, short, night))
        assertEquals("SW04600.19.8.8.0.0123abc", store.read().vouchedSoftware)
    }

    @Test
    fun `one record that cannot be trusted costs itself and the short ones beside it, not the nights`() = runTest {
        val before = first
        val after = Night(machineNow - 5 * hour, rates(150, base = 50))
        val nap = Night(machineNow - 2 * hour, rates(12))
        // As a power cut might leave it: the rate has 80 minutes, the leak 50.
        machine.torn = machineNow - 20 * hour
        machine.nights = listOf(before, after, nap)
        val report = engine.sync()!!
        assertEquals(SyncOutcome.PARTIAL, report.outcome)
        // The long nights decoded cleanly, which shows the layout is read as it is meant: what
        // failed was the one record. So the nap beside them is believed too.
        assertHolds(listOf(before, after, nap))
        assertEquals(before.firstMinuteEnd, store.read().resumeFromMs)
    }

    @Test
    fun `short records beside one that cannot be trusted wait when nothing long vouches for the layout`() = runTest {
        val long = first
        machine.nights = listOf(long)
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        phoneNow += 72 * hour
        val nap = Night(machineNow - 3 * hour, rates(14))
        machine.nights = listOf(long, nap)
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        // Three days on, the download holds a torn record and two more naps, and nothing long:
        // the long night is days behind the place reading starts from.
        phoneNow += 72 * hour
        val one = Night(machineNow - 6 * hour, rates(12))
        val two = Night(machineNow - 2 * hour, rates(9, base = 60))
        machine.torn = machineNow - 4 * hour
        machine.nights = listOf(long, nap, one, two)
        assertEquals(SyncOutcome.PARTIAL, engine.sync()!!.outcome)
        assertHolds(listOf(long, nap))
        // The next night is long and clean, and answers the doubt for the naps read with it.
        phoneNow += 20 * hour
        val night = Night(machineNow - 8 * hour, rates(400, base = 48))
        machine.nights = listOf(long, nap, one, two, night)
        assertEquals(SyncOutcome.PARTIAL, engine.sync()!!.outcome)
        assertHolds(listOf(long, nap, one, two, night))
    }

    @Test
    fun `a record no rule was written for costs itself, and the nights after it are written`() = runTest {
        // Longer than any session can be: a week and a minute.
        val endless = Night(machineNow - 400 * hour, rates(SessionReadings.MAX_MINUTES + 1))
        val after = Night(machineNow - 5 * hour, rates(150, base = 50))
        machine.nights = listOf(endless, after)
        assertEquals(SyncOutcome.PARTIAL, engine.sync()!!.outcome)
        assertHolds(listOf(after))
    }

    @Test
    fun `a run that is always cut off still gets through a long history, a piece at a time`() = runTest {
        val nights = (1..12).map { Night(machineNow - (13 - it) * 24 * hour, rates(40 + it)) }
        machine.nights = nights
        machine.perRound = 2
        // The link carries two rounds and then goes quiet, every time.
        var allowed = 0
        val never = CompletableDeferred<Unit>()
        val stalling = object : MachineConnector by machine {
            override suspend fun <T> withSession(block: suspend (MachineSession) -> T): T = machine.withSession { session ->
                block(
                    object : MachineSession by session {
                        override suspend fun pull(log: SpoolName, from: SpoolAddress): SpoolRound {
                            if (allowed-- <= 0) never.await()
                            return session.pull(log, from)
                        }
                    },
                )
            }
        }
        val limited = SyncEngine(stalling, health, store, nowMs = { phoneNow }, limitMs = 60_000)
        var runs = 0
        do {
            allowed = 2
            phoneNow += hour
            val report = limited.sync()!!
            assertTrue(++runs < 12, "no progress: still ${health.records.size} records after $runs runs")
        } while (report.outcome != SyncOutcome.COMPLETE)
        assertHolds(nights)
    }

    @Test
    fun `writing everything again is done once the end is reached, whatever could not be read on the way`() = runTest {
        val good = first
        val broken = Night(machineNow - 20 * hour, rates(203), kind = 2)
        val after = Night(machineNow - 3 * hour, rates(101))
        machine.nights = listOf(good, broken, after)
        engine.sync()
        // Health Connect's data is cleared, and the user asks for everything again.
        health.records.clear()
        store.write(store.read().with(rewriteRequested = true).fromTheStart())
        phoneNow += hour
        val rewrite = engine.sync()!!
        assertEquals(SyncOutcome.PARTIAL, rewrite.outcome)
        assertEquals(123 + 101, rewrite.readingsWritten)
        assertHolds(listOf(good, after))
        assertTrue(!store.read().rewriteRequested)
        // The unreadable night is still asked for, but nothing is written twice for its sake.
        phoneNow += hour
        val next = engine.sync()!!
        assertEquals(SyncOutcome.PARTIAL, next.outcome)
        assertEquals(0, next.readingsWritten)
    }

    @Test
    fun `a night that could not be read while everything was written again is written when it can be`() = runTest {
        val good = first
        val night = Night(machineNow - 20 * hour, rates(203))
        val after = Night(machineNow - 3 * hour, rates(101))
        machine.nights = listOf(good, night, after)
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        // Health Connect's data is cleared and everything is asked for again, at a moment when
        // the middle night comes back unreadable.
        health.records.clear()
        store.write(store.read().with(rewriteRequested = true).fromTheStart())
        machine.nights = listOf(good, Night(night.firstMinuteEnd, night.rates, kind = 2), after)
        phoneNow += hour
        assertEquals(SyncOutcome.PARTIAL, engine.sync()!!.outcome)
        assertHolds(listOf(good, after))
        // Readable again. The ledger still knows it was to be rewritten, and it is.
        machine.nights = listOf(good, night, after)
        phoneNow += hour
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertHolds(listOf(good, night, after))
    }

    @Test
    fun `writing everything again gets through even when Health Connect takes only a little at a time`() = runTest {
        val nights = (1..9).map { Night(machineNow - (10 - it) * 24 * hour, rates(40 + it)) }
        machine.nights = nights
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        health.records.clear()
        store.write(store.read().with(rewriteRequested = true).fromTheStart())
        var runs = 0
        do {
            // Three sessions, and then it has had enough for now.
            health.applies = 0
            health.failOnApply = 4
            phoneNow += hour
            val report = engine.sync()!!
            assertTrue(++runs < 8, "no progress: ${health.records.size} records after $runs runs")
        } while (report.outcome != SyncOutcome.COMPLETE)
        assertHolds(nights)
    }

    @Test
    fun `nights waiting for the software to be vouched for are not given up for being old`() = runTest {
        // Forty nights are on the machine when its software is seen to have changed.
        val old = Night(machineNow - 60 * 24 * hour, rates(300))
        machine.nights = listOf(old)
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        phoneNow += 50 * 24 * hour
        machine.applicationIdentifier = "SW04600.19.8.8.0.0123abc"
        val unsynced = (1..40).map { Night(machineNow - (41 - it) * 24 * hour, rates(200 + it)) }
        machine.nights = listOf(old) + unsynced
        assertEquals(SyncOutcome.PARTIAL, engine.sync()!!.outcome)
        assertHolds(listOf(old))
        // The next night is recorded by the new software and vouches for it. All forty come across.
        phoneNow += 24 * hour
        val fresh = Night(machineNow - 9 * hour, rates(420, base = 50))
        machine.nights = listOf(old) + unsynced + fresh
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertHolds(listOf(old) + unsynced + fresh)
    }

    @Test
    fun `a record kept for another machine is not used for this one`() = runTest {
        // Another machine was synchronised, and its record outlived the forgetting of it.
        val other = SyncEngine(Machine().also { it.serialNumber = "23219999999"; it.pairedSerial = null; it.nights = listOf(second) }, health, store, nowMs = { phoneNow })
        assertEquals(SyncOutcome.COMPLETE, other.sync()!!.outcome)
        val others = health.records.keys.toSet()
        assertEquals(203, others.size)
        store.write(store.read().withLeakLimit(24))

        phoneNow += hour
        machine.nights = listOf(first)
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        val state = store.read()
        assertEquals(tag, state.machineTag)
        assertTrue(state.ledger.values.all { it.machineTag == tag })
        // The setting is the user's, not the machine's, and stays.
        assertEquals(24, state.leakLimitLitresPerMinute)
        // The other machine's nights stay in Health Connect, as forgetting it promises.
        assertTrue(health.records.keys.containsAll(others))
        assertEquals(others.size + 123, health.records.size)
    }

    @Test
    fun `after a reinstall, what the earlier installation left is cleared and written afresh`() = runTest {
        val leaky = Night(machineNow - 50 * hour, rates(123), leaks = IntArray(123) { if (it in 40..59) 30 else 2 })
        val other = second
        machine.nights = listOf(leaky, other)
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertHolds(listOf(leaky, other))
        // The app is removed and installed again: its record is gone, Health Connect's is not, and
        // what is there carries versions above anything this phone's clock would now give.
        File(directory, "sync.json").delete()
        for ((id, held) in health.records.toMap()) health.records[id] = health.Held(Long.MAX_VALUE - 5, held.rate + 1, held.time)

        // This time with the leak rule on, so some of what is there is no longer wanted at all.
        store.write(switchedOn.withLeakLimit(24))
        phoneNow += hour
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertHolds(listOf(leaky, other), leakLimit = 24)
        assertTrue(health.records.values.all { it.version <= phoneNow })
    }

    @Test
    fun `a run that does not finish in time is ended, and keeps what it had done`() = runTest {
        val never = CompletableDeferred<Unit>()
        val stalling = object : MachineConnector by machine {
            override suspend fun <T> withSession(block: suspend (MachineSession) -> T): T = machine.withSession { session ->
                block(
                    object : MachineSession by session {
                        // The second round never comes: the link is up and silent.
                        override suspend fun pull(log: SpoolName, from: SpoolAddress): SpoolRound {
                            if (from is SpoolAddress.Continuation) never.await()
                            return session.pull(log, from)
                        }
                    },
                )
            }
        }
        val nights = (1..4).map { Night(machineNow - (5 - it) * 24 * hour, rates(40 + it)) }
        machine.nights = nights
        machine.perRound = 2
        val limited = SyncEngine(stalling, health, store, nowMs = { phoneNow }, limitMs = 60_000)
        val report = limited.sync()!!
        assertEquals(SyncOutcome.PARTIAL, report.outcome)
        assertHolds(nights.take(2))
        assertNull(store.read().lastCompleteAtMs)
        // The next run, on a link that works, finishes the job.
        phoneNow += hour
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertHolds(nights)
    }

    @Test
    fun `a session that may still be going on waits until it has ended, and is then written once`() = runTest {
        val started = machineNow - 3 * hour
        val earlier = first
        // Its last minute was recorded two minutes ago: the mask is probably still on.
        machine.nights = listOf(earlier, Night(started, rates(178)))
        val during = engine.sync()!!
        assertEquals(SyncOutcome.COMPLETE, during.outcome)
        assertEquals(1, during.sessions)
        assertHolds(listOf(earlier))
        assertEquals(started, store.read().resumeFromMs)

        // An hour on it is 200 minutes long and ended forty minutes ago.
        phoneNow += hour
        val whole = Night(started, rates(200))
        machine.nights = listOf(earlier, whole)
        val report = engine.sync()!!
        assertEquals(SyncOutcome.COMPLETE, report.outcome)
        assertEquals(200, report.readingsWritten)
        assertHolds(listOf(earlier, whole))
    }

    @Test
    fun `nothing is written while the phone's own clock is known to be wrong`() = runTest {
        var wrong = true
        val careful = SyncEngine(machine, health, store, nowMs = { phoneNow }, phoneClockWrong = { wrong })
        machine.nights = listOf(first)
        assertEquals(SyncOutcome.PHONE_CLOCK_WRONG, careful.sync()!!.outcome)
        assertEquals(0, machine.connections)
        assertTrue(health.records.isEmpty())
        wrong = false
        assertEquals(SyncOutcome.COMPLETE, careful.sync()!!.outcome)
    }

    @Test
    fun `a machine that keeps promising more and sends nothing is not followed for ever`() = runTest {
        machine.emptyForEver = true
        val report = engine.sync()!!
        assertEquals(SyncOutcome.PARTIAL, report.outcome)
        assertEquals(SyncEngine.MAX_EMPTY_ROUNDS + 1, kept.size)
    }

    @Test
    fun `the record says a write is under way before Health Connect hears of it`() = runTest {
        machine.nights = listOf(first, second)
        val seen = ArrayList<Boolean>()
        val watching = object : HealthSink by health {
            override suspend fun apply(change: SessionChange) {
                // What is on disk at this moment is all that survives if the app dies here.
                val entry = store.read().ledger[change.intent.key]
                seen += entry?.pendingMinutes == change.intent.pendingMinutes && entry?.version == change.intent.version
                health.apply(change)
            }
        }
        assertEquals(SyncOutcome.COMPLETE, SyncEngine(machine, watching, store, nowMs = { phoneNow }).sync()!!.outcome)
        assertEquals(listOf(true, true), seen)
        assertTrue(store.read().ledger.values.all { it.pendingMinutes == null })
    }

    @Test
    fun `a run stopped from outside stops, says so to its caller, and leaves the record ready for the next`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val slow = object : HealthSink by health {
            override suspend fun apply(change: SessionChange) {
                health.apply(change)
                gate.await()
            }
        }
        machine.nights = listOf(first, second)
        val stopped = SyncEngine(machine, slow, store, nowMs = { phoneNow })
        val running = async { runCatching { stopped.sync() } }
        testScheduler.runCurrent()
        running.cancel()
        testScheduler.runCurrent()
        assertTrue(running.isCancelled)
        // No report of a run that did not end; the session it was in is marked as begun.
        val left = store.read()
        assertNull(left.lastReport)
        assertTrue(left.ledger.values.single().pendingMinutes != null)
        // The engine is free again, and the next run finishes the job.
        gate.complete(Unit)
        phoneNow += hour
        assertEquals(SyncOutcome.COMPLETE, stopped.sync()!!.outcome)
        assertHolds(machine.nights)
    }

    @Test
    fun `a change to the record waits for a run in progress`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val slow = object : MachineConnector by machine {
            override suspend fun <T> withSession(block: suspend (MachineSession) -> T): T {
                gate.await()
                return machine.withSession(block)
            }
        }
        val busy = SyncEngine(slow, health, store, nowMs = { phoneNow })
        machine.nights = listOf(first)
        val running = async { busy.sync() }
        testScheduler.runCurrent()
        var changed = false
        val change = async { busy.whileIdle { changed = true } }
        testScheduler.runCurrent()
        assertTrue(!changed)
        gate.complete(Unit)
        running.await()
        change.await()
        assertTrue(changed)
    }

    @Test
    fun `how much the machine's clock may wander depends on how long it has been`() = runTest {
        machine.nights = listOf(first)
        engine.sync()
        // Forty seconds in ten days is a clock that runs a little fast.
        phoneNow += 10 * 24 * hour
        machineAhead += 40_000
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        // Forty seconds in one day is a clock that somebody set.
        phoneNow += 24 * hour
        machineAhead += 40_000
        assertEquals(SyncOutcome.CLOCK_CHANGED, engine.sync()!!.outcome)
    }

    @Test
    fun `once a clock that was set back is accepted, the nights it put behind the resume point are found`() = runTest {
        val before = first
        machine.nights = listOf(before)
        engine.sync()
        // The machine's clock is set back two days, and a night is recorded under it. Its stamps
        // lie before the night already dealt with.
        phoneNow += 30 * hour
        machineAhead -= 48 * hour
        val under = Night(machineNow - 8 * hour, rates(95, base = 46))
        machine.nights = listOf(under, before).sortedBy { it.firstMinuteEnd }
        assertEquals(SyncOutcome.CLOCK_CHANGED, engine.sync()!!.outcome)
        // The user accepts the clock as it is now.
        store.write(store.read().with(clock = null).fromTheStart())
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertEquals(123 + 95, health.records.size)
        assertEquals(Instant.ofEpochMilli(under.firstMinuteEnd - machineAhead), health.records.getValue("rr-$tag-${under.firstMinuteEnd}").time)
    }

    @Test
    fun `when the rules for a reading change, every night is looked at again`() = runTest {
        val old = Night(machineNow - 20 * 24 * hour, rates(70))
        val recent = first
        machine.nights = listOf(old, recent)
        engine.sync()
        assertEquals(SessionReadings.RULES_VERSION, store.read().rulesVersion)
        // As an earlier version of the app would have left it: other rules, and the old night's
        // records lost from Health Connect and from the record.
        val state = store.read()
        store.write(state.with(rulesVersion = SessionReadings.RULES_VERSION - 1, ledger = state.ledger.filterValues { it.firstMinuteEnd.epochMs != old.firstMinuteEnd }))
        health.records.keys.removeAll { it.endsWith("-${old.firstMinuteEnd}") }
        phoneNow += hour
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertHolds(listOf(old, recent))
    }

    @Test
    fun `a machine's tag is the same every time, differs between machines, and is not its serial`() {
        assertEquals(tag, SyncEngine.machineTag(serial))
        assertTrue(Regex("m-[0-9a-f]{16}").matches(tag))
        assertTrue(SyncEngine.machineTag("23211234568") != tag)
        assertTrue(serial !in tag && serial.takeLast(4) !in tag)
    }

    @Test
    fun `a record of synchronising that cannot be read stops everything, and is left as it is`() = runTest {
        machine.nights = listOf(first)
        File(directory, "sync.json").writeText("{ not a state")
        val report = engine.sync()!!
        assertEquals(SyncOutcome.INTERNAL, report.outcome)
        assertEquals(0, machine.connections)
        assertTrue(health.records.isEmpty())
        assertEquals("{ not a state", File(directory, "sync.json").readText())
    }
}
