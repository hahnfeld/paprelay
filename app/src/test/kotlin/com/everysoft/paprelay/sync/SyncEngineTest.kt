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
    private val machineAhead = 361_600L
    private val machineNow get() = phoneNow + machineAhead

    /** A session on the machine: its first minute's stamp, and a rate and leak for each minute. */
    private class Night(val firstMinuteEnd: Long, val rates: IntArray, val leaks: IntArray = IntArray(rates.size) { 2 }, val kind: Long = 1)

    private fun Proto.night(night: Night) {
        message(5) {
            message(1) { varint(1, night.kind); varint(2, night.firstMinuteEnd); bytes(3, riceBlock(night.leaks, 4)) }
            message(3) { varint(1, night.kind); varint(2, night.firstMinuteEnd); bytes(3, riceBlock(pressure(night.rates.size), 2)) }
            message(4) { varint(1, night.kind); varint(2, night.firstMinuteEnd); bytes(3, riceBlock(night.rates, 8)) }
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
            val data = proto { for (night in page) night(night) }
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

    private val tag = SyncEngine.machineTag(serial)

    /** What Health Connect should hold for these nights with no leak above the limit: every minute, at its corrected time. */
    private fun expected(nights: List<Night>): Map<String, Pair<Double, Instant>> = nights.flatMap { night ->
        night.rates.indices.filter { night.leaks[it] * 1.2 <= 24 }.map { minute ->
            val stamp = night.firstMinuteEnd + minute * 60_000L
            "rr-$tag-$stamp" to (night.rates[minute] / 4.0 to Instant.ofEpochMilli(stamp - machineAhead))
        }
    }.toMap()

    private fun assertHolds(nights: List<Night>) {
        val want = expected(nights)
        assertEquals(want.keys, health.records.keys)
        for ((id, value) in want) {
            assertEquals(value.first, health.records.getValue(id).rate, id)
            assertEquals(value.second, health.records.getValue(id).time, id)
        }
    }

    private val first get() = Night(machineNow - 50 * hour, rates(123))
    private val second get() = Night(machineNow - 20 * hour, rates(202, base = 48))

    @Test
    fun `the first synchronisation writes every minute, at its corrected time, and the second writes nothing`() = runTest {
        val nights = listOf(first, second)
        machine.nights = nights
        val report = engine.sync()!!
        assertEquals(SyncOutcome.COMPLETE, report.outcome)
        assertEquals(2, report.sessions)
        assertEquals(325, report.readingsWritten)
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
        assertEquals(1, store.read().ledger.size)
        assertNull(store.read().lastCompleteAtMs)
        assertHolds(nights.take(1))

        health.failOnApply = null
        val next = engine.sync()!!
        assertEquals(SyncOutcome.COMPLETE, next.outcome)
        assertEquals(202 + 101, next.readingsWritten)
        assertHolds(nights)
    }

    @Test
    fun `a write that got half-way through a session is put right by the next run`() = runTest {
        val nights = listOf(first)
        machine.nights = nights
        health.failAfterWrites = 50
        assertEquals(SyncOutcome.HEALTH_CONNECT_FAILED, engine.sync()!!.outcome)
        assertEquals(50, health.records.size)
        assertTrue(store.read().ledger.isEmpty())

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
    fun `a record that cannot be read stops the run there, and nothing after it is written`() = runTest {
        val broken = Night(machineNow - 20 * hour, rates(202), kind = 2)
        val after = Night(machineNow - 3 * hour, rates(101))
        machine.nights = listOf(first, broken, after)
        val report = engine.sync()!!
        assertEquals(SyncOutcome.PARTIAL, report.outcome)
        assertEquals(1, report.sessions)
        assertHolds(listOf(first))
        // The place reached does not pass the gap: the next run reads from before it again.
        assertEquals(first.firstMinuteEnd, store.read().resumeFromMs)
        assertNull(store.read().lastCompleteAtMs)
    }

    @Test
    fun `minutes above the leak limit are left out, and changing the limit brings Health Connect into step`() = runTest {
        // Minutes 10 to 19 leak 36 L/min.
        val leaky = Night(machineNow - 5 * hour, rates(60), leaks = IntArray(60) { if (it in 10..19) 30 else 2 })
        machine.nights = listOf(leaky)
        engine.sync()
        assertEquals(50, health.records.size)
        assertHolds(listOf(leaky))

        // With the limit off, the ten come back; with it on again, they go.
        store.write(store.read().with(leakLimitLitresPerMinute = null))
        phoneNow += 1_000
        assertEquals(60, engine.sync()!!.readingsWritten)
        assertEquals(60, health.records.size)
        store.write(store.read().with(leakLimitLitresPerMinute = 24))
        phoneNow += 1_000
        engine.sync()
        assertEquals(50, health.records.size)
        assertHolds(listOf(leaky))
    }

    @Test
    fun `nothing is written for another machine, unconfirmed software, or a clock that cannot be read`() = runTest {
        machine.nights = listOf(first)
        machine.serialNumber = "23219999999"
        assertEquals(SyncOutcome.WRONG_MACHINE, engine.sync()!!.outcome)
        machine.serialNumber = serial

        machine.applicationIdentifier = "SW04600.20.1.0.0.abcdef"
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
        // None of these got as far as a session: the one connection is the first run's.
        assertEquals(1, machine.connections)
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
