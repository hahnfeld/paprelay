// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.sync

import com.everysoft.paprelay.as11.MachineClockReading
import com.everysoft.paprelay.as11.SpoolAddress
import com.everysoft.paprelay.as11.SpoolName
import com.everysoft.paprelay.as11.SpoolRound
import com.everysoft.paprelay.as11.decode.Proto
import com.everysoft.paprelay.as11.decode.proto
import com.everysoft.paprelay.as11.plan.ClockCorrection
import com.everysoft.paprelay.core.nights.BreathingEventKind
import com.everysoft.paprelay.core.nights.EventReport
import com.everysoft.paprelay.core.sync.SessionChange
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The breathing events beside the rest of a run: what a failure of theirs, or a clock set earlier, does and does not change. */
class EventRunTest {
    private val serial = "23211234567"
    private val software = "SW04600.19.8.8.0.dced143ae"
    private val day = 86_400_000L
    private val hour = 3_600_000L
    private val first = LocalDate.of(2026, 11, 10)

    private fun noon(date: LocalDate) = date.atTime(19, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    /** The phone's clock; the machine's runs [ahead] of it. */
    private var phoneNow = noon(LocalDate.of(2026, 11, 16)) + 5 * hour
    private var ahead = 361_600L
    private val machineNow get() = phoneNow + ahead

    /** An event on the machine: its code, its stamp, its length; [broken] gives it two stamps that differ. */
    private class Stored(val code: Long, val at: Long, val durationMs: Long? = null, val broken: Boolean = false)

    private inner class Machine : MachineConnector, MachineSession {
        var applicationIdentifier = software
        var events = listOf<Stored>()
        var perRound = 100
        val asked = ArrayList<Long>()
        var pulls = 0
        var minutePulls = 0

        /** The per-minute log cannot be decoded at all; or the link is lost when it is asked for. */
        var minuteGarbage = false
        var minuteLinkLost = false
        var eventsBroken = false

        /** The events log fails after this many of its rounds, as a link lost in the middle would. */
        var eventsFailAfter: Int? = null
        var eventsGarbage = false

        override fun notReady(): SyncOutcome? = null

        override fun pairedSerial() = serial

        override suspend fun <T> withSession(block: suspend (MachineSession) -> T): T = block(this)

        override suspend fun identity() = MachineIdentity(serial, applicationIdentifier)

        override suspend fun clock() = MachineClockReading(machineEpochMs = machineNow + 20, sentAtMs = phoneNow, roundTripMs = 40)

        override suspend fun pull(log: SpoolName, from: SpoolAddress): SpoolRound {
            if (log == SpoolName.SUMMARY) return SpoolRound(summary(), ByteArray(32), null)
            if (log == SpoolName.ONE_MINUTE) {
                minutePulls++
                if (minuteLinkLost) throw com.everysoft.paprelay.as11.As11TransportException("link lost")
                return SpoolRound(if (minuteGarbage) byteArrayOf(0x0a, 0x7f, 0x01) else ByteArray(0), ByteArray(32), null)
            }
            if (eventsBroken) throw com.everysoft.paprelay.as11.As11TransportException("events round failed")
            if (eventsGarbage) return SpoolRound(byteArrayOf(0x0a, 0x7f, 0x01), ByteArray(32), null)
            assertEquals(SpoolName.RESPIRATORY_EVENTS, log)
            eventsFailAfter?.let { if (pulls >= it) throw com.everysoft.paprelay.as11.As11TransportException("link lost") }
            pulls++
            val (wanted, offset) = when (from) {
                is SpoolAddress.FromMachineTime -> {
                    asked += from.machineEpochMs
                    events.filter { it.at >= from.machineEpochMs && it.at <= machineNow } to 0
                }
                is SpoolAddress.Continuation -> events.filter { it.at >= from.next.getValue("since").toString().toLong() && it.at <= machineNow } to
                    from.next.getValue("at").toString().toInt()
            }
            val page = wanted.drop(offset).take(perRound)
            val next = if (offset + page.size < wanted.size) {
                JsonObject(mapOf("at" to JsonPrimitive(offset + page.size), "since" to JsonPrimitive(wanted.first().at)))
            } else {
                null
            }
            val data = if (page.isEmpty()) {
                ByteArray(0)
            } else {
                proto {
                    message(4) {
                        message(1) {
                            for (event in page) {
                                message(1) {
                                    varint(1, event.code); varint(2, event.at); varint(3, if (event.broken) event.at + 1 else event.at)
                                    event.durationMs?.let { varint(4, it) }
                                }
                            }
                        }
                    }
                }
            }
            return SpoolRound(data, ByteArray(32), next)
        }

        /** Six finished nights with one session each, so that the events have nights to belong to. */
        fun summary(): ByteArray = proto {
            for (index in 0..5L) {
                val start = noon(first.plusDays(index))
                if (start + day > machineNow) continue
                message(2) {
                    varint(1, 1); varint(2, start); varint(3, start + day); zigzag(4, -420); varint(5, 400)
                    message(6) { message(1) { varint(1, start + 36_000_000); varint(2, 400) } }
                    varint(7, 100); varint(39, 1); varint(40, start + 80_000_000)
                }
            }
        }
    }

    private object NoMinutes : HealthSink {
        override suspend fun notReady(): SyncOutcome? = SyncOutcome.HEALTH_CONNECT_UNAVAILABLE

        override suspend fun apply(change: SessionChange) = error("respiratory rate is switched off")
    }

    private val machine = Machine()
    private val directory: File = Files.createTempDirectory("paprelay-events").toFile()
    private val store = SyncStore(File(directory, "sync.json"))
    private val nightStore = NightStore(File(directory, "nights.json"))
    private val eventFile = File(directory, "events.json")
    private val eventStore = EventStore(eventFile)
    private val engine = SyncEngine(machine, NoMinutes, store, nowMs = { phoneNow }, nights = nightStore, events = eventStore)

    /** Some events of the night of the 15th, and one of a kind never seen in a capture. */
    private fun lastNight(): List<Stored> {
        val start = noon(first.plusDays(5))
        return listOf(
            Stored(4, start + 40_000_000, 10_500),
            Stored(2, start + 41_000_000),
            Stored(6, start + 41_500_000), // RERA: not passed on
            Stored(3, start + 42_000_000, 12_000),
        )
    }


    private val tag = SyncEngine.machineTag(serial)

    /**
     * An installation that kept respiratory rate's clock baseline and not yet the correction's with respiratory rate on: sync.json has the respiratory-rate clock baseline
     * but no correctionClock. The machine's clock was set 10 minutes forward since that baseline. The
     * engine itself sees the step (clockChanged) but ClockCorrection does not, because
     * correctionClock is null: the first 0.3.0 run corrects every event and session of the last
     * week with the post-step offset.
     */
    @Test
    fun `a clock set since respiratory rate last measured it corrects nothing, though the correction has no baseline of its own yet`() = runTest {
        val oldOffset = -(ahead - 600_000) // the machine was 10 minutes less fast before it was set
        store.write(SyncState(tag, emptyMap(), null, null, null, null, ClockBaseline(oldOffset, machineNow - 2 * day), false, rulesVersion = com.everysoft.paprelay.core.sync.SessionReadings.RULES_VERSION, softwareSeen = software, respiratoryRate = true))
        machine.events = lastNight()
        engine.sync()
        val events = eventStore.read().all
        assertEquals(3, events.size)
        // Desired: no correction in a run where the clock is known to have been stepped.
        assertTrue(events.all { it.correctedMs == null }, "corrected across a known clock step: " + events.map { (it.correctedMs ?: 0) - it.machineTime.epochMs })
    }

    /** Respiratory rate is read before the events, and a failure of the events log keeps nothing else from the phone. */
    @Test
    fun `a failing events log does not stop respiratory rate, and the run says the connection was lost`() = runTest {
        val sink = object : HealthSink {
            override suspend fun notReady(): SyncOutcome? = null
            override suspend fun apply(change: SessionChange) = Unit
        }
        store.write(SyncState(null, emptyMap(), null, null, null, null, null, false, respiratoryRate = true))
        val rrEngine = SyncEngine(machine, sink, store, nowMs = { phoneNow }, nights = nightStore, events = eventStore)
        machine.eventsBroken = true
        val outcome = rrEngine.sync()!!.outcome
        assertTrue(machine.minutePulls > 0, "respiratory rate never read: outcome $outcome")
        assertEquals(SyncOutcome.CONNECTION_LOST, outcome)
        assertTrue(nightStore.read().byDate.isNotEmpty())
    }

    /** A first events download that cannot be decoded at all does not move the place to resume from: nothing was read. */
    @Test
    fun `an undecodable first events download does not give up the older history`() = runTest {
        val start = noon(first)
        machine.events = listOf(Stored(4, start + 30_000_000, 10_000))
        machine.eventsGarbage = true
        assertEquals(SyncOutcome.PARTIAL, engine.sync()!!.outcome)
        assertNull(eventStore.read().resumeFromMs, "resume moved although nothing was read")
        // Readable again: the whole history is asked for.
        machine.eventsGarbage = false
        phoneNow += hour
        machine.asked.clear()
        engine.sync()
        assertEquals(machineNow + 20 - SyncEngine.FIRST_WINDOW_MS, machine.asked.single())
        assertEquals(1, eventStore.read().all.size)
    }

    @Test
    fun `what a run read of the events before the link was lost is kept, and the next run goes on from there`() = runTest {
        val start = noon(first.plusDays(2))
        machine.events = (0 until 6L).map { Stored(2, start + it * hour) }
        machine.perRound = 1
        machine.eventsFailAfter = 3
        assertEquals(SyncOutcome.CONNECTION_LOST, engine.sync()!!.outcome)
        // Each round was saved as it came: three events, and the place after them.
        assertEquals(3, eventStore.read().all.size)
        assertEquals(start + 2 * hour, eventStore.read().resumeFromMs)
        machine.eventsFailAfter = null
        phoneNow += hour
        machine.asked.clear()
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertEquals(start + 2 * hour - SyncEngine.OVERLAP_MS, machine.asked.single())
        assertEquals(6, eventStore.read().all.size)
    }

    @Test
    fun `beyond the bound on events kept, the oldest go and the newest stay`() = runTest {
        // No nights to bound them by; the store already at its bound, with events older than any on the machine.
        val oldest = noon(first) - 400 * day
        val kept = (0 until EventStore.MAX_EVENTS.toLong()).map {
            com.everysoft.paprelay.core.nights.BreathingEvent(BreathingEventKind.HYPOPNEA, com.everysoft.paprelay.core.MachineTime(oldest + it * 1_000), null, null)
        }
        val bare = EventStore(File(directory, "bound.json"))
        bare.write(Events(tag, kept, noon(first)))
        val engine = SyncEngine(machine, NoMinutes, SyncStore(File(directory, "bound-sync.json")), nowMs = { phoneNow }, events = bare)
        machine.events = lastNight()
        engine.sync()
        val all = bare.read().all
        assertEquals(EventStore.MAX_EVENTS, all.size)
        // The three new ones are there; the three oldest are not.
        assertEquals(lastNight().filter { it.code != 6L }.map { it.at }, all.takeLast(3).map { it.machineTime.epochMs })
        assertEquals(oldest + 3_000, all.first().machineTime.epochMs)
    }

    @Test
    fun `a clock change still waiting on the user corrects nothing, though the last run measured the clock as it is now`() = runTest {
        val oldOffset = -(ahead - 600_000)
        // Respiratory rate's baseline shows the step; the correction's own, from the last run, does not.
        store.write(
            SyncState(tag, emptyMap(), null, null, null, null, ClockBaseline(oldOffset, machineNow - 2 * day), false, rulesVersion = com.everysoft.paprelay.core.sync.SessionReadings.RULES_VERSION, softwareSeen = software, respiratoryRate = true)
                .with(correctionClock = ClockBaseline(-ahead - 20, machineNow - hour)),
        )
        machine.events = lastNight()
        engine.sync()
        assertTrue(eventStore.read().all.isNotEmpty())
        assertTrue(eventStore.read().all.all { it.correctedMs == null })
        // Once the user accepts the clock, the next run corrects again.
        store.write(store.read().clockAccepted())
        phoneNow += hour
        machine.events = lastNight() + Stored(2, noon(first.plusDays(5)) + 50_000_000)
        engine.sync()
        assertEquals(noon(first.plusDays(5)) + 50_000_000 - ahead, eventStore.read().all.single { it.machineTime.epochMs == noon(first.plusDays(5)) + 50_000_000 }.correctedMs)
    }

    @Test
    fun `a clock set since the last run corrects nothing in this one, with respiratory rate never switched on`() = runTest {
        store.write(SyncState.EMPTY.with(machineTag = tag, correctionClock = ClockBaseline(-(ahead - 600_000), machineNow - hour)))
        machine.events = lastNight()
        engine.sync()
        assertTrue(eventStore.read().all.all { it.correctedMs == null })
        assertTrue(nightStore.read().all.mapNotNull { it.sessionList }.flatten().all { it.correctedMs == null })
    }

    private val rateSink = object : HealthSink {
        override suspend fun notReady(): SyncOutcome? = null

        override suspend fun apply(change: SessionChange) = Unit
    }

    @Test
    fun `a per-minute log that cannot be decoded does not stop the breathing events`() = runTest {
        store.write(SyncState(null, emptyMap(), null, null, null, null, null, false, respiratoryRate = true))
        val rrEngine = SyncEngine(machine, rateSink, store, nowMs = { phoneNow }, nights = nightStore, events = eventStore)
        machine.events = lastNight()
        machine.minuteGarbage = true
        assertEquals(SyncOutcome.DATA_NOT_UNDERSTOOD, rrEngine.sync()!!.outcome)
        assertTrue(machine.pulls > 0)
        assertEquals(3, eventStore.read().all.size)
    }

    @Test
    fun `respiratory rate stopped by Health Connect does not stop the breathing events`() = runTest {
        // NoMinutes says Health Connect is not there; respiratory rate is switched on.
        store.write(SyncState(null, emptyMap(), null, null, null, null, null, false, respiratoryRate = true))
        machine.events = lastNight()
        assertEquals(SyncOutcome.HEALTH_CONNECT_UNAVAILABLE, engine.sync()!!.outcome)
        assertEquals(3, eventStore.read().all.size)
    }

    @Test
    fun `a run that lost the link before the events leaves the correction's baseline where it was`() = runTest {
        machine.events = lastNight()
        engine.sync()
        val before = store.read().correctionClock!!
        store.write(store.read().with(respiratoryRate = true))
        val rrEngine = SyncEngine(machine, rateSink, store, nowMs = { phoneNow }, nights = nightStore, events = eventStore)
        phoneNow += hour
        machine.minuteLinkLost = true
        val pulls = machine.pulls
        assertEquals(SyncOutcome.CONNECTION_LOST, rrEngine.sync()!!.outcome)
        // The events were not read, so the clock's baseline for them did not move.
        assertEquals(pulls, machine.pulls)
        assertEquals(before.machineMs, store.read().correctionClock!!.machineMs)
        assertEquals(before.offsetMs, store.read().correctionClock!!.offsetMs)
    }

    @Test
    fun `with respiratory rate off, its old clock baseline does not hold the correction back`() = runTest {
        // A baseline from when respiratory rate was last on, long ago and before the clock was set; the
        // correction's own baseline, from the last run, agrees with the clock now.
        store.write(
            SyncState(tag, emptyMap(), null, null, null, null, ClockBaseline(-(ahead - 600_000), machineNow - 60 * day), false, softwareSeen = software)
                .with(correctionClock = ClockBaseline(-ahead - 20, machineNow - hour)),
        )
        machine.events = lastNight()
        engine.sync()
        assertTrue(eventStore.read().all.isNotEmpty())
        assertTrue(eventStore.read().all.all { it.correctedMs != null })
    }

    @Test
    fun `a re-reading for a new set of kinds that takes several runs is taken up where it reached, and finishes once`() = runTest {
        val start = noon(first)
        machine.events = (0 until 60L).map { Stored(if (it % 3 == 0L) 5 else 4, start + it * 2 * hour, 10_000) }
        engine.sync()
        phoneNow += hour
        engine.sync()
        val kept = eventStore.read()
        assertEquals(60, kept.all.size)
        eventStore.write(Events(kept.machineTag, kept.all.filter { it.kind != BreathingEventKind.UNCLASSIFIED_APNEA }, kept.resumeFromMs, "central_apnea,hypopnea,obstructive_apnea"))
        machine.perRound = 1
        val asked = ArrayList<Long>()
        repeat(4) {
            phoneNow += hour
            machine.asked.clear()
            engine.sync()
            asked += machine.asked
        }
        val back = eventStore.read()
        assertEquals(60, back.all.size)
        assertEquals(back.all.size, back.all.map { it.identity }.toSet().size)
        assertEquals(com.everysoft.paprelay.as11.plan.EventPlan.VERSION, back.kindsVersion)
        assertEquals(1, asked.count { it < start - day }, "the 400-day read was begun more than once")
    }
}
