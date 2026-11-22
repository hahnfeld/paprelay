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

/** The breathing events: from the machine's event log to the phone, each once, in true time where that is known. */
class EventSyncTest {
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

        override fun notReady(): SyncOutcome? = null

        override fun pairedSerial() = serial

        override suspend fun <T> withSession(block: suspend (MachineSession) -> T): T = block(this)

        override suspend fun identity() = MachineIdentity(serial, applicationIdentifier)

        override suspend fun clock() = MachineClockReading(machineEpochMs = machineNow + 20, sentAtMs = phoneNow, roundTripMs = 40)

        override suspend fun pull(log: SpoolName, from: SpoolAddress): SpoolRound {
            if (log == SpoolName.SUMMARY) return SpoolRound(summary(), ByteArray(32), null)
            assertEquals(SpoolName.RESPIRATORY_EVENTS, log)
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

    @Test
    fun `the events of the confirmed kinds reach the phone, in true time beside the machine's, each under its night`() = runTest {
        machine.events = lastNight()
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        val events = eventStore.read()
        assertEquals(listOf(BreathingEventKind.OBSTRUCTIVE_APNEA, BreathingEventKind.HYPOPNEA, BreathingEventKind.CENTRAL_APNEA), events.all.map { it.kind })
        val start = noon(first.plusDays(5))
        assertEquals(start + 40_000_000, events.all[0].machineTime.epochMs)
        // The machine is about six minutes fast; the true moment is that much earlier.
        assertEquals(start + 40_000_000 - ahead, events.all[0].correctedMs)
        assertEquals(10_500, events.all[0].durationMs)
        assertNull(events.all[1].durationMs)
        // Asked for from as far back as the machine might hold.
        assertEquals(machineNow + 20 - SyncEngine.FIRST_WINDOW_MS, machine.asked.single())
        // Each belongs to the night of the 15th, and that night's events come newest first.
        val night = nightStore.read().byDate.getValue(first.plusDays(5)).night
        assertEquals(events.all.reversed(), events.of(night))
        assertTrue(events.of(nightStore.read().byDate.getValue(first.plusDays(4)).night).isEmpty())
        val csv = EventReport.csv(events.all, nightStore.read().all).lines()
        assertTrue(csv.drop(1).filter { it.isNotEmpty() }.all { it.startsWith("2026-11-15,") })
        // The session of each night is corrected by the same measurement.
        assertEquals(night.sessionList!!.single().start.epochMs - ahead, night.sessionList!!.single().correctedMs)
        // No health value and no serial number in the file's printed form or its name.
        assertFalse(eventFile.readText().contains("2321"))
    }

    @Test
    fun `two runs over the same log give the same file to the byte, with nothing twice`() = runTest {
        machine.events = lastNight()
        engine.sync()
        val once = eventFile.readBytes()
        // The next run reads the last day again. The clock has drifted a little since: what was given stands.
        phoneNow += hour
        ahead += 1_500
        machine.asked.clear()
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertEquals(lastNight().filter { it.code != 6L }.maxOf { it.at } - SyncEngine.OVERLAP_MS, machine.asked.single())
        assertTrue(once.contentEquals(eventFile.readBytes()))
        assertEquals(3, eventStore.read().all.size)
        // The nights' corrected session starts stand in the same way.
        val sessions = nightStore.read().byDate.values.map { it.night.sessionList }
        phoneNow += hour
        ahead += 1_500
        engine.sync()
        assertEquals(sessions, nightStore.read().byDate.values.map { it.night.sessionList })
    }

    @Test
    fun `a new event in the overlap is added once, and the events already kept keep their place`() = runTest {
        machine.events = lastNight()
        engine.sync()
        phoneNow += hour
        machine.events = lastNight() + Stored(4, noon(first.plusDays(5)) + 43_000_000, 15_000)
        engine.sync()
        phoneNow += hour
        engine.sync()
        val all = eventStore.read().all
        assertEquals(4, all.size)
        assertEquals(all.size, all.map { it.identity }.toSet().size)
    }

    @Test
    fun `in a run that finds the machine's clock set since the last, the events it copies keep the machine's time alone`() = runTest {
        machine.events = lastNight()
        engine.sync()
        // The clock is set ten minutes forward, and an event is recorded.
        phoneNow += hour
        ahead += 600_000
        val later = noon(first.plusDays(5)) + 44_000_000 + 600_000
        machine.events = lastNight() + Stored(2, later)
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        val stored = eventStore.read().all
        assertNull(stored.single { it.machineTime.epochMs == later }.correctedMs)
        // What was corrected before stands.
        assertTrue(stored.filter { it.machineTime.epochMs != later }.all { it.correctedMs != null })
        // The next run measures against the new setting, and corrects again.
        phoneNow += hour
        machine.events = machine.events + Stored(2, later + hour)
        engine.sync()
        assertEquals(later + hour - ahead, eventStore.read().all.single { it.machineTime.epochMs == later + hour }.correctedMs)
    }

    @Test
    fun `an event older than the correction window keeps the machine's time alone`() = runTest {
        val old = machineNow - ClockCorrection.WINDOW_MS - hour
        machine.events = listOf(Stored(4, old, 10_000)) + lastNight()
        engine.sync()
        // It is older than every night kept here, so it is not kept either.
        assertTrue(eventStore.read().all.none { it.machineTime.epochMs == old })
        // Kept when there are no nights to bound it.
        val bare = SyncEngine(machine, NoMinutes, SyncStore(File(directory, "bare.json")), nowMs = { phoneNow }, events = EventStore(File(directory, "bare-events.json")))
        bare.sync()
        assertNull(EventStore(File(directory, "bare-events.json")).read().all.single { it.machineTime.epochMs == old }.correctedMs)
    }

    @Test
    fun `nothing is copied from software of another line`() = runTest {
        machine.events = lastNight()
        machine.applicationIdentifier = "SW04700.20.1.0.0.abcdef"
        assertEquals(SyncOutcome.SOFTWARE_NOT_CONFIRMED, engine.sync()!!.outcome)
        assertFalse(eventFile.exists())
        assertEquals(0, machine.pulls)
    }

    @Test
    fun `an event that cannot be read leaves the run short, and the next run reads from before it again`() = runTest {
        val start = noon(first.plusDays(5))
        machine.events = listOf(Stored(4, start + 30_000_000, 10_000), Stored(2, start + 31_000_000, broken = true), Stored(3, start + 32_000_000, 12_000))
        assertEquals(SyncOutcome.PARTIAL, engine.sync()!!.outcome)
        // The events either side are kept.
        assertEquals(2, eventStore.read().all.size)
        assertEquals(start + 30_000_000, eventStore.read().resumeFromMs)
        phoneNow += hour
        machine.asked.clear()
        machine.events = listOf(Stored(4, start + 30_000_000, 10_000), Stored(2, start + 31_000_000), Stored(3, start + 32_000_000, 12_000))
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertEquals(start + 30_000_000 - SyncEngine.OVERLAP_MS, machine.asked.single())
        assertEquals(3, eventStore.read().all.size)
        assertEquals(start + 32_000_000, eventStore.read().resumeFromMs)
    }

    @Test
    fun `a long history is read over several runs, a bounded part at a time, with nothing twice`() = runTest {
        val start = noon(first)
        machine.events = (0 until 60L).map { Stored(2, start + it * 2 * hour) }
        machine.perRound = 1
        assertEquals(SyncOutcome.PARTIAL, engine.sync()!!.outcome)
        assertEquals(SyncEngine.MAX_EVENT_ROUNDS, eventStore.read().all.size)
        phoneNow += hour
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertEquals(60, eventStore.read().all.size)
    }

    @Test
    fun `a file of events that cannot be read is put aside and built again, and another machine's are not kept`() = runTest {
        machine.events = lastNight()
        eventFile.writeText("{ not events")
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertEquals(3, eventStore.read().all.size)
        assertEquals("{ not events", File(directory, "events.json.damaged").readText())
        // Another machine's file: started afresh for this one.
        eventStore.write(Events("m-0000000000000000", eventStore.read().all + eventStore.read().all, 5))
        phoneNow += hour
        machine.asked.clear()
        engine.sync()
        assertEquals(machineNow + 20 - SyncEngine.FIRST_WINDOW_MS, machine.asked.single())
        assertEquals(SyncEngine.machineTag(serial), eventStore.read().machineTag)
        eventStore.delete()
        assertTrue(directory.listFiles()!!.none { it.name.startsWith("events.json") })
    }

    @Test
    fun `events kept before a kind was confirmed are read again from the start, once, and the new kind is found in the nights before`() = runTest {
        val start = noon(first.plusDays(1))
        // A store from a build that passed on three kinds, with the place it had reached.
        machine.events = listOf(Stored(4, start + 30_000_000, 10_000), Stored(5, start + 31_000_000, 14_000)) + lastNight()
        engine.sync()
        val kept = eventStore.read()
        eventStore.write(Events(kept.machineTag, kept.all.filter { it.kind != BreathingEventKind.UNCLASSIFIED_APNEA }, kept.resumeFromMs, "central_apnea,hypopnea,obstructive_apnea"))
        phoneNow += hour
        machine.asked.clear()
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        // From as far back as the machine might hold, and the unclassified apnoea of the earlier night is there.
        assertEquals(machineNow + 20 - SyncEngine.FIRST_WINDOW_MS, machine.asked.single())
        val all = eventStore.read()
        assertEquals(1, all.all.count { it.kind == BreathingEventKind.UNCLASSIFIED_APNEA })
        assertEquals(all.all.size, all.all.map { it.identity }.toSet().size)
        assertEquals(com.everysoft.paprelay.as11.plan.EventPlan.VERSION, all.kindsVersion)
        // Once: then from where it reached.
        phoneNow += hour
        machine.asked.clear()
        engine.sync()
        assertEquals(lastNight().filter { it.code != 6L }.maxOf { it.at } - SyncEngine.OVERLAP_MS, machine.asked.single())
    }
}
