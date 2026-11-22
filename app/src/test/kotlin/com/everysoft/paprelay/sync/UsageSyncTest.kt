// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.sync

import com.everysoft.paprelay.as11.MachineClockReading
import com.everysoft.paprelay.as11.SpoolAddress
import com.everysoft.paprelay.as11.SpoolName
import com.everysoft.paprelay.as11.SpoolRound
import com.everysoft.paprelay.as11.decode.UsageEvent
import com.everysoft.paprelay.as11.decode.proto
import com.everysoft.paprelay.as11.plan.NightPlan
import com.everysoft.paprelay.core.nights.FhirResource
import com.everysoft.paprelay.core.nights.NightPreview
import com.everysoft.paprelay.core.nights.NightReport
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The morning preview: the usage log read for it, kept apart, and never passed on. */
class UsageSyncTest {
    private val serial = "23211234567"
    private val software = "SW04600.19.8.8.0.dced143ae"
    private val day = 86_400_000L
    private val hour = 3_600_000L
    private val first = LocalDate.of(2026, 11, 10)
    private val both = setOf(UsageEvent.MASK_ON, UsageEvent.MASK_OFF)

    private fun noon(date: LocalDate) = date.atTime(19, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    /** Half past seven in the morning of the 16th by the machine's clock: the night of the 15th has no summary yet. */
    private var phoneNow = noon(LocalDate.of(2026, 11, 16)) - 4 * hour - 30 * 60_000 - 361_600
    private var ahead = 361_600L
    private val machineNow get() = phoneNow + ahead

    /** The night of the 15th: mask on, off, and on again (still on). */
    private val tonight = noon(LocalDate.of(2026, 11, 15))
    private fun nightMarks() = listOf(3L to tonight + 11 * hour, 2L to tonight + 17 * hour, 3L to tonight + 17 * hour + 30 * 60_000)

    private inner class Machine : MachineConnector, MachineSession {
        var marks = listOf<Pair<Long, Long>>()
        var events = listOf<Pair<Long, Long>>()
        var usageLinkLost = false
        var usageGarbage = false

        /** A usage record whose two stamps differ, so that it cannot be read. */
        var broken: Long? = null
        val usageAsked = ArrayList<Long>()
        var usagePulls = 0

        override fun notReady(): SyncOutcome? = null

        override fun pairedSerial() = serial

        override suspend fun <T> withSession(block: suspend (MachineSession) -> T): T = block(this)

        override suspend fun identity() = MachineIdentity(serial, software)

        override suspend fun clock() = MachineClockReading(machineEpochMs = machineNow + 20, sentAtMs = phoneNow, roundTripMs = 40)

        override suspend fun pull(log: SpoolName, from: SpoolAddress): SpoolRound = when (log) {
            SpoolName.SUMMARY -> SpoolRound(summary(), ByteArray(32), null)
            SpoolName.RESPIRATORY_EVENTS -> SpoolRound(eventLog(events.filter { it.second <= machineNow }, 4), ByteArray(32), null)
            SpoolName.USAGE_EVENTS -> {
                usagePulls++
                if (usageLinkLost) throw com.everysoft.paprelay.as11.As11TransportException("link lost")
                if (usageGarbage) {
                    SpoolRound(byteArrayOf(0x0a, 0x7f, 0x01), ByteArray(32), null)
                } else {
                    val start = (from as SpoolAddress.FromMachineTime).machineEpochMs
                    usageAsked += start
                    SpoolRound(eventLog(marks.filter { it.second in start..machineNow }, 6), ByteArray(32), null)
                }
            }
            else -> error("not read: $log")
        }

        private fun eventLog(entries: List<Pair<Long, Long>>, envelope: Int): ByteArray =
            if (entries.isEmpty()) ByteArray(0) else proto { message(envelope) { message(1) { for ((code, at) in entries) message(1) { varint(1, code); varint(2, at); varint(3, if (envelope == 6 && at == broken) at + 1 else at); if (envelope == 4 && code != 2L) varint(4, 10_000) } } } }

        /** Finished nights up to the 14th; the 15th's day has not ended. */
        fun summary(): ByteArray = proto {
            for (index in 0..6L) {
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

    private inner class Records : NightRecords {
        val held = HashMap<String, String>()

        override suspend fun state() = RecordsState.READY

        override suspend fun open() = false

        override suspend fun write(resources: List<FhirResource>, removeIds: List<String>) {
            for (id in removeIds) held.remove(id)
            for (resource in resources) held[resource.id] = resource.json
        }

        override suspend fun writePatient(patient: FhirResource?) = error("no details are given here")
    }

    private val machine = Machine()
    private val records = Records()
    private val directory: File = Files.createTempDirectory("paprelay-usage").toFile()
    private val store = SyncStore(File(directory, "sync.json"))
    private val nightStore = NightStore(File(directory, "nights.json"))
    private val eventStore = EventStore(File(directory, "events.json"))
    private val usageStore = UsageStore(File(directory, "usage.json"))

    private fun engine(kinds: Set<UsageEvent> = both) =
        SyncEngine(machine, NoMinutes, store, nowMs = { phoneNow }, nights = nightStore, records = records, events = eventStore, usage = usageStore, usageKinds = kinds)

    private fun preview(): NightPreview? = usageStore.read().preview(nightStore.read().all)

    @Test
    fun `the night without a summary yet is previewed from mask on and off, a session still running counted up to the last read`() = runTest {
        machine.marks = nightMarks()
        machine.events = listOf(4L to tonight + 12 * hour, 2L to tonight + 13 * hour)
        assertEquals(SyncOutcome.COMPLETE, engine().sync()!!.outcome)
        val preview = assertNotNull(preview())
        assertEquals(LocalDate.of(2026, 11, 15), preview.date)
        assertTrue(preview.running)
        // Six hours, then from 05:30 to the machine's clock when it was read (07:30 and the 20 ms of the reading).
        assertEquals(6 * 60 + 120, preview.usageMinutes)
        // The night's events are kept, as all events are.
        assertEquals(2, eventStore.read().between(preview.startMs, preview.endMs).size)
        // Corrected by the same measurement as everything else.
        assertEquals(tonight + 11 * hour - ahead, preview.sessions.first().correctedStartMs)
        // Read from two days back the first time.
        assertEquals(machineNow + 20 - SyncEngine.USAGE_WINDOW_MS, machine.usageAsked.single())
    }

    @Test
    fun `the preview never reaches the files, the health records or the shared text`() = runTest {
        machine.marks = nightMarks()
        machine.events = listOf(4L to tonight + 12 * hour)
        engine().sync()
        assertNotNull(preview())
        val nights = nightStore.read().all
        val tables = ExportTables.of(nights, eventStore.read().all)!!
        assertFalse(tables.getValue(ExportTables.NIGHTS).contains("2026-11-15"))
        // The night's event is kept, and waits for its night before it is written.
        assertEquals(1, eventStore.read().all.size)
        assertEquals(1, tables.getValue(ExportTables.EVENTS).lines().filter { it.isNotEmpty() }.size)
        assertTrue(records.held.keys.none { "-20261115-" in it })
        assertFalse(NightReport.csv(nights, NightReport.SHARED, withSessions = false).contains("2026-11-15"))
        assertFalse(NightReport.text(nights).contains("2026-11-15"))
    }

    @Test
    fun `when the machine's own night arrives, it takes the preview's place`() = runTest {
        machine.marks = nightMarks() + (2L to tonight + 19 * hour)
        engine().sync()
        assertEquals(LocalDate.of(2026, 11, 15), preview()?.date)
        // After midday: the machine has summed up the 15th.
        phoneNow += 6 * hour
        engine().sync()
        assertTrue(nightStore.read().byDate.containsKey(LocalDate.of(2026, 11, 15)))
        assertNull(preview())
    }

    @Test
    fun `with mask on or off unconfirmed, the usage log is not read at all`() = runTest {
        machine.marks = nightMarks()
        assertEquals(SyncOutcome.COMPLETE, engine(kinds = setOf(UsageEvent.MASK_ON)).sync()!!.outcome)
        assertEquals(0, machine.usagePulls)
        assertFalse(File(directory, "usage.json").exists())
        assertNull(preview())
        assertTrue(NightPlan.FIGURES.isNotEmpty())
    }

    @Test
    fun `a usage log that fails keeps nothing else from the phone`() = runTest {
        machine.marks = nightMarks()
        machine.events = listOf(4L to tonight + 12 * hour)
        machine.usageGarbage = true
        // Not understood: nothing said, everything else in.
        assertEquals(SyncOutcome.COMPLETE, engine().sync()!!.outcome)
        assertEquals(5, nightStore.read().byDate.size)
        assertEquals(1, eventStore.read().all.size)
        assertNull(preview())
        // The link lost on it: said as such, the rest still in.
        machine.usageGarbage = false
        machine.usageLinkLost = true
        phoneNow += hour
        machine.events = machine.events + (2L to tonight + 13 * hour)
        assertEquals(SyncOutcome.CONNECTION_LOST, engine().sync()!!.outcome)
        assertEquals(2, eventStore.read().all.size)
        // A damaged file of marks is dropped, not reported.
        machine.usageLinkLost = false
        File(directory, "usage.json").writeText("{ not marks")
        phoneNow += hour
        assertEquals(SyncOutcome.COMPLETE, engine().sync()!!.outcome)
        assertNotNull(preview())
    }

    @Test
    fun `each run reads from a little before where the last one reached, and nothing is kept twice`() = runTest {
        machine.marks = nightMarks()
        engine().sync()
        val once = usageStore.read().marks.size
        phoneNow += hour
        machine.usageAsked.clear()
        engine().sync()
        assertEquals(tonight + 17 * hour + 30 * 60_000 - SyncEngine.OVERLAP_MS, machine.usageAsked.single())
        assertEquals(once, usageStore.read().marks.size)
        assertEquals(3, once)
    }

    @Test
    fun `in a run that finds the clock set, no mark is corrected`() = runTest {
        machine.marks = nightMarks().take(1)
        engine().sync()
        phoneNow += hour
        ahead += 600_000
        machine.marks = nightMarks()
        engine().sync()
        val marks = usageStore.read().marks
        assertTrue(marks.first().correctedMs != null)
        assertTrue(marks.drop(1).all { it.correctedMs == null })
    }

    @Test
    fun `marks that cannot be stored are no failure of the run`() = runTest {
        machine.marks = nightMarks()
        machine.events = listOf(4L to tonight + 12 * hour)
        // The temporary file the marks are written through is a directory: storing them fails.
        File(directory, "usage.json.new").mkdirs()
        assertEquals(SyncOutcome.COMPLETE, engine().sync()!!.outcome)
        assertEquals(5, nightStore.read().byDate.size)
        assertEquals(1, eventStore.read().all.size)
        assertNull(preview())
    }

    @Test
    fun `with the kinds as confirmed in this version, a sync brings the Home card its time of use and its count of events`() = runTest {
        // The engine as the app makes it: no kinds named, so the confirmed ones.
        val app = SyncEngine(machine, NoMinutes, store, nowMs = { phoneNow }, nights = nightStore, records = records, events = eventStore, usage = usageStore)
        machine.marks = nightMarks()
        machine.events = listOf(4L to tonight + 12 * hour, 2L to tonight + 13 * hour, 3L to tonight + 16 * hour)
        assertEquals(SyncOutcome.COMPLETE, app.sync()!!.outcome)
        assertTrue(machine.usagePulls > 0)
        // What the runner hands the screens, and what the Home card shows of it.
        val preview = assertNotNull(usageStore.read().preview(nightStore.read().all))
        assertEquals(LocalDate.of(2026, 11, 15), preview.date)
        assertEquals(6 * 60 + 120, preview.usageMinutes)
        assertTrue(preview.running)
        assertEquals(3, com.everysoft.paprelay.ui.previewEvents(eventStore.read(), preview).size)
    }

    @Test
    fun `a usage record of the night that cannot be read gives that night no total`() = runTest {
        machine.marks = listOf(3L to tonight + 10 * hour, 2L to tonight + 12 * hour, 3L to tonight + 13 * hour, 2L to tonight + 15 * hour)
        machine.broken = tonight + 10 * hour
        engine().sync()
        val preview = assertNotNull(preview())
        assertNull(preview.usageMinutes)
        // What is known whole is still drawn.
        assertEquals(1, preview.sessions.size)
    }

    @Test
    fun `a record that keeps failing does not freeze the preview`() = runTest {
        machine.marks = listOf(3L to tonight - 20 * hour, 3L to tonight + 11 * hour)
        machine.broken = tonight - 20 * hour
        engine().sync()
        val first = usageStore.read()
        assertEquals(machineNow + 20, first.knownUntilMs)
        phoneNow += 2 * hour
        engine().sync()
        val second = usageStore.read()
        // The last read moves on; the place to resume from is held before the failure only within the window.
        assertEquals(machineNow + 20, second.knownUntilMs)
        assertTrue(second.resumeFromMs!! >= machineNow + 20 - SyncEngine.USAGE_WINDOW_MS)
        // The failed record lies before the night's first mark, so it may be this night's: no total, and no running time.
        val preview = assertNotNull(preview())
        assertNull(preview.usageMinutes)
        assertNull(preview.sessions.single().end)
    }

    @Test
    fun `the last read is when the log was read to its end, in machine time and in true time, and only then`() = runTest {
        machine.marks = nightMarks()
        engine().sync()
        val read = usageStore.read()
        assertEquals(machineNow + 20, read.knownUntilMs)
        assertEquals(machineNow + 20 - ahead, read.knownUntilCorrectedMs)
        // A download that cannot be decoded is not read to its end: the last read stays where it was.
        phoneNow += hour
        machine.usageGarbage = true
        engine().sync()
        assertEquals(read.knownUntilMs, usageStore.read().knownUntilMs)
        assertEquals(read.knownUntilCorrectedMs, usageStore.read().knownUntilCorrectedMs)
    }

    @Test
    fun `only the marks the preview can need are kept`() = runTest {
        // Read from two days back: one before the newest finished night's last day (dropped), one after (kept).
        val tooOld = noon(LocalDate.of(2026, 11, 14)) - 4 * hour
        val kept = noon(LocalDate.of(2026, 11, 14)) + hour
        machine.marks = listOf(3L to tooOld, 2L to tooOld + hour, 3L to kept, 2L to kept + hour) + nightMarks()
        engine().sync()
        val times = usageStore.read().marks.map { it.machineTime.epochMs }
        assertFalse(tooOld in times)
        assertTrue(kept in times)
        assertTrue(tonight + 11 * hour in times)
    }

    @Test
    fun `a record that cannot be read holds the place to resume from at the last one read before it`() = runTest {
        val first = tonight + 11 * hour
        machine.marks = listOf(3L to first, 2L to first + hour, 3L to first + 2 * hour)
        machine.broken = first + hour
        engine().sync()
        assertEquals(first, usageStore.read().resumeFromMs)
        val gap = usageStore.read().gaps.single()
        assertEquals(first, gap.afterMs)
        assertEquals(first + 2 * hour, gap.beforeMs)
        // Read whole the next time: the place moves on, and no gap is left.
        machine.broken = null
        phoneNow += hour
        engine().sync()
        assertEquals(first + 2 * hour, usageStore.read().resumeFromMs)
        assertTrue(usageStore.read().gaps.isEmpty())
    }

    @Test
    fun `a running session in a night a gap reaches has a start and no length, never a length of zero`() = runTest {
        machine.marks = listOf(3L to tonight + 10 * hour, 2L to tonight + 12 * hour, 3L to tonight + 13 * hour)
        machine.broken = tonight + 10 * hour
        engine().sync()
        val last = assertNotNull(preview()).sessions.last()
        assertTrue(last.running)
        assertNull(last.end)
        assertNull(last.minutes)
    }

    @Test
    fun `a record that cannot be read at the end of the log leaves a gap open to the end`() = runTest {
        val first = tonight + 11 * hour
        machine.marks = listOf(3L to first, 2L to first + hour, 3L to first + 2 * hour)
        machine.broken = first + 2 * hour
        engine().sync()
        val gap = usageStore.read().gaps.single()
        assertEquals(first + hour, gap.afterMs)
        assertNull(gap.beforeMs)
        assertNull(assertNotNull(preview()).usageMinutes)
    }
}
