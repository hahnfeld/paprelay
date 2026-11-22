// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.sync

import com.everysoft.paprelay.core.ClockOffset
import com.everysoft.paprelay.core.MachineTime
import com.everysoft.paprelay.core.sync.LedgerEntry
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncStoreTest {
    private val directory: File = Files.createTempDirectory("paprelay-store").toFile()
    private val file = File(directory, "sync.json")
    private val store = SyncStore(file)

    private fun entry(first: Long, minutes: List<Int>, version: Long = 5, pending: List<Int>? = null) =
        LedgerEntry("m-4f2a9c", MachineTime(first), minutes, "f".repeat(64), ClockOffset.restored(-361_600), java.time.ZoneId.of("America/Phoenix"), version, pending)

    private val full = SyncState(
        machineTag = "m-4f2a9c",
        ledger = listOf(entry(2_000_000, (0..122).toList()), entry(1_000_000, listOf(0, 1, 2, 5, 6, 9), version = 7, pending = listOf(0, 1, 2, 3, 5, 6, 9))).associateBy { it.key },
        resumeFromMs = 1_500_000,
        lastReport = SyncReport(atMs = 99, outcome = SyncOutcome.PARTIAL, sessions = 3, readingsWritten = 325),
        lastCompleteAtMs = 77,
        leakLimitLitresPerMinute = 30,
        clock = ClockBaseline(offsetMs = -361_600, machineMs = 1_794_787_873_218),
        rewriteRequested = true,
    )

    @Test
    fun `a state comes back exactly as it was stored`() {
        store.write(full)
        val read = store.read()
        assertEquals("m-4f2a9c", read.machineTag)
        assertEquals(1_500_000, read.resumeFromMs)
        assertEquals(77, read.lastCompleteAtMs)
        assertEquals(30, read.leakLimitLitresPerMinute)
        assertEquals(-361_600, read.clock!!.offsetMs)
        assertEquals(1_794_787_873_218, read.clock!!.machineMs)
        assertTrue(read.rewriteRequested)
        assertEquals(SyncOutcome.PARTIAL, read.lastReport!!.outcome)
        assertEquals(listOf(99L, 3L, 325L), read.lastReport!!.let { listOf(it.atMs, it.sessions.toLong(), it.readingsWritten.toLong()) })
        assertEquals(full.ledger.keys, read.ledger.keys)
        for ((key, written) in full.ledger) {
            val back = read.ledger.getValue(key)
            assertEquals(written.minutes, back.minutes)
            assertEquals(written.fingerprint, back.fingerprint)
            assertEquals(written.clockOffset, back.clockOffset)
            assertEquals(written.version, back.version)
            assertEquals(written.zone, back.zone)
            assertEquals(written.pendingMinutes, back.pendingMinutes)
            assertEquals(written.firstMinuteEnd, back.firstMinuteEnd)
        }
    }

    @Test
    fun `nothing stored is an empty state, with no leak rule`() {
        val read = store.read()
        assertNull(read.machineTag)
        assertTrue(read.ledger.isEmpty())
        assertNull(read.lastReport)
        // What the machine recorded is passed on unless the user says otherwise.
        assertNull(read.leakLimitLitresPerMinute)
        assertNull(read.clock)
        assertTrue(!read.rewriteRequested)
    }

    @Test
    fun `changing the leak rule sends every night to be looked at again`() {
        store.write(full.withLeakLimit(null))
        assertNull(store.read().leakLimitLitresPerMinute)
        assertNull(store.read().resumeFromMs)
        store.write(full.withLeakLimit(0))
        assertEquals(0, store.read().leakLimitLitresPerMinute)
        // The ledger is kept: it is what lets records under the old rule be found and removed.
        assertEquals(2, store.read().ledger.size)
        // A change that is not one to the leak rule keeps the place reached.
        store.write(full.with(rewriteRequested = false))
        assertEquals(1_500_000, store.read().resumeFromMs)
        assertTrue(!store.read().rewriteRequested)
    }

    @Test
    fun `a state that cannot be read is reported, not replaced by an empty one`() {
        for (damaged in listOf("", "{", "[]", """{"format":1,"ledger":[]}""", """{"format":2,"ledger":[{"tag":"m-4f2a9c"}]}""", """{"ledger":[]}""")) {
            file.writeText(damaged)
            assertFailsWith<SyncStateUnreadableException>(damaged) { store.read() }
            // Still there for a later version, or a person, to look at.
            assertEquals(damaged, file.readText())
        }
        // The same session twice, a run of minutes that is not one, and one that asks for the moon.
        val twice = SyncStore.encode(full).toString().replace("\"first\":2000000", "\"first\":1000000")
        file.writeText(twice)
        assertFailsWith<SyncStateUnreadableException> { store.read() }
        for (run in listOf("5-2", "a", "1-2-3", "-1", "0-99999999")) {
            file.writeText(SyncStore.encode(full).toString().replace("0-122", run))
            assertFailsWith<SyncStateUnreadableException>(run) { store.read() }
        }
        for ((from, to) in listOf("America/Phoenix" to "Nowhere/Atall", "\"leakLimit\":30" to "\"leakLimit\":-4", "f".repeat(64) to "not a fingerprint")) {
            val text = SyncStore.encode(full).toString()
            assertTrue(from in text, from)
            file.writeText(text.replace(from, to))
            assertFailsWith<SyncStateUnreadableException>(to) { store.read() }
        }
    }

    @Test
    fun `a write replaces the file whole, and a leftover from an interrupted write does no harm`() {
        store.write(full)
        // An interrupted write leaves its temporary file; the real one is untouched and still read.
        File(directory, "sync.json.new").writeText("half a sta")
        assertEquals(2, store.read().ledger.size)
        store.write(full.with(ledger = emptyMap()))
        assertTrue(store.read().ledger.isEmpty())
        assertTrue(!File(directory, "sync.json.new").exists())
        store.delete()
        assertTrue(!file.exists())
        assertNull(store.read().machineTag)
    }

    @Test
    fun `minutes are stored as runs and read back the same`() {
        for (minutes in listOf(emptyList(), listOf(0), listOf(0, 1, 2), listOf(0, 2, 4), listOf(3, 4, 5, 9, 10, 400), (0..9_000).toList())) {
            assertEquals(minutes, SyncStore.minutes(SyncStore.ranges(minutes)))
        }
        assertEquals("0-122", SyncStore.ranges((0..122).toList()))
        assertEquals("0-2,5-6,9", SyncStore.ranges(listOf(0, 1, 2, 5, 6, 9)))
        assertEquals("", SyncStore.ranges(emptyList()))
    }

    @Test
    fun `the stored file holds no health values`() {
        store.write(full)
        val text = file.readText()
        // Which minutes have a record, a fingerprint, counts and times of synchronising: nothing more.
        assertTrue("breaths" !in text && "rate" !in text && "serial" !in text, text)
    }

    @Test
    fun `the choices are kept, and a state from before they were choices goes on as it was`() {
        val file = File(Files.createTempDirectory("paprelay-choices").toFile(), "sync.json")
        val store = SyncStore(file)
        store.write(SyncState.EMPTY.with(respiratoryRate = true, healthRecords = false, exportFolder = "content://somewhere/file"))
        val back = store.read()
        assertTrue(back.respiratoryRate)
        assertTrue(!back.healthRecords)
        assertEquals("content://somewhere/file", back.exportFolder)
        // Forgetting the machine keeps what the user chose and nothing else.
        val cleared = back.with(machineTag = "m-4f2a9c", resumeFromMs = 5, rewriteRequested = true).cleared()
        assertTrue(cleared.respiratoryRate && !cleared.healthRecords && cleared.exportFolder == "content://somewhere/file")
        assertNull(cleared.machineTag)
        assertNull(cleared.resumeFromMs)
        assertTrue(!cleared.rewriteRequested)

        // The defaults: the nights go to health records, respiratory rate does not go anywhere.
        assertTrue(!SyncState.EMPTY.respiratoryRate)
        assertTrue(SyncState.EMPTY.healthRecords)

        // Written by the version before this was a choice, which wrote respiratory rate always:
        // if it had written any, it goes on doing so; if it had not, the new default applies.
        val one = entry(1_800_000_000_000, listOf(0, 1))
        val json = SyncStore.encode(SyncState.EMPTY.with(machineTag = one.machineTag, ledger = mapOf(one.key to one)))
        val older = kotlinx.serialization.json.JsonObject(json.filterKeys { it != "respiratoryRate" && it != "healthRecords" })
        assertTrue(SyncStore.decode(older).respiratoryRate)
        assertTrue(SyncStore.decode(older).healthRecords)
        val unused = kotlinx.serialization.json.JsonObject(SyncStore.encode(SyncState.EMPTY).filterKeys { it != "respiratoryRate" })
        assertTrue(!SyncStore.decode(unused).respiratoryRate)
    }
}
