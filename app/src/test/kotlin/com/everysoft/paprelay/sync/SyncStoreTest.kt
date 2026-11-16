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

    private fun entry(first: Long, minutes: List<Int>, version: Long = 5) =
        LedgerEntry("m-4f2a9c", MachineTime(first), minutes, "f".repeat(64), ClockOffset.restored(-361_600), version)

    private val full = SyncState(
        machineTag = "m-4f2a9c",
        ledger = listOf(entry(2_000_000, (0..122).toList()), entry(1_000_000, listOf(0, 1, 2, 5, 6, 9), version = 7)).associateBy { it.key },
        resumeFromMs = 1_500_000,
        lastReport = SyncReport(atMs = 99, outcome = SyncOutcome.PARTIAL, sessions = 3, readingsWritten = 325),
        lastCompleteAtMs = 77,
        leakLimitLitresPerMinute = 30,
    )

    @Test
    fun `a state comes back exactly as it was stored`() {
        store.write(full)
        val read = store.read()
        assertEquals("m-4f2a9c", read.machineTag)
        assertEquals(1_500_000, read.resumeFromMs)
        assertEquals(77, read.lastCompleteAtMs)
        assertEquals(30, read.leakLimitLitresPerMinute)
        assertEquals(SyncOutcome.PARTIAL, read.lastReport!!.outcome)
        assertEquals(listOf(99L, 3L, 325L), read.lastReport!!.let { listOf(it.atMs, it.sessions.toLong(), it.readingsWritten.toLong()) })
        assertEquals(full.ledger.keys, read.ledger.keys)
        for ((key, written) in full.ledger) {
            val back = read.ledger.getValue(key)
            assertEquals(written.minutes, back.minutes)
            assertEquals(written.fingerprint, back.fingerprint)
            assertEquals(written.clockOffset, back.clockOffset)
            assertEquals(written.version, back.version)
            assertEquals(written.firstMinuteEnd, back.firstMinuteEnd)
        }
    }

    @Test
    fun `nothing stored is an empty state, with the leak limit at its default`() {
        val read = store.read()
        assertNull(read.machineTag)
        assertTrue(read.ledger.isEmpty())
        assertNull(read.lastReport)
        assertEquals(24, read.leakLimitLitresPerMinute)
    }

    @Test
    fun `the leak limit can be switched off, and stays off`() {
        store.write(full.with(leakLimitLitresPerMinute = null))
        assertNull(store.read().leakLimitLitresPerMinute)
        store.write(full.with(leakLimitLitresPerMinute = 0))
        assertEquals(0, store.read().leakLimitLitresPerMinute)
    }

    @Test
    fun `a state that cannot be read is reported, not replaced by an empty one`() {
        for (damaged in listOf("", "{", "[]", """{"format":2,"ledger":[]}""", """{"format":1,"ledger":[{"tag":"m-4f2a9c"}]}""", """{"ledger":[]}""")) {
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
}
