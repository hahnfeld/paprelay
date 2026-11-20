// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.sync

import com.everysoft.paprelay.as11.MachineClockReading
import com.everysoft.paprelay.as11.SpoolAddress
import com.everysoft.paprelay.as11.SpoolName
import com.everysoft.paprelay.as11.SpoolRound
import com.everysoft.paprelay.as11.decode.Proto
import com.everysoft.paprelay.as11.decode.proto
import com.everysoft.paprelay.core.nights.FhirResource
import com.everysoft.paprelay.core.nights.NightFigure
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

/** The nightly figures: from the machine's daily summaries to the phone's list and its health records. */
class NightSyncTest {
    private val serial = "23211234567"
    private val software = "SW04600.19.8.8.0.dced143ae"
    private val day = 86_400_000L
    private val hour = 3_600_000L

    private fun noon(date: LocalDate) = date.atTime(19, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val first = LocalDate.of(2026, 11, 10)

    /** The phone's clock: half past five in the afternoon, machine time, on the 16th. */
    private var phoneNow = noon(LocalDate.of(2026, 11, 16)) + 5 * hour + 30 * 60_000
    private val machineNow get() = phoneNow + 361_600

    /** A day on the machine: when it began, minutes of therapy, and its AHI in hundredths (null for a day without therapy). */
    private class Day(val start: Long, val usage: Int, val ahi: Long?, val broken: Boolean = false, val written: Long? = null, val listed: Long = 1)

    private fun Proto.day(day: Day) {
        message(2) {
            varint(1, if (day.broken) 2 else 1)
            varint(2, day.start)
            varint(3, day.start + 86_400_000L)
            zigzag(4, -420)
            varint(5, day.usage.toLong())
            if (day.ahi != null) {
                message(6) { message(1) { varint(1, day.start + 36_000_000); varint(2, day.usage.toLong()) } }
                varint(7, day.ahi); varint(10, day.ahi); varint(11, 0); varint(9, 0)
                message(14) { varint(2, 6); varint(3, 8); varint(4, 42); varint(5, 86) }
                message(21) { varint(2, 800); varint(3, 960); varint(4, 1020) }
                varint(39, day.listed)
            }
            varint(40, day.written ?: (day.start + 80_000_000))
        }
    }

    private inner class Machine : MachineConnector, MachineSession {
        var days = listOf<Day>()
        var perRound = 100
        var applicationIdentifier = software
        var serialNumber = serial
        val asked = ArrayList<Pair<SpoolName, Long>>()

        override fun notReady(): SyncOutcome? = null

        override fun pairedSerial() = serial

        override suspend fun <T> withSession(block: suspend (MachineSession) -> T): T = block(this)

        override suspend fun identity() = MachineIdentity(serialNumber, applicationIdentifier)

        override suspend fun clock() = MachineClockReading(machineEpochMs = machineNow + 20, sentAtMs = phoneNow, roundTripMs = 40)

        override suspend fun pull(log: SpoolName, from: SpoolAddress): SpoolRound {
            if (log != SpoolName.SUMMARY) return SpoolRound(ByteArray(0), ByteArray(32), null)
            val (wanted, offset) = when (from) {
                is SpoolAddress.FromMachineTime -> {
                    asked += log to from.machineEpochMs
                    // The machine sends a day once it has ended, and only those ending after the time asked for.
                    days.filter { it.start + day > from.machineEpochMs && it.start + day <= machineNow } to 0
                }
                is SpoolAddress.Continuation -> days.filter { it.start >= from.next.getValue("since").toString().toLong() && it.start + day <= machineNow } to
                    from.next.getValue("at").toString().toInt()
            }
            val page = wanted.drop(offset).take(perRound)
            val next = if (offset + page.size < wanted.size) {
                JsonObject(mapOf("at" to JsonPrimitive(offset + page.size), "since" to JsonPrimitive(wanted.first().start)))
            } else {
                null
            }
            return SpoolRound(proto { for (one in page) day(one) }, ByteArray(32), next)
        }
    }

    /** The phone's health records as the app sees them: resources by id; a write replaces. */
    private inner class Records : NightRecords {
        val held = HashMap<String, String>()
        var state = RecordsState.READY
        var failOnWrite: Int? = null
        var writes = 0

        /** False once PAPRelay's place in the health records has been made; set back to true to play its removal. */
        var gone = true

        override suspend fun state() = state

        override suspend fun open(): Boolean {
            val fresh = gone
            if (gone) held.clear()
            gone = false
            return fresh
        }

        override suspend fun write(resources: List<FhirResource>, removeIds: List<String>) {
            writes++
            if (failOnWrite == writes) throw HealthSinkException(notAllowed = false)
            for (id in removeIds) held.remove(id)
            for (resource in resources) held[resource.id] = resource.json
        }
    }

    private object NoMinutes : HealthSink {
        override suspend fun notReady(): SyncOutcome? = SyncOutcome.HEALTH_CONNECT_UNAVAILABLE

        override suspend fun apply(change: SessionChange) = error("respiratory rate is switched off")
    }

    /** A used night gives four records (usage, events, leak, pressure); a day without therapy gives one. */
    private val perNight = 4
    private val weekRecords = 5 * perNight + 1

    private fun mark(night: com.everysoft.paprelay.core.nights.Night) = "${com.everysoft.paprelay.core.nights.FhirObservations.FORMAT}:${night.fingerprint()}"

    private val machine = Machine()
    private val records = Records()
    private val directory: File = Files.createTempDirectory("paprelay-nights").toFile()
    private val store = SyncStore(File(directory, "sync.json"))
    private val nightStore = NightStore(File(directory, "nights.json"))
    private val engine = SyncEngine(machine, NoMinutes, store, nowMs = { phoneNow }, nights = nightStore, records = records)
    private val tag = SyncEngine.machineTag(serial)

    private fun week() = (0..5).map { Day(noon(first.plusDays(it.toLong())), if (it == 3) 0 else 400 + it, if (it == 3) null else 100L + it * 10) }

    @Test
    fun `the first run brings every finished night, used or not, to the phone and to its health records`() = runTest {
        machine.days = week()
        val report = engine.sync()!!
        // Respiratory rate is off and Health Connect would not allow it: neither stands in the way.
        assertEquals(SyncOutcome.COMPLETE, report.outcome)
        val nights = nightStore.read()
        assertEquals(tag, nights.machineTag)
        assertEquals((0..5).map { first.plusDays(it.toLong()) }.reversed(), nights.all.map { it.date })
        assertEquals("1.2", nights.byDate.getValue(first.plusDays(2)).night.value(NightFigure.AHI)!!.toPlainString())
        assertEquals(0, nights.byDate.getValue(first.plusDays(3)).night.usageMinutes)
        assertTrue(nights.all.all { it.complete })
        // Asked for from as far back as the machine might hold.
        assertEquals(SpoolName.SUMMARY to machineNow + 20 - SyncEngine.FIRST_WINDOW_MS, machine.asked.single())

        // Usage for all six, and events, leak and pressure for each of the five nights with therapy.
        assertEquals(weekRecords, records.held.size)
        assertTrue(records.held.getValue("$tag-20261112-events").contains(""""valueQuantity":{"value":1.2,"unit":"events/h""""))
        assertTrue(records.held.getValue("$tag-20261113-usage").contains(""""valueQuantity":{"value":0,"unit":"h""""))
        assertEquals(listOf("$tag-20261113-usage"), records.held.keys.filter { "-20261113-" in it })
        assertTrue(nights.byDate.values.all { it.recorded == mark(it.night) })

        // The second run asks only for the last few days, and writes nothing.
        phoneNow += hour
        machine.asked.clear()
        val writes = records.writes
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertEquals(noon(first.plusDays(6)) - SyncEngine.NIGHT_OVERLAP_MS, machine.asked.single().second)
        assertEquals(writes, records.writes)
    }

    @Test
    fun `a new night and an amended one are passed on, and the others are left alone`() = runTest {
        machine.days = week()
        engine.sync()
        val before = records.held.toMap()
        val writes = records.writes
        // A day later: one more night, and the machine has amended the one before it.
        phoneNow += day
        machine.days = week().dropLast(1) + Day(noon(first.plusDays(5)), 405, 290) + Day(noon(first.plusDays(6)), 380, 70)
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertEquals(writes + 2, records.writes)
        assertTrue(records.held.getValue("$tag-20261115-events").contains(""""valueQuantity":{"value":2.9,"""))
        assertTrue(records.held.getValue("$tag-20261116-events").contains(""""valueQuantity":{"value":0.7,"""))
        assertEquals(before.filterKeys { "-20261115-" !in it }, records.held.filterKeys { "-20261115-" !in it && "-20261116-" !in it })
        assertEquals(7, nightStore.read().byDate.size)
    }

    @Test
    fun `a figure a night no longer has is removed from the health records`() = runTest {
        machine.days = week()
        engine.sync()
        assertTrue(records.held.containsKey("$tag-20261115-events"))
        assertTrue(records.held.containsKey("$tag-20261115-leak"))
        // The machine now says the last day had no therapy after all.
        machine.days = week().dropLast(1) + Day(noon(first.plusDays(5)), 0, null)
        phoneNow += hour
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertEquals(listOf("$tag-20261115-usage"), records.held.keys.filter { "-20261115-" in it })
        assertTrue(records.held.getValue("$tag-20261115-usage").contains(""""valueQuantity":{"value":0,"unit":"h""""))
        // The other nights are untouched.
        assertEquals(weekRecords - (perNight - 1), records.held.size)
    }

    @Test
    fun `a day still running is neither kept as finished nor passed on`() = runTest {
        // The machine sends only days that have ended; one that has not is not there to be read.
        machine.days = week() + Day(noon(first.plusDays(6)), 200, 50)
        engine.sync()
        assertEquals(6, nightStore.read().byDate.size)
        assertFalse(records.held.keys.any { "-20261116-" in it })
    }

    @Test
    fun `health records that fail keep the nights on the phone, and are tried again`() = runTest {
        machine.days = week()
        records.failOnWrite = 3
        val report = engine.sync()!!
        assertEquals(SyncOutcome.HEALTH_CONNECT_FAILED, report.outcome)
        // Every night is on the phone all the same; two were passed on before it failed.
        assertEquals(6, nightStore.read().byDate.size)
        assertEquals(2, nightStore.read().byDate.values.count { it.recorded != null })
        records.failOnWrite = null
        phoneNow += hour
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertTrue(nightStore.read().byDate.values.all { it.recorded != null })
        assertEquals(weekRecords, records.held.size)
    }

    @Test
    fun `the nights of another version of the same software are passed on, and those of another line are not`() = runTest {
        machine.days = week()
        machine.applicationIdentifier = "SW04700.20.1.0.0.abcdef"
        assertEquals(SyncOutcome.SOFTWARE_NOT_CONFIRMED, engine.sync()!!.outcome)
        assertEquals(0, nightStore.read().byDate.size)
        assertEquals(0, records.writes)

        phoneNow += hour
        machine.applicationIdentifier = "SW04600.20.1.0.0.abcdef"
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertEquals(6, nightStore.read().byDate.size)
        assertEquals(weekRecords, records.held.size)
        assertEquals("1.2", nightStore.read().byDate.getValue(first.plusDays(2)).night.value(NightFigure.AHI)!!.toPlainString())
    }

    @Test
    fun `on another version the nights are passed on with respiratory rate switched on, and its log is not asked for`() = runTest {
        machine.days = week()
        machine.applicationIdentifier = "SW04600.20.1.0.0.abcdef"
        store.write(SyncState.EMPTY.with(respiratoryRate = true))
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertEquals(6, nightStore.read().byDate.size)
        assertEquals(weekRecords, records.held.size)
        assertEquals(setOf(SpoolName.SUMMARY), machine.asked.map { it.first }.toSet())
    }

    @Test
    fun `with health records switched off or not there, the nights are kept and nothing is passed on`() = runTest {
        machine.days = week()
        store.write(SyncState.EMPTY.with(healthRecords = false))
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertEquals(6, nightStore.read().byDate.size)
        assertEquals(0, records.writes)

        // Switched on, on a phone that keeps no health records: still nothing, and nothing wrong.
        store.write(store.read().with(healthRecords = true))
        records.state = RecordsState.NOT_SUPPORTED
        phoneNow += hour
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertEquals(0, records.writes)
        val plain = SyncEngine(machine, NoMinutes, store, nowMs = { phoneNow }, nights = NightStore(File(directory, "other.json")))
        assertEquals(SyncOutcome.COMPLETE, plain.sync()!!.outcome)
    }

    @Test
    fun `health records switched on and not allowed is said, and the nights reach the phone all the same`() = runTest {
        machine.days = week()
        records.state = RecordsState.NOT_ALLOWED
        // Not "up to date": something that is switched on could not be written.
        assertEquals(SyncOutcome.HEALTH_CONNECT_NOT_ALLOWED, engine.sync()!!.outcome)
        assertEquals(6, nightStore.read().byDate.size)
        assertEquals(0, records.writes)
        // Allowed at last: everything kept so far is passed on.
        records.state = RecordsState.READY
        phoneNow += hour
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertEquals(weekRecords, records.held.size)
    }

    @Test
    fun `when PAPRelay's place in the health records has been removed, everything is written there again`() = runTest {
        machine.days = week()
        engine.sync()
        assertEquals(weekRecords, records.held.size)
        // The user deletes PAPRelay's data in Health Connect. Nothing here knows.
        records.gone = true
        phoneNow += hour
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertEquals(weekRecords, records.held.size)
    }

    @Test
    fun `asked to write everything again, every night is passed on once more, and only once`() = runTest {
        machine.days = week()
        engine.sync()
        records.held.clear()
        store.write(store.read().with(rewriteRequested = true))
        phoneNow += hour
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertEquals(weekRecords, records.held.size)
        assertFalse(store.read().rewriteRequested)
        val writes = records.writes
        phoneNow += hour
        engine.sync()
        assertEquals(writes, records.writes)
    }

    @Test
    fun `a night written again leaves nothing of what it used to say`() = runTest {
        machine.days = week()
        engine.sync()
        // The machine now says the last day had no therapy; and everything is to be written again,
        // so what was recorded for it before is no longer remembered here.
        machine.days = week().dropLast(1) + Day(noon(first.plusDays(5)), 0, null)
        store.write(store.read().with(rewriteRequested = true))
        phoneNow += hour
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertEquals(listOf("$tag-20261115-usage"), records.held.keys.filter { "-20261115-" in it })
    }

    @Test
    fun `a file of nights that cannot be read is put aside, not written over, and the list is built again`() = runTest {
        machine.days = week()
        engine.sync()
        File(directory, "nights.json").writeText("{ not nights")
        phoneNow += hour
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertEquals("{ not nights", File(directory, "nights.json.damaged").readText())
        assertEquals(6, nightStore.read().byDate.size)
        // Everything is passed on again, over the same records.
        assertEquals(weekRecords, records.held.size)
    }

    @Test
    fun `two records for one day, the one the machine wrote later stands, whichever comes first`() = runTest {
        val start = noon(first)
        val early = Day(start, 300, 990, written = start + 80_000_000)
        val late = Day(start, 300, 250, written = start + 90_000_000)
        for (order in listOf(listOf(early, late), listOf(late, early))) {
            nightStore.delete()
            machine.days = order
            phoneNow += hour
            assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
            assertEquals("2.5", nightStore.read().byDate.getValue(first).night.value(NightFigure.AHI)!!.toPlainString())
        }
    }

    @Test
    fun `two different days that claim one date are neither believed, and the run says so`() = runTest {
        machine.days = week()
        engine.sync()
        val kept = nightStore.read().byDate.getValue(first.plusDays(5)).night.fingerprint()
        // The machine's clock was set: a second day, a quarter of an hour off, lands on the 15th as well.
        machine.days = week() + Day(noon(first.plusDays(5)) + 15 * 60_000, 200, 770)
        phoneNow += hour
        assertEquals(SyncOutcome.PARTIAL, engine.sync()!!.outcome)
        assertEquals(kept, nightStore.read().byDate.getValue(first.plusDays(5)).night.fingerprint())
    }

    @Test
    fun `a day that claims a date another day already holds from an earlier run is not believed either`() = runTest {
        machine.days = week()
        engine.sync()
        val kept = nightStore.read().byDate.mapValues { it.value.night.fingerprint() }
        // The machine's clock was set between runs: its days now begin a quarter of an hour off, and
        // each lands on a date that already has a night.
        machine.days = week().map { Day(it.start + 15 * 60_000, it.usage, it.ahi?.plus(500)) }
        phoneNow += hour
        assertEquals(SyncOutcome.PARTIAL, engine.sync()!!.outcome)
        assertEquals(kept, nightStore.read().byDate.mapValues { it.value.night.fingerprint() })
    }

    @Test
    fun `forgetting removes the nights and whatever was put aside`() {
        val file = File(directory, "gone.json")
        val nights = NightStore(file)
        nights.write(Nights(tag, emptyMap()))
        file.writeText("{ not nights")
        nights.setAside("damaged")
        nights.write(Nights(tag, emptyMap()))
        nights.setAside("previous")
        nights.write(Nights(tag, emptyMap()))
        nights.delete()
        assertTrue(directory.listFiles()!!.none { it.name.startsWith("gone.json") })
    }

    @Test
    fun `a finished day that cannot stand as a night leaves the run short, and says so`() = runTest {
        // Its list of sessions is not whole.
        machine.days = week().mapIndexed { index, one -> if (index == 2) Day(one.start, one.usage, one.ahi, listed = 3) else one }
        assertEquals(SyncOutcome.PARTIAL, engine.sync()!!.outcome)
        assertEquals(5, nightStore.read().byDate.size)
    }

    @Test
    fun `respiratory rate that Health Connect will not take does not keep the nights from the phone`() = runTest {
        machine.days = week()
        store.write(SyncState.EMPTY.with(respiratoryRate = true))
        // Health Connect is not there for the respiratory rate; the nights need nothing of it.
        assertEquals(SyncOutcome.HEALTH_CONNECT_UNAVAILABLE, engine.sync()!!.outcome)
        assertEquals(6, nightStore.read().byDate.size)
        assertEquals(weekRecords, records.held.size)
    }

    @Test
    fun `a day that cannot be read costs itself, and the run says not everything came across`() = runTest {
        machine.days = week().mapIndexed { index, one -> if (index == 2) Day(one.start, one.usage, one.ahi, broken = true) else one }
        assertEquals(SyncOutcome.PARTIAL, engine.sync()!!.outcome)
        assertEquals(5, nightStore.read().byDate.size)
        assertNull(nightStore.read().byDate[first.plusDays(2)])
    }

    @Test
    fun `a long history arrives in several rounds`() = runTest {
        machine.days = (0..39L).map { Day(noon(LocalDate.of(2026, 10, 1).plusDays(it)), 300, 150) }
        machine.perRound = 7
        assertEquals(SyncOutcome.COMPLETE, engine.sync()!!.outcome)
        assertEquals(40, nightStore.read().byDate.size)
    }

    @Test
    fun `another machine's nights are not kept beside this one's`() = runTest {
        machine.days = week()
        engine.sync()
        machine.serialNumber = "23219999999"
        val other = SyncEngine(
            object : MachineConnector by machine {
                override fun pairedSerial() = "23219999999"
            },
            NoMinutes, store, nowMs = { phoneNow }, nights = nightStore, records = records,
        )
        machine.days = listOf(Day(noon(first.plusDays(4)), 100, 300))
        phoneNow += hour
        assertEquals(SyncOutcome.COMPLETE, other.sync()!!.outcome)
        val nights = nightStore.read()
        assertEquals(SyncEngine.machineTag("23219999999"), nights.machineTag)
        assertEquals(listOf(first.plusDays(4)), nights.all.map { it.date })
        assertEquals("3", nights.all.single().value(NightFigure.AHI)!!.toPlainString())
    }

    @Test
    fun `the nights survive being stored and read back, and a damaged file is simply built again`() {
        val file = File(directory, "round.json")
        val nights = NightStore(file)
        assertTrue(nights.read().byDate.isEmpty())
        val night = com.everysoft.paprelay.core.nights.Night(first, -420, 432, 2, mapOf(NightFigure.AHI to 230L, NightFigure.LEAK_95 to 1440L), true)
        val running = com.everysoft.paprelay.core.nights.Night(first.plusDays(1), 330, 10, 1, emptyMap(), false)
        nights.write(Nights("m-13f8d92ec4854105", mapOf(first to StoredNight(night, mark(night)), first.plusDays(1) to StoredNight(running, null))))
        val back = nights.read()
        assertEquals("m-13f8d92ec4854105", back.machineTag)
        assertEquals(night.fingerprint(), back.byDate.getValue(first).night.fingerprint())
        assertEquals(mark(night), back.byDate.getValue(first).recorded)
        assertEquals(running.fingerprint(), back.byDate.getValue(first.plusDays(1)).night.fingerprint())
        assertNull(back.byDate.getValue(first.plusDays(1)).recorded)
        // The file holds figures, which is its purpose, but no serial number.
        assertFalse(file.readText().contains("2321"))
        // A file that is there and cannot be read is said to be so, not passed off as empty.
        file.writeText("{ not nights")
        kotlin.test.assertFailsWith<NightsUnreadableException> { nights.read() }
        nights.setAside("damaged")
        assertFalse(file.exists())
        assertTrue(nights.read().byDate.isEmpty())
        assertEquals("{ not nights", File(directory, "round.json.damaged").readText())
        // A day the clocks changed in keeps its length.
        val short = com.everysoft.paprelay.core.nights.Night(first, -420, 100, 1, emptyMap(), true, hours = 23)
        nights.write(Nights("m-13f8d92ec4854105", mapOf(first to StoredNight(short, null))))
        assertEquals(23, nights.read().byDate.getValue(first).night.hours)
        nights.delete()
        assertFalse(file.exists())
    }
}
