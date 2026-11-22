// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.ui

import com.everysoft.paprelay.check.CheckEnd
import com.everysoft.paprelay.check.CheckSteps
import com.everysoft.paprelay.check.Step
import com.everysoft.paprelay.check.StepState
import com.everysoft.paprelay.pairing.PairingState
import com.everysoft.paprelay.sync.SyncOutcome
import com.everysoft.paprelay.sync.SyncStatus

/** The state of one leg of the journey from the machine to Health Connect. */
enum class LinkState {
    /** Nothing connects the two ends yet. */
    NONE,

    /** Connected and idle. */
    READY,

    /** Data is moving along this leg right now. */
    ACTIVE,

    /** This leg has been shown to work. */
    OK,

    /** This leg did not work. */
    FAILED,
}

/**
 * What the picture shows: how each of its two legs stands, and whether something is
 * wrong on the phone itself. Worked out here, away from the screens, so it can be tested.
 */
data class Links(val machine: LinkState, val health: LinkState, val phoneFault: Boolean = false)

/** True once a device check has been started, so that its steps say something. */
fun checkStarted(steps: List<Step>, running: Boolean): Boolean = running || steps.any { it.state != StepState.PENDING }

/** What the picture and the sentence under it on the home screen describe. */
enum class HomeShows {
    /** The pairing has not been read yet. */
    NOTHING,

    /** The device check: the one running, or the last one. */
    CHECK,

    /** Synchronising: the run in progress, or the last one. */
    SYNC,
}

/**
 * Which of the two the home screen describes, so that the picture and the sentence always tell
 * of the same event.
 *
 * A running check is shown, and so is the check in a build that does not synchronise or before
 * there is a machine. Otherwise it is whichever ended last: the check, for as long as the latest
 * synchronisation is still the one there was when the check ended. Just after the first pairing
 * that is the check, so its ticks stay until the first synchronisation. No two clocks are
 * compared, so a synchronisation stamped while the phone's clock was wrong cannot hide a later
 * check. A synchronisation in progress, and a damaged record of synchronising (whose way out is on
 * the home screen), always come first.
 */
fun homeShows(pairing: PairingState, checkRunning: Boolean, checkEnd: CheckEnd?, syncEnabled: Boolean, sync: SyncStatus): HomeShows {
    if (pairing == PairingState.Loading) return HomeShows.NOTHING
    if (checkRunning || !syncEnabled || pairing !is PairingState.Paired) return HomeShows.CHECK
    if (sync.running || sync.storeUnreadable) return HomeShows.SYNC
    return if (checkEnd != null && checkEnd.lastSyncAtMs == sync.lastReport?.atMs) HomeShows.CHECK else HomeShows.SYNC
}

/** The picture on the home screen, for what [homeShows] chose. */
fun homeLinks(shows: HomeShows, pairing: PairingState, steps: List<Step>, checkRunning: Boolean, sync: SyncStatus, healthAllowed: Boolean): Links =
    when (shows) {
        HomeShows.NOTHING -> Links(LinkState.NONE, LinkState.NONE)
        HomeShows.CHECK -> checkLinks(steps, pairing, checkRunning, healthAllowed)
        HomeShows.SYNC -> syncLinks(sync, healthAllowed)
    }

/** How a finished check went, in the terms the screens use to describe it. */
enum class CheckOutcome { PASSED, FAILED, STOPPED, INCOMPLETE }

/** "Passed" means the last step did, not merely that nothing went wrong. */
fun checkOutcome(steps: List<Step>): CheckOutcome = when {
    steps.any { it.state == StepState.CANCELLED } -> CheckOutcome.STOPPED
    steps.any { it.state == StepState.FAILED } -> CheckOutcome.FAILED
    steps.lastOrNull()?.state == StepState.PASSED -> CheckOutcome.PASSED
    else -> CheckOutcome.INCOMPLETE
}

/**
 * Whether the steps for pairing are shown under "Not paired". After a check that failed at
 * finding or pairing the machine, its own advice says the same and they are left out; after
 * one that failed for another reason (Health Connect, Bluetooth on the phone) they stay.
 */
fun pairingStepsShown(steps: List<Step>, paired: Boolean): Boolean {
    if (paired || checkOutcome(steps) != CheckOutcome.FAILED) return true
    val first = steps.firstOrNull { it.state == StepState.FAILED } ?: return true
    return first.onPhone || first.id != CheckSteps.SCAN && first.id != CheckSteps.PAIR
}

/** How the two legs stand during a device check, or after one. */
fun checkLinks(steps: List<Step>, pairing: PairingState, running: Boolean, healthAllowed: Boolean): Links = Links(
    machine = machineLink(steps, pairing, running),
    health = healthLink(steps, healthAllowed),
    // A failure that lies with the phone is marked on the phone, not on either link.
    phoneFault = steps.any { it.state == StepState.FAILED && it.onPhone && it.id != CheckSteps.HEALTH },
)

/** How the two legs stand after a synchronisation, or during one. */
fun syncLinks(sync: SyncStatus, healthAllowed: Boolean): Links {
    val idleHealth = if (healthAllowed) LinkState.READY else LinkState.NONE
    if (sync.running) return Links(LinkState.ACTIVE, idleHealth)
    if (sync.storeUnreadable) return Links(LinkState.READY, idleHealth, phoneFault = true)
    return when (sync.lastReport?.outcome) {
        // The Health Connect leg is ticked only when something is switched on to go there and is allowed to.
        SyncOutcome.COMPLETE -> Links(LinkState.OK, if (healthAllowed) LinkState.OK else LinkState.NONE)
        // Under way, with nothing wrong: no ticks until everything is across. And out of range is
        // the ordinary state of a phone that is not at home.
        SyncOutcome.PARTIAL, SyncOutcome.MACHINE_AWAY, null -> Links(LinkState.READY, idleHealth)
        SyncOutcome.BLUETOOTH_OFF, SyncOutcome.BLUETOOTH_NOT_ALLOWED, SyncOutcome.PHONE_CLOCK_WRONG, SyncOutcome.INTERNAL,
        -> Links(LinkState.READY, idleHealth, phoneFault = true)
        SyncOutcome.HEALTH_CONNECT_NOT_ALLOWED -> Links(LinkState.READY, LinkState.NONE)
        SyncOutcome.HEALTH_CONNECT_UNAVAILABLE, SyncOutcome.HEALTH_CONNECT_FAILED -> Links(LinkState.READY, LinkState.FAILED)
        // Shown only with a machine paired, so this is the leftover of a run made while there was none.
        SyncOutcome.NOT_PAIRED -> Links(LinkState.READY, idleHealth)
        SyncOutcome.PAIRING_REJECTED, SyncOutcome.WRONG_MACHINE, SyncOutcome.SOFTWARE_NOT_CONFIRMED, SyncOutcome.CLOCK_UNRELIABLE,
        SyncOutcome.CLOCK_CHANGED, SyncOutcome.CONNECTION_LOST, SyncOutcome.DATA_NOT_UNDERSTOOD,
        -> Links(LinkState.FAILED, idleHealth)
    }
}

/**
 * The machine-to-phone leg, from the steps that travel along it.
 *
 * A failure that lies with the phone itself (its storage, Bluetooth being off)
 * is not drawn as a broken link to the machine: the machine was never the
 * problem. A tick needs both a passed check and a pairing that still exists.
 */
private fun machineLink(steps: List<Step>, pairing: PairingState, running: Boolean): LinkState {
    val leg = steps.filter { it.id != CheckSteps.HEALTH }
    val healthDone = steps.firstOrNull { it.id == CheckSteps.HEALTH }?.state.let { it != StepState.PENDING && it != StepState.RUNNING }
    val machineFailed = leg.any { it.state == StepState.FAILED && !it.onPhone }
    val paired = pairing is PairingState.Paired
    return when {
        leg.any { it.state == StepState.RUNNING } -> LinkState.ACTIVE
        // Between two steps of a run, such as the pause after pairing.
        running && healthDone && !machineFailed && leg.any { it.state == StepState.PENDING } -> LinkState.ACTIVE
        machineFailed -> LinkState.FAILED
        paired && leg.lastOrNull()?.state == StepState.PASSED -> LinkState.OK
        paired -> LinkState.READY
        else -> LinkState.NONE
    }
}

/** The phone-to-Health-Connect leg. */
private fun healthLink(steps: List<Step>, allowed: Boolean): LinkState =
    when (steps.firstOrNull { it.id == CheckSteps.HEALTH }?.state) {
        StepState.RUNNING -> LinkState.ACTIVE
        StepState.PASSED -> LinkState.OK
        StepState.FAILED -> LinkState.FAILED
        else -> if (allowed) LinkState.READY else LinkState.NONE
    }
