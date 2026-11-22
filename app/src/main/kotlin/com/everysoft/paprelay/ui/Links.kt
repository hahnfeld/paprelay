// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.ui

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

/**
 * The picture on the home screen.
 *
 * With a machine paired, in a build that synchronises, it shows how synchronising stands.
 * While a check runs, or before there is a machine, it shows the check. It also shows the
 * check when the check has something to say and synchronising does not yet: just after the
 * first pairing, the check's ticks are worth more than two grey lines. Until the pairing has
 * been read it shows nothing in particular.
 */
fun homeLinks(
    pairing: PairingState,
    steps: List<Step>,
    checkRunning: Boolean,
    syncEnabled: Boolean,
    sync: SyncStatus,
    healthAllowed: Boolean,
): Links {
    if (pairing == PairingState.Loading) return Links(LinkState.NONE, LinkState.NONE)
    val syncSaysNothing = sync.lastReport == null && !sync.running && !sync.storeUnreadable
    val showSync = syncEnabled && pairing is PairingState.Paired && !checkRunning &&
        !(syncSaysNothing && checkStarted(steps, checkRunning))
    return if (showSync) syncLinks(sync, healthAllowed) else checkLinks(steps, pairing, checkRunning, healthAllowed)
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
