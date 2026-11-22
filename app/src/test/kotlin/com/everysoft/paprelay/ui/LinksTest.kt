// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.ui

import com.everysoft.paprelay.as11.PairingCredentials
import com.everysoft.paprelay.check.CheckEnd
import com.everysoft.paprelay.check.CheckSteps
import com.everysoft.paprelay.check.Step
import com.everysoft.paprelay.check.StepState
import com.everysoft.paprelay.pairing.Pairing
import com.everysoft.paprelay.pairing.PairingState
import com.everysoft.paprelay.sync.SyncOutcome
import com.everysoft.paprelay.sync.SyncReport
import com.everysoft.paprelay.sync.SyncStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinksTest {
    private val paired = PairingState.Paired(Pairing(PairingCredentials("test", ByteArray(32)), "00:11:22:33:44:55", "23000000001", 0))
    private val order = listOf(CheckSteps.HEALTH, CheckSteps.SCAN, CheckSteps.PAIR, CheckSteps.SESSION, CheckSteps.IDENTITY, CheckSteps.CLOCK, CheckSteps.DOWNLOAD)

    /** The steps in their usual order, each in the state given, the rest waiting. */
    private fun steps(vararg states: StepState, onPhone: Boolean = false): List<Step> =
        order.mapIndexed { index, id -> Step(id, states.getOrElse(index) { StepState.PENDING }, onPhone = onPhone && states.getOrNull(index) == StepState.FAILED) }

    private val untouched = steps()
    private val allPassed = steps(*Array(order.size) { StepState.PASSED })
    private val neverSynced = SyncStatus(running = false, lastReport = null, lastCompleteAtMs = null, storeUnreadable = false)

    private fun synced(outcome: SyncOutcome) = neverSynced.with(lastReport = SyncReport(atMs = 1, outcome = outcome, sessions = 1, readingsWritten = 1))

    /** What the home screen draws, as MainActivity puts it together. */
    private fun home(pairing: PairingState, steps: List<Step>, running: Boolean, end: CheckEnd?, syncEnabled: Boolean, sync: SyncStatus, allowed: Boolean): Links =
        homeLinks(homeShows(pairing, running, end, syncEnabled, sync), pairing, steps, running, sync, allowed)

    /** A check that ended while [sync] was the latest synchronisation. */
    private fun endedAfter(sync: SyncStatus) = CheckEnd(sync.lastReport?.atMs)

    @Test
    fun `nothing in particular is shown before the pairing has been read`() {
        assertEquals(HomeShows.NOTHING, homeShows(PairingState.Loading, false, endedAfter(neverSynced), true, synced(SyncOutcome.COMPLETE)))
        assertEquals(Links(LinkState.NONE, LinkState.NONE), home(PairingState.Loading, allPassed, false, endedAfter(neverSynced), true, synced(SyncOutcome.COMPLETE), true))
    }

    @Test
    fun `while pairing, the lines fill in as the check reaches each part`() {
        // Health Connect first, then finding the machine.
        assertEquals(Links(LinkState.NONE, LinkState.ACTIVE), checkLinks(steps(StepState.RUNNING), PairingState.Absent, true, false))
        assertEquals(Links(LinkState.ACTIVE, LinkState.OK), checkLinks(steps(StepState.PASSED, StepState.RUNNING), PairingState.Absent, true, false))
        // The pause after pairing, between two steps, still shows data on the move.
        val afterPairing = steps(StepState.PASSED, StepState.PASSED, StepState.PASSED)
        assertEquals(Links(LinkState.ACTIVE, LinkState.OK), checkLinks(afterPairing, paired, true, true))
        // Done: both legs are ticked.
        assertEquals(Links(LinkState.OK, LinkState.OK), checkLinks(allPassed, paired, false, true))
    }

    @Test
    fun `a machine-side failure breaks the machine leg, and a phone-side one marks the phone`() {
        val machineSide = steps(StepState.PASSED, StepState.FAILED)
        assertEquals(Links(LinkState.FAILED, LinkState.OK), checkLinks(machineSide, PairingState.Absent, false, true))
        val phoneSide = steps(StepState.PASSED, StepState.FAILED, onPhone = true)
        assertEquals(Links(LinkState.NONE, LinkState.OK, phoneFault = true), checkLinks(phoneSide, PairingState.Absent, false, true))
        // Health Connect failing is drawn on its own leg, not on the phone.
        val health = steps(StepState.FAILED, onPhone = true)
        assertEquals(Links(LinkState.NONE, LinkState.FAILED), checkLinks(health, PairingState.Absent, false, false))
    }

    @Test
    fun `a tick on the machine leg needs a pairing that still exists`() {
        assertEquals(LinkState.NONE, checkLinks(allPassed, PairingState.Absent, false, true).machine)
    }

    @Test
    fun `before a check, the legs say only what is set up`() {
        assertEquals(Links(LinkState.READY, LinkState.READY), checkLinks(untouched, paired, false, true))
        assertEquals(Links(LinkState.NONE, LinkState.NONE), checkLinks(untouched, PairingState.Absent, false, false))
    }

    @Test
    fun `after a passed check with no synchronisation yet, home keeps the check's ticks`() {
        assertEquals(HomeShows.CHECK, homeShows(paired, false, endedAfter(neverSynced), true, neverSynced))
        assertEquals(Links(LinkState.OK, LinkState.OK), home(paired, allPassed, false, endedAfter(neverSynced), true, neverSynced, true))
    }

    @Test
    fun `after a failed check with no synchronisation yet, home keeps the check's failure`() {
        val failed = steps(StepState.PASSED, StepState.PASSED, StepState.SKIPPED, StepState.FAILED)
        assertEquals(LinkState.FAILED, home(paired, failed, false, endedAfter(neverSynced), true, neverSynced, true).machine)
    }

    @Test
    fun `home shows whichever of the check and synchronising ended last`() {
        val earlier = neverSynced.with(lastReport = SyncReport(atMs = 100, outcome = SyncOutcome.CONNECTION_LOST, sessions = 1, readingsWritten = 0))
        // The check came after the synchronisation: its ticks are shown.
        assertEquals(HomeShows.CHECK, homeShows(paired, false, endedAfter(earlier), true, earlier))
        assertEquals(Links(LinkState.OK, LinkState.OK), home(paired, allPassed, false, endedAfter(earlier), true, earlier, true))
        // A synchronisation came after the check: its failure is shown.
        val later = earlier.with(lastReport = SyncReport(atMs = 300, outcome = SyncOutcome.CONNECTION_LOST, sessions = 1, readingsWritten = 0))
        assertEquals(HomeShows.SYNC, homeShows(paired, false, endedAfter(earlier), true, later))
        assertEquals(Links(LinkState.FAILED, LinkState.READY), home(paired, allPassed, false, endedAfter(earlier), true, later, true))
        // The first synchronisation after a check made before any.
        assertEquals(HomeShows.SYNC, homeShows(paired, false, endedAfter(neverSynced), true, later))
    }

    @Test
    fun `a synchronisation stamped by a clock that was ahead does not hide a later check`() {
        val day = 86_400_000L
        val now = 1_800_000_000_000L
        // Stamped while the phone's clock was a day fast; the clock was then put right and a check run.
        val ahead = neverSynced.with(lastReport = SyncReport(atMs = now + day, outcome = SyncOutcome.PHONE_CLOCK_WRONG, sessions = 0, readingsWritten = 0))
        assertEquals(HomeShows.CHECK, homeShows(paired, false, endedAfter(ahead), true, ahead))
        assertEquals(Links(LinkState.OK, LinkState.OK), home(paired, allPassed, false, endedAfter(ahead), true, ahead, true))
        // The next synchronisation, stamped by the right clock and so "earlier", is still the newer event.
        val next = ahead.with(lastReport = SyncReport(atMs = now + 60_000, outcome = SyncOutcome.COMPLETE, sessions = 1, readingsWritten = 1))
        assertEquals(HomeShows.SYNC, homeShows(paired, false, endedAfter(ahead), true, next))
    }

    @Test
    fun `a synchronisation in progress or a damaged record is shown even after a newer check`() {
        assertEquals(Links(LinkState.ACTIVE, LinkState.READY), home(paired, allPassed, false, endedAfter(neverSynced), true, neverSynced.with(running = true), true))
        assertEquals(HomeShows.SYNC, homeShows(paired, false, endedAfter(neverSynced), true, neverSynced.with(storeUnreadable = true)))
        assertTrue(home(paired, allPassed, false, endedAfter(neverSynced), true, neverSynced.with(storeUnreadable = true), true).phoneFault)
    }

    @Test
    fun `with no check and no synchronisation, home shows the legs as ready`() {
        // After a restart the check is forgotten: synchronising, with nothing yet, is shown.
        assertEquals(HomeShows.SYNC, homeShows(paired, false, null, true, neverSynced))
        assertEquals(Links(LinkState.READY, LinkState.NONE), home(paired, untouched, false, null, true, neverSynced, false))
    }

    @Test
    fun `while a check runs, home shows the check even after synchronising`() {
        val running = steps(StepState.RUNNING)
        assertEquals(Links(LinkState.READY, LinkState.ACTIVE), home(paired, running, true, null, true, synced(SyncOutcome.COMPLETE), true))
    }

    @Test
    fun `before there is a machine, or in a build that does not synchronise, home shows the check`() {
        assertEquals(HomeShows.CHECK, homeShows(PairingState.Absent, false, null, true, neverSynced))
        assertEquals(HomeShows.CHECK, homeShows(PairingState.Unavailable, false, null, true, synced(SyncOutcome.COMPLETE)))
        assertEquals(Links(LinkState.OK, LinkState.OK), home(paired, allPassed, false, null, false, synced(SyncOutcome.CONNECTION_LOST), true))
    }

    @Test
    fun `a check's outcome is read the way the screens say it`() {
        assertEquals(CheckOutcome.PASSED, checkOutcome(allPassed))
        assertEquals(CheckOutcome.FAILED, checkOutcome(steps(StepState.PASSED, StepState.FAILED, StepState.SKIPPED)))
        // Stopped wins over a failure: the user ended it.
        assertEquals(CheckOutcome.STOPPED, checkOutcome(steps(StepState.FAILED, StepState.CANCELLED)))
        // Nothing failed, but the last step did not pass either.
        assertEquals(CheckOutcome.INCOMPLETE, checkOutcome(steps(StepState.PASSED, StepState.NOTED, StepState.SKIPPED, StepState.SKIPPED, StepState.SKIPPED, StepState.SKIPPED, StepState.SKIPPED)))
    }

    @Test
    fun `synchronising ticks Health Connect only when it is allowed`() {
        assertEquals(Links(LinkState.OK, LinkState.OK), syncLinks(synced(SyncOutcome.COMPLETE), true))
        assertEquals(Links(LinkState.OK, LinkState.NONE), syncLinks(synced(SyncOutcome.COMPLETE), false))
    }

    @Test
    fun `each synchronisation outcome is drawn where its cause lies`() {
        assertEquals(Links(LinkState.READY, LinkState.READY), syncLinks(synced(SyncOutcome.MACHINE_AWAY), true))
        assertEquals(Links(LinkState.READY, LinkState.READY, phoneFault = true), syncLinks(synced(SyncOutcome.BLUETOOTH_OFF), true))
        assertEquals(Links(LinkState.READY, LinkState.NONE), syncLinks(synced(SyncOutcome.HEALTH_CONNECT_NOT_ALLOWED), true))
        assertEquals(Links(LinkState.READY, LinkState.FAILED), syncLinks(synced(SyncOutcome.HEALTH_CONNECT_FAILED), true))
        assertEquals(Links(LinkState.FAILED, LinkState.READY), syncLinks(synced(SyncOutcome.WRONG_MACHINE), true))
        // Every outcome is drawn somehow, with no phone fault where the machine is to blame.
        SyncOutcome.entries.forEach { outcome ->
            val links = syncLinks(synced(outcome), true)
            if (links.machine == LinkState.FAILED) assertFalse(links.phoneFault, outcome.name)
        }
    }

    @Test
    fun `a check counts as started once it runs or any step has moved`() {
        assertFalse(checkStarted(untouched, false))
        assertTrue(checkStarted(untouched, true))
        assertTrue(checkStarted(steps(StepState.NOTED), false))
    }
}
