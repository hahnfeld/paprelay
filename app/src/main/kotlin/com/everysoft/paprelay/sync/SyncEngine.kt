// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.sync

import com.everysoft.paprelay.as11.As11AuthException
import com.everysoft.paprelay.as11.As11Exception
import com.everysoft.paprelay.as11.MachineClockReading
import com.everysoft.paprelay.as11.SpoolAddress
import com.everysoft.paprelay.as11.SpoolName
import com.everysoft.paprelay.as11.SpoolRound
import com.everysoft.paprelay.as11.decode.EventDecoder
import com.everysoft.paprelay.as11.decode.Firmware
import com.everysoft.paprelay.as11.decode.UsageEvent
import com.everysoft.paprelay.as11.decode.MinuteDecoder
import com.everysoft.paprelay.as11.decode.MinuteSignal
import com.everysoft.paprelay.as11.decode.RecordResult
import com.everysoft.paprelay.as11.decode.SpoolDecodeException
import com.everysoft.paprelay.as11.decode.SummaryDecoder
import com.everysoft.paprelay.as11.plan.ClockCorrection
import com.everysoft.paprelay.as11.plan.EventPlan
import com.everysoft.paprelay.as11.plan.NightPlan
import com.everysoft.paprelay.as11.plan.UsagePlan
import com.everysoft.paprelay.as11.plan.RespiratoryRatePlan
import com.everysoft.paprelay.as11.plan.SessionPlan
import com.everysoft.paprelay.ble.BluetoothOffException
import com.everysoft.paprelay.ble.NoAnswerException
import com.everysoft.paprelay.core.ClockOffset
import com.everysoft.paprelay.core.MachineTime
import com.everysoft.paprelay.core.nights.BreathingEvent
import com.everysoft.paprelay.core.nights.FhirObservations
import com.everysoft.paprelay.core.nights.Night
import com.everysoft.paprelay.core.nights.UsageGap
import com.everysoft.paprelay.core.nights.UsageMark
import com.everysoft.paprelay.core.nights.FhirResource
import com.everysoft.paprelay.core.sync.LedgerEntry
import com.everysoft.paprelay.core.sync.Reconcile
import com.everysoft.paprelay.core.sync.SessionReadings
import com.everysoft.paprelay.core.sync.SessionChange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.spec.KeySpec
import java.time.LocalDate
import java.time.ZoneId
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.math.abs

/** What the machine says it is, read in the connection the data comes from. */
class MachineIdentity(val serialNumber: String, val applicationIdentifier: String)

/** A proven, encrypted session with the paired machine. Read-only by construction of the client beneath it. */
interface MachineSession {
    suspend fun identity(): MachineIdentity

    suspend fun clock(): MachineClockReading

    suspend fun pull(log: SpoolName, from: SpoolAddress): SpoolRound
}

/** Opens a session with the paired machine, runs [block] in it, and closes it whatever happens. */
interface MachineConnector {
    /** A reason no connection can even be tried (Bluetooth off, no permission), or null. */
    fun notReady(): SyncOutcome?

    /** The serial number of the machine that was paired, or null if it has not been recorded yet. */
    fun pairedSerial(): String?

    suspend fun <T> withSession(block: suspend (MachineSession) -> T): T
}

/**
 * Health Connect refused or failed. [notAllowed] when the cause is a missing permission;
 * [rateLimited] when Health Connect has had enough calls for now and will take more later.
 */
class HealthSinkException(val notAllowed: Boolean, cause: Throwable? = null, val rateLimited: Boolean = false) :
    Exception("Health Connect did not accept the change", cause)

/** Where readings go. */
interface HealthSink {
    /** A reason nothing can be written (not installed, not allowed), or null. */
    suspend fun notReady(): SyncOutcome?

    /**
     * Carries out one session's change in Health Connect: its deletions, then its writes.
     * Returns only when Health Connect has confirmed both. The caller updates the ledger.
     *
     * @throws HealthSinkException if any part was not confirmed. Some of it may have been done;
     *   the change is then simply applied again later.
     */
    suspend fun apply(change: SessionChange)
}

/** Whether a night's figures can be written to the phone's health records. */
enum class RecordsState { READY, NOT_SUPPORTED, NOT_ALLOWED }

/** Where each night's figures go as health records, on a phone that keeps them. */
interface NightRecords {
    suspend fun state(): RecordsState

    /**
     * Makes ready to write, once at the start of a run.
     *
     * @return true if nothing of PAPRelay's was there: its place in the health records had to be
     *   made again, so whatever was written before is gone and must be written again.
     * @throws HealthSinkException if the health records could not be reached.
     */
    suspend fun open(): Boolean

    /**
     * Stores [resources], each replacing whatever was stored under its id before, and removes
     * [removeIds]. Returns only when both are confirmed.
     *
     * @throws HealthSinkException if any part was not confirmed; it is then simply done again later.
     */
    suspend fun write(resources: List<FhirResource>, removeIds: List<String>)

    /**
     * Stores [patient] in place of the Patient record stored before, or, with null, removes it. Returns
     * only when that is confirmed; removing one that is not there is no failure.
     *
     * @throws HealthSinkException if it was not confirmed; it is then simply done again later.
     */
    suspend fun writePatient(patient: FhirResource?)
}

/**
 * One synchronisation: connect, check it is the right machine, measure its clock, read the
 * per-minute log from where the last one left off, and bring Health Connect into step,
 * session by session.
 *
 * Every session is its own small transaction: the intent is stored, the change is applied,
 * and the result is stored. Wherever a run is cut off (the phone leaves the room, the app
 * is killed), what was finished stays finished, what was begun is marked as such and worked
 * out again, and the rest is taken up next time. Nothing here retries: a failed run ends,
 * says why, and the next one starts again.
 */
class SyncEngine(
    private val connector: MachineConnector,
    private val sink: HealthSink,
    private val store: SyncStore,
    private val nowMs: () -> Long,
    /** Called with each downloaded round, so that the bytes can be kept for a later version to decode. */
    private val keepRaw: suspend (SpoolName, SpoolRound) -> Unit = { _, _ -> },
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    /** A run that has not finished in this time is ended; what it has done so far stands. */
    private val limitMs: Long = RUN_LIMIT_MS,
    /** True when the phone's clock is known to be wrong. Every time written is worked out from it. */
    private val phoneClockWrong: () -> Boolean = { false },
    /** Turns a failure into a label for the saved report, with nothing private in it. */
    private val describe: (Throwable) -> String? = { null },
    /** Where the machine's nightly figures are kept, or null to leave them alone. */
    private val nights: NightStore? = null,
    /** The phone's health records, or null where there are none to write to. */
    private val records: NightRecords? = null,
    /** Where the machine's breathing events are kept, or null to leave them alone. */
    private val events: EventStore? = null,
    /** Where the person's name and date of birth are kept, when they have given them, or null for none. */
    private val patients: PatientStore? = null,
    /** Where the mask-on and mask-off marks of the morning preview are kept, or null to leave them alone. */
    private val usage: UsageStore? = null,
    /** The usage kinds that may be used: the confirmed ones. Tests name others. */
    private val usageKinds: Set<UsageEvent> = UsagePlan.CONFIRMED,
) {
    private val running = Mutex()

    /** The last serial number a tag was worked out for, and the tag: working one out is slow by design. */
    private var knownTag: Pair<String, String>? = null

    /** Runs one synchronisation, or returns null at once if one is already running. */
    suspend fun sync(): SyncReport? {
        if (!running.tryLock()) return null
        try {
            return attempt()
        } finally {
            running.unlock()
        }
    }

    /**
     * Runs [block] while no synchronisation is in progress, waiting for one to finish first.
     * Everything else that changes the stored state goes through here: a run keeps the state
     * in hand while it works, and a change made beside it would be overwritten, or overwrite it.
     */
    suspend fun <T> whileIdle(block: suspend () -> T): T = running.withLock { block() }

    /**
     * What stands in the way of a run before it has begun (Bluetooth off, a missing permission,
     * no pairing, a wrong phone clock), or null. Asks nothing of the machine.
     */
    suspend fun blockedBy(): SyncOutcome? =
        // Health Connect is not asked here: what cannot be written there is said at the end of a
        // run, after the nights have been brought to the phone, which needs nothing of it.
        connector.notReady() ?: if (phoneClockWrong()) SyncOutcome.PHONE_CLOCK_WRONG else null

    private class Stop(val outcome: SyncOutcome) : Exception()

    private class Progress(var state: SyncState) {
        var sessions = 0
        var written = 0
        var partial = false
        var sessionInProgress = false
    }

    private suspend fun attempt(): SyncReport {
        val startedState = try {
            store.read()
        } catch (e: Exception) {
            // Nothing can be recorded about this attempt either; the screen is told directly.
            return SyncReport(nowMs(), SyncOutcome.INTERNAL, 0, 0)
        }
        val progress = Progress(startedState)
        var failure: Throwable? = null
        val outcome = try {
            blockedBy()?.let { throw Stop(it) }
            // A link that stalls without breaking must not hold the machine, or this run, for ever.
            // Whatever was finished before the limit stays finished; the next run takes up the rest.
            val finished = withTimeoutOrNull(limitMs) { connector.withSession { session -> run(session, progress) } }
            if (finished == null || progress.partial) SyncOutcome.PARTIAL else SyncOutcome.COMPLETE
        } catch (e: CancellationException) {
            throw e
        } catch (e: Stop) {
            e.outcome
        } catch (e: NoAnswerException) {
            SyncOutcome.MACHINE_AWAY
        } catch (e: BluetoothOffException) {
            SyncOutcome.BLUETOOTH_OFF
        } catch (e: As11AuthException.PairingUnknown) {
            SyncOutcome.PAIRING_REJECTED
        } catch (e: As11AuthException.SessionProofRejected) {
            SyncOutcome.PAIRING_REJECTED
        } catch (e: As11Exception) {
            failure = e
            SyncOutcome.CONNECTION_LOST
        } catch (e: HealthSinkException) {
            failure = e.cause ?: e
            when {
                e.notAllowed -> SyncOutcome.HEALTH_CONNECT_NOT_ALLOWED
                // Not a fault: Health Connect takes so many calls in a while, and the next run carries on.
                e.rateLimited -> SyncOutcome.PARTIAL
                else -> SyncOutcome.HEALTH_CONNECT_FAILED
            }
        } catch (e: SpoolDecodeException) {
            failure = e
            SyncOutcome.DATA_NOT_UNDERSTOOD
        } catch (e: Exception) {
            failure = e
            SyncOutcome.INTERNAL
        }
        val now = nowMs()
        val code = failure?.let { runCatching { describe(it) }.getOrNull() }?.take(SyncReport.MAX_CODE)
        val report = SyncReport(now, outcome, progress.sessions, progress.written, code, progress.sessionInProgress)
        val finished = progress.state.with(
            lastReport = report,
            lastCompleteAtMs = if (outcome == SyncOutcome.COMPLETE) now else progress.state.lastCompleteAtMs,
        )
        // If even this cannot be stored the ledger written so far is still on disk; only the report is lost.
        runCatching { store.write(finished) }
        return report
    }

    private suspend fun run(session: MachineSession, progress: Progress) {
        val identity = session.identity()
        val expected = connector.pairedSerial()
        if (expected != null && expected != identity.serialNumber) throw Stop(SyncOutcome.WRONG_MACHINE)
        val firmware = Firmware.reportedByMachine(identity.applicationIdentifier)
        if (!firmware.layoutsAccepted) throw Stop(SyncOutcome.SOFTWARE_NOT_CONFIRMED)

        val tag = knownTag?.takeIf { it.first == identity.serialNumber }?.second
            ?: machineTag(identity.serialNumber).also { knownTag = identity.serialNumber to it }
        if (progress.state.machineTag != tag) {
            // Nothing is remembered yet, or what is remembered is another machine's (its record
            // outlived the forgetting of it). That machine's nights stay in Health Connect, as
            // forgetting promises; the record of them is of no use here, and this one starts empty.
            progress.state = progress.state.cleared().with(machineTag = tag)
            store.write(progress.state)
        }
        if (progress.state.rulesVersion != SessionReadings.RULES_VERSION) {
            // The rules for what a reading is have changed since the nights behind the resume point
            // were looked at (or this is the first run). All of them are looked at again.
            progress.state = progress.state.fromTheStart().with(rulesVersion = SessionReadings.RULES_VERSION)
            store.write(progress.state)
        }

        val (machineNow, offset) = measureClock(session)
        // A clock that has been set since the last run puts the nights recorded in between out
        // of reach: there is no telling which of them carry the old time and which the new.
        // No respiratory rate is written until the user has said how to treat them. The nightly
        // figures are dated by the machine's calendar and are still written (below).
        val clockChanged = progress.state.clock?.let { stepped(it, machineNow, offset) } ?: false
        // Whether the per-minute log's moduli are known for this software, or only assumed.
        val compared = firmware.perMinuteCompared
        if (progress.state.softwareSeen != identity.applicationIdentifier) {
            // Other software than last time. Sessions already on the machine were recorded by the
            // old one; only what begins from now on can show how the new one writes.
            progress.state = progress.state.with(
                softwareSeen = identity.applicationIdentifier,
                softwareSinceMs = when {
                    !compared -> null
                    // The compared version, on a first run, takes the sessions the machine already holds.
                    progress.state.softwareSeen == null -> null
                    else -> machineNow.epochMs
                },
                // Not compared: which sessions are its own is settled when its per-minute log is next read (below).
                softwareOwnFromMs = null,
                // Whatever was vouched for before is not what is running now, even if its name comes back.
                vouchedSoftware = null,
            )
        }
        if (progress.state.rewriteRequested) {
            // A rewrite was asked for: Health Connect may have been cleared, so the marks that say
            // "written" cannot be trusted. Clear them here, once, for nights and for sessions,
            // whichever of the two is switched on. After that this is an ordinary run: what it
            // writes is confirmed again, and what it does not reach is still marked next time.
            // Nights first: if the app dies between the two, this block runs again.
            nights?.let { nightStore ->
                val kept = readNights(nightStore)
                if (kept.byDate.values.any { it.recorded != null }) {
                    nightStore.write(Nights(kept.machineTag, kept.byDate.mapValues { (_, stored) -> StoredNight(stored.night, null) }, kept.planVersion))
                }
            }
            // Every ledger entry is marked as begun and not confirmed, which is exactly what makes a
            // session be written again, and reading starts from the beginning.
            val marked = progress.state.ledger.mapValues { (_, entry) ->
                LedgerEntry(
                    entry.machineTag, entry.firstMinuteEnd, entry.minutes, entry.fingerprint, entry.clockOffset, entry.zone, entry.version,
                    pendingMinutes = entry.possiblyHeld,
                )
            }
            // The Patient record too: what is there is not known, so it is written again, or removed.
            progress.state = progress.state.with(ledger = marked, rewriteRequested = false, patientWritten = SyncState.PATIENT_UNKNOWN).fromTheStart()
        }
        store.write(progress.state)

        // Session starts and breathing events are given in true time beside the machine's own, where
        // the clock's error is known: not in a run that finds the clock set since the last one, when
        // there is no telling which stamps carry the old time and which the new. Measured against the
        // last run, and, while respiratory rate is switched on, against its own baseline
        // ([clockChanged]): that one may be all there is (an installation from before this was kept),
        // and a change it found stands until the user has accepted it. With respiratory rate off its
        // baseline is not kept up, and says nothing about the clock now.
        val rateClockChanged = progress.state.respiratoryRate && clockChanged
        val correctionStepped = rateClockChanged || progress.state.correctionClock?.let { stepped(it, machineNow, offset) } == true
        val correction = ClockCorrection(offset.takeIf { !correctionStepped }, machineNow, nowMs())

        // The nights first. They are whole days by the machine's own calendar, so they need no
        // clock correction and nothing that follows can hold them up; and they are what matters most.
        val recordsFailure = nights(session, firmware, tag, machineNow, correction, progress)

        // Then respiratory rate, if it is switched on. Whatever stops it is said at the end of the run,
        // after the breathing events have been read: they need nothing of Health Connect.
        var rateFailure: Exception? = null
        if (progress.state.respiratoryRate) {
            try {
                respiratoryRate(session, identity, firmware, tag, machineNow, offset, clockChanged, progress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                rateFailure = e
            }
        }
        // Respiratory rate is off, or has been dealt with. (When it is off, the stored clock reading
        // is kept, so that a clock change is still noticed if it is switched on later.)

        // Last, the breathing events, unless the connection itself has failed: then there is nothing to
        // read them with, and the next run takes them up. Anything else that stopped respiratory rate
        // (Health Connect, a changed clock, a per-minute log that cannot be read) does not stop them. A
        // failure of their own is said at the end, like the others; it keeps nothing else from the phone.
        var eventsFailure: Exception? = null
        if (!connectionLost(rateFailure)) {
            try {
                breathingEvents(session, firmware, tag, machineNow, correction, progress)
                // Only now: had the events not been read, a clock set since the last run would not be seen again.
                progress.state = progress.state.with(correctionClock = ClockBaseline(offset.millis, machineNow.epochMs))
                store.write(progress.state)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                eventsFailure = e
            }
        }
        // Then, once mask on and off are confirmed, the marks for the morning preview: a convenience for
        // the screen, read last. Nothing of it is passed on, and nothing it does keeps anything else
        // from the phone. Only a lost connection is said, as it is for the rest.
        var usageFailure: Exception? = null
        if (usage != null && UsagePlan.ready(usageKinds) && !connectionLost(rateFailure) && !connectionLost(eventsFailure)) {
            try {
                usageMarks(session, firmware, tag, machineNow, correction)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                usageFailure = e
            }
        }
        (rateFailure ?: eventsFailure ?: recordsFailure ?: usageFailure?.takeIf { connectionLost(it) })?.let { throw it }
    }

    /**
     * Reads the usage log from a little before where the last run left off, and keeps the mask-on and
     * mask-off marks of the last night or two, each once. Anything it cannot read is left: the preview
     * then shows less, and the next run reads it again.
     */
    private suspend fun usageMarks(session: MachineSession, firmware: Firmware, tag: String, machineNow: MachineTime, correction: ClockCorrection) {
        val usageStore = usage ?: return
        val onDisk = usageStore.read()
        val kept = if (onDisk.machineTag == tag) onDisk else Usage.EMPTY
        val byId = LinkedHashMap<String, UsageMark>()
        for (mark in kept.marks) byId[mark.identity] = mark
        var address: SpoolAddress = SpoolAddress.FromMachineTime(
            maxOf(EARLIEST_START_MS, kept.resumeFromMs?.let { it - OVERLAP_MS } ?: (machineNow.epochMs - USAGE_WINDOW_MS)),
        )
        var newest: Long? = null
        var lastBeforeTrouble: Long? = null
        var troubled = false
        var readToEnd = false
        var rounds = 0
        // Where records could not be read: after the last one read before, before the first read after.
        val gaps = ArrayList<UsageGap>()
        var lastRead: Long? = null
        var gapAfter: Long? = null
        var inGap = false
        while (++rounds <= MAX_USAGE_ROUNDS) {
            val round = session.pull(SpoolName.USAGE_EVENTS, address)
            keepRaw(SpoolName.USAGE_EVENTS, round)
            val log = try {
                EventDecoder.usage(round.data, firmware)
            } catch (e: SpoolDecodeException) {
                troubled = true
                if (!inGap) gapAfter = lastRead
                inGap = true
                break
            }
            for (entry in log.entries) {
                when (entry) {
                    is RecordResult.Failed -> {
                        troubled = true
                        if (!inGap) gapAfter = lastRead
                        inGap = true
                    }
                    is RecordResult.Decoded -> {
                        val at = entry.value.time.epochMs
                        if (inGap) {
                            gaps += UsageGap(gapAfter, at)
                            inGap = false
                        }
                        lastRead = at
                        newest = maxOf(newest ?: Long.MIN_VALUE, at)
                        if (!troubled) lastBeforeTrouble = newest
                        UsagePlan.mark(log, entry.value, correction, usageKinds)?.let { byId.putIfAbsent(it.identity, it) }
                    }
                }
            }
            val next = round.next
            if (next == null) {
                readToEnd = true
                break
            }
            address = SpoolAddress.Continuation(next)
        }
        // Only what the preview can need: marks since a day before the newest finished night ended.
        val newestEnd = nights?.let { runCatching { it.read() }.getOrNull() }?.byDate?.values
            ?.filter { it.night.complete }?.maxOfOrNull { it.night.end.toInstant().toEpochMilli() }
        val keepFrom = maxOf(newestEnd?.let { it - DAY_MS } ?: Long.MIN_VALUE, machineNow.epochMs - 3 * DAY_MS)
        val marks = byId.values.filter { it.machineTime.epochMs >= keepFrom }.sortedBy { it.machineTime.epochMs }.takeLast(UsageStore.MAX_MARKS)
        if (inGap) gaps += UsageGap(gapAfter, null)
        // A record that cannot be read holds the place to resume from before it, but only for as long
        // as its night can matter to the preview: a record that keeps failing must not stop the rest.
        val resume = when {
            !troubled || newest == null -> newest ?: kept.resumeFromMs
            else -> minOf(maxOf(lastBeforeTrouble ?: kept.resumeFromMs ?: Long.MIN_VALUE, machineNow.epochMs - USAGE_WINDOW_MS), newest!!)
        }
        // The last moment the log was read to its end. Where a record of a night could not be read, the
        // preview gives that night no total and a running session no time (see NightPreview).
        val read = readToEnd
        val knownUntil = if (read) machineNow.epochMs else kept.knownUntilMs
        val knownUntilCorrected = if (read) correction.of(machineNow) else kept.knownUntilCorrectedMs
        usageStore.write(Usage(tag, marks, resume, knownUntil, knownUntilCorrected, gaps))
    }

    /**
     * Reads the per-minute log from where the last run left off and brings Health Connect's
     * respiratory rate into step, session by session.
     */
    private suspend fun respiratoryRate(
        session: MachineSession,
        identity: MachineIdentity,
        firmware: Firmware,
        tag: String,
        machineNow: MachineTime,
        offset: ClockOffset,
        clockChanged: Boolean,
        progress: Progress,
    ) {
        val compared = firmware.perMinuteCompared
        // What stands in the way of the respiratory rate (Health Connect missing, or not allowing it) is said
        // now that the nights are in: it is no reason to keep those from the phone.
        sink.notReady()?.let { throw Stop(it) }
        if (clockChanged) throw Stop(SyncOutcome.CLOCK_CHANGED)
        progress.state = progress.state.with(clock = ClockBaseline(offset.millis, machineNow.epochMs))
        store.write(progress.state)

        // On software that was not compared, a session is the software's own only if it was not yet
        // on the machine when the per-minute log was first read under that software: what was there
        // then, an earlier version may have recorded. This is settled once, in a run that reads the
        // log to its end, and kept as the moment after the last minute then present. (Not as "now":
        // a clock that was once set back leaves sessions stamped later than now.) It is settled
        // again if it is missing, or if it lies after the machine's present time: the clock was set
        // back past it, or sessions stamped later than now were present when it was settled. While
        // it is being settled nothing is the software's own, and whatever was vouched for has to be
        // vouched for again.
        val settled = if (compared) null else progress.state.softwareOwnFromMs?.takeIf { it <= machineNow.epochMs }
        val settling = !compared && settled == null
        if (settling && progress.state.vouchedSoftware != null) {
            progress.state = progress.state.with(vouchedSoftware = null)
            store.write(progress.state)
        }
        // A session whose first minute is before this is not the software's own. Null on the compared version.
        val ownFrom: Long? = when {
            compared -> null
            settling -> Long.MAX_VALUE
            else -> settled
        }
        var presentUntil = machineNow.epochMs
        var readToEnd = false
        // Records that failed without saying when they began, met since the last record that could be dated.
        var undated = 0
        var vouched = progress.state.vouchedSoftware == identity.applicationIdentifier
        val vouchFrom = if (compared) progress.state.softwareSinceMs else null
        val from = maxOf(EARLIEST_START_MS, progress.state.resumeFromMs?.let { it - OVERLAP_MS } ?: (machineNow.epochMs - FIRST_WINDOW_MS))
        var address: SpoolAddress = SpoolAddress.FromMachineTime(from)
        var rounds = 0
        var emptyRounds = 0
        var skippedInProgress = false
        // The start of the newest session seen, and of the last one dealt with before anything went wrong.
        var newest: Long? = null
        var lastBeforeTrouble: Long? = null
        var troubled = false
        // Short sessions that are waiting for a long one to vouch for the software, in order.
        val waiting = ArrayList<SessionPlan.Planned>()

        suspend fun write(plan: SessionPlan.Planned) {
            val key = plan.readings.key
            val now = nowMs()
            val change = Reconcile.session(plan.readings, progress.state.ledger[key], offset, zone(), now) ?: return
            // A reading that has not happened yet means the clock correction is wrong.
            if (change.writes.any { it.time.toEpochMilli() > now + FUTURE_ALLOWANCE_MS }) throw Stop(SyncOutcome.CLOCK_UNRELIABLE)
            // The intent first: from here on the ledger covers whatever is about to exist.
            storeEntry(progress, key, change.intent)
            sink.apply(change)
            // Confirmed, and stored before the next session is begun: this one is finished for good.
            storeEntry(progress, key, change.confirmed)
            progress.written += change.writes.size
        }

        fun trouble() {
            progress.partial = true
            troubled = true
        }

        // What is finished stays finished even if this run does not reach its end: the place to
        // resume from moves up as sessions are dealt with. Never past one that is waiting, and past
        // one that could not be dealt with only as far as the hold limit allows (see below).
        fun checkpoint() {
            if (waiting.isNotEmpty()) return
            val reached = if (!troubled) {
                lastBeforeTrouble
            } else {
                newest?.let { minOf(maxOf(lastBeforeTrouble ?: progress.state.resumeFromMs ?: Long.MIN_VALUE, machineNow.epochMs - HOLD_LIMIT_MS), it) }
            } ?: return
            if (reached > (progress.state.resumeFromMs ?: Long.MIN_VALUE)) {
                progress.state = progress.state.with(resumeFromMs = reached)
                store.write(progress.state)
            }
        }

        while (true) {
            if (++rounds > MAX_ROUNDS) {
                progress.partial = true
                break
            }
            val round = session.pull(SpoolName.ONE_MINUTE, address)
            keepRaw(SpoolName.ONE_MINUTE, round)
            val log = MinuteDecoder.decode(round.data, firmware)
            // Stop if the machine keeps saying there is more but sends nothing.
            if (log.entries.isNotEmpty()) {
                emptyRounds = 0
            } else if (++emptyRounds > MAX_EMPTY_ROUNDS) {
                progress.partial = true
                break
            }
            // Something in this download failed the way a misread layout would. A long record that
            // decoded cleanly beside it answers the doubt: the layout is being read as it is meant,
            // and what failed was that one record. With none, the short records are not believed.
            // (On software that was not compared, a record that is not the software's own answers nothing.)
            val doubtful = log.doubted && log.decoded.none { it.sampleCount >= VOUCH_SAMPLES && (ownFrom == null || it.firstMinuteEnd.epochMs >= ownFrom) }
            for (entry in log.entries) {
                // A stop asked for from outside is heard between sessions, never inside one.
                currentCoroutineContext().ensureActive()
                val record = when (entry) {
                    is RecordResult.Decoded -> entry.value
                    // Not understood: its minutes are unknown. The sessions after it are still
                    // written (one unreadable night must not hold up every night that follows),
                    // but the place to resume from stays before it, so that it is read again.
                    is RecordResult.Failed -> {
                        val began = entry.startedAt?.epochMs
                        when {
                            ownFrom == null -> trouble()
                            // Not the software's own: it would not be written if it could be read.
                            began != null && began < ownFrom -> Unit
                            // No telling yet when it was recorded. The log is in time order: what follows will say.
                            began == null -> undated++
                            else -> trouble()
                        }
                        continue
                    }
                }
                newest = record.firstMinuteEnd.epochMs
                if (settling) presentUntil = maxOf(presentUntil, record.lastMinuteEnd.epochMs + 1)
                if (ownFrom != null && record.firstMinuteEnd.epochMs < ownFrom) {
                    // Not the software's own, so which software recorded it is not known, and with
                    // moduli that are only assumed that is not good enough to write it. Whatever
                    // failed just before it is older still.
                    undated = 0
                    // One thing is known of a session that has an entry in the ledger: this app wrote
                    // it before, under software it had reason to trust. Its bytes have not changed, so
                    // it is kept in order as usual (written again if that was asked for, or finished
                    // if a write was cut short). Nothing else of that age is touched, now or later.
                    val known = try {
                        RespiratoryRatePlan.session(record, tag, progress.state.leakLimitLitresPerMinute) as? SessionPlan.Planned
                    } catch (e: IllegalArgumentException) {
                        null
                    } catch (e: IllegalStateException) {
                        null
                    }
                    if (known != null && progress.state.ledger[known.readings.key] != null) {
                        write(known)
                        progress.sessions++
                    }
                    if (!troubled && waiting.isEmpty()) lastBeforeTrouble = newest
                    checkpoint()
                    continue
                }
                if (undated > 0) {
                    // What failed came after everything older, so it is the software's own.
                    undated = 0
                    trouble()
                }
                if (machineNow.epochMs - record.lastMinuteEnd.epochMs in -FUTURE_ALLOWANCE_MS until SETTLE_MS) {
                    // Its last minute has only just been recorded: the session may still be going on.
                    // It is left until it has ended, so that a night is written once, whole, and
                    // nothing depends on how the machine presents a record it is still adding to.
                    // The next run starts at it again.
                    skippedInProgress = true
                    if (!troubled && waiting.isEmpty()) lastBeforeTrouble = newest
                    continue
                }
                if (doubtful && record.sampleCount < VOUCH_SAMPLES) {
                    // A misreading can pass in a record this short. It is read again another time.
                    trouble()
                    continue
                }
                val plan = try {
                    RespiratoryRatePlan.session(record, tag, progress.state.leakLimitLitresPerMinute)
                } catch (e: IllegalArgumentException) {
                    // A record no rule was written for (far too long, say) is skipped, like one that cannot be read.
                    trouble()
                    continue
                } catch (e: IllegalStateException) {
                    trouble()
                    continue
                }
                when (plan) {
                    is SessionPlan.Unplanned -> if (plan.minutesUnknown) trouble()
                    is SessionPlan.Planned -> {
                        val canVouch = if (compared) {
                            record.sampleCount >= VOUCH_SAMPLES && (vouchFrom == null || record.firstMinuteEnd.epochMs >= vouchFrom)
                        } else {
                            // Nothing was compared for this software, so the record alone has to show that
                            // it is read as it was meant: an hour or more, with every known signal readable.
                            // All seven then agree on its length, and none holds a value the machine cannot
                            // report (a signal that does is not among the readable); with a modulus wrong,
                            // one or the other fails. It is the software's own, or it would not have got
                            // this far, and it must not be stamped later than now.
                            record.sampleCount >= STRICT_VOUCH_SAMPLES && record.signals.containsAll(KNOWN_SIGNALS) &&
                                record.lastMinuteEnd.epochMs <= machineNow.epochMs + FUTURE_ALLOWANCE_MS
                        }
                        if (!vouched && canVouch) {
                            vouched = true
                            progress.state = progress.state.with(vouchedSoftware = identity.applicationIdentifier)
                            store.write(progress.state)
                            // The short sessions that were waiting for this are written first, in their order.
                            while (waiting.isNotEmpty()) {
                                currentCoroutineContext().ensureActive()
                                write(waiting.first())
                                waiting.removeAt(0)
                                progress.sessions++
                            }
                        }
                        if (!vouched) {
                            // Too short, or too old, to show that this software's data is read as it
                            // is meant. It waits for a session that can.
                            waiting += plan
                            continue
                        }
                        write(plan)
                    }
                }
                progress.sessions++
                if (!troubled && waiting.isEmpty()) lastBeforeTrouble = newest
                checkpoint()
            }
            checkpoint()
            val next = round.next
            if (next == null) {
                readToEnd = true
                break
            }
            address = SpoolAddress.Continuation(next)
        }
        // A failure with nothing after it to date it by is taken to be the software's own. (While the
        // software's own sessions are being settled, everything on the machine is older.)
        if (undated > 0 && !settling) trouble()
        if (settling && readToEnd) {
            // Everything now on the machine was there before the software's own sessions begin.
            progress.state = progress.state.with(softwareOwnFromMs = presentUntil)
        }
        // Nothing long enough came to vouch for the software: the sessions wait for the next run. On
        // the compared version that is a run that did not finish. On any other it is how things stand
        // until the software has recorded a full night, and nothing has gone wrong.
        val unvouched = waiting.isNotEmpty()
        if (unvouched && compared) trouble()
        // Where the next run begins: at the newest session (it may still be in progress and grow),
        // or, if something could not be dealt with, before it, so that it is read again. Where the
        // trouble came before any session that could be dealt with, the place stays where it was.
        // How far back it is kept is bounded: never more than the hold limit before now. A night
        // that still cannot be read when it is that old stops being asked for, or every run would
        // read months of history again for its sake.
        val resume = when {
            // On the compared version, sessions that are only waiting for the software to be vouched
            // for can be read; they are never given up for being old. On any other, the wait may
            // have no end (software whose sessions never vouch), so it is bounded like the rest.
            unvouched && compared -> lastBeforeTrouble ?: progress.state.resumeFromMs
            unvouched || troubled -> minOf(
                maxOf(lastBeforeTrouble ?: progress.state.resumeFromMs ?: Long.MIN_VALUE, machineNow.epochMs - HOLD_LIMIT_MS),
                newest ?: Long.MAX_VALUE,
            )
            else -> newest ?: progress.state.resumeFromMs
        }
        progress.state = progress.state.with(resumeFromMs = resume)
        store.write(progress.state)
        progress.sessionInProgress = skippedInProgress
    }

    /**
     * Brings the stored nights up to date from the machine's daily summaries, and passes on to
     * the phone's health records every finished night that is new or has changed.
     *
     * The nights are kept before anything is passed on, so that the app can show them whatever
     * becomes of the rest. A failure to pass them on is returned, not thrown, so that the caller
     * can finish its other work first.
     */
    private suspend fun nights(
        session: MachineSession,
        firmware: Firmware,
        tag: String,
        machineNow: MachineTime,
        correction: ClockCorrection,
        progress: Progress,
    ): HealthSinkException? {
        val nightStore = nights ?: return null
        val onDisk = readNights(nightStore)
        if (onDisk.machineTag != null && onDisk.machineTag != tag) {
            // Another machine's nights are not this one's. They are put aside, not thrown away, and the list starts empty.
            nightStore.setAside(PREVIOUS)
        }
        val before = if (onDisk.machineTag == tag) onDisk.byDate else emptyMap()
        val byDate = LinkedHashMap(before)
        var changed = onDisk.machineTag != tag
        // Nights made under another plan (one that gave fewer figures, say) are read again, as far back
        // as the machine holds them, once: the plan's version is kept only when the history was read to its end.
        var planVersion = if (onDisk.machineTag == tag) onDisk.planVersion else null
        val rereading = planVersion != NightPlan.VERSION
        // What this download has already given for a date: the start of its day, and when the machine wrote it.
        val given = HashMap<LocalDate, Pair<Long, Long?>>()
        val disputed = HashSet<LocalDate>()
        // From a little before the end of the newest finished night: the machine may still amend a day it has closed.
        val newestEnd = byDate.values.filter { it.night.complete }.maxOfOrNull { it.night.end.toInstant().toEpochMilli() }
        val from = maxOf(EARLIEST_START_MS, newestEnd?.takeIf { !rereading }?.let { it - NIGHT_OVERLAP_MS } ?: (machineNow.epochMs - FIRST_WINDOW_MS))
        var address: SpoolAddress = SpoolAddress.FromMachineTime(from)
        var rounds = 0
        var emptyRounds = 0
        while (true) {
            if (++rounds > MAX_ROUNDS) {
                progress.partial = true
                break
            }
            val round = session.pull(SpoolName.SUMMARY, address)
            keepRaw(SpoolName.SUMMARY, round)
            val log = SummaryDecoder.decode(round.data, firmware)
            if (log.entries.isNotEmpty()) {
                emptyRounds = 0
            } else if (++emptyRounds > MAX_EMPTY_ROUNDS) {
                progress.partial = true
                break
            }
            for (entry in log.entries) {
                val record = when (entry) {
                    is RecordResult.Decoded -> entry.value
                    is RecordResult.Failed -> {
                        progress.partial = true
                        continue
                    }
                }
                val night = NightPlan.night(record, machineNow, correction)?.let { keepCorrections(it, before[it.date]?.night) }
                if (night == null) {
                    // A record that cannot stand as a night. For a day still being written that is to
                    // be expected; for one that has ended, a night is missing and the run says so.
                    if (record.periodEnd <= machineNow) progress.partial = true
                    continue
                }
                if (night.date in disputed) continue
                val earlier = given[night.date]
                // What is already kept for the date, from an earlier run, counts as a claim on it too.
                val keptStart = before[night.date]?.night?.start?.toInstant()?.toEpochMilli()
                if ((earlier != null && earlier.first != record.periodStart.epochMs) || (keptStart != null && keptStart != record.periodStart.epochMs)) {
                    // Two different days claim one date: the machine's clock or zone was set. Which of
                    // them the date belongs to cannot be told, so neither is believed, and what was
                    // kept for it before stays as it was.
                    disputed += night.date
                    val kept = before[night.date]
                    if (kept != null) byDate[night.date] = kept else byDate.remove(night.date)
                    progress.partial = true
                    continue
                }
                // The same day twice: the record the machine wrote later stands.
                val stamp = record.recordTimestamp?.epochMs
                if (earlier != null && (stamp ?: Long.MIN_VALUE) < (earlier.second ?: Long.MIN_VALUE)) continue
                given[night.date] = record.periodStart.epochMs to stamp
                val held = byDate[night.date]
                if (held == null || held.night.fingerprint() != night.fingerprint()) {
                    byDate[night.date] = StoredNight(night, held?.recorded)
                    changed = true
                }
            }
            val next = round.next
            if (next == null) {
                if (rereading) {
                    planVersion = NightPlan.VERSION
                    changed = true
                }
                break
            }
            address = SpoolAddress.Continuation(next)
        }
        // The oldest go when there are more than anyone will look at.
        while (byDate.size > MAX_NIGHTS) {
            byDate.remove(byDate.keys.min())
            changed = true
        }
        if (changed) nightStore.write(Nights(tag, byDate, planVersion))

        val records = records ?: return null
        if (!progress.state.healthRecords) return null
        when (records.state()) {
            RecordsState.NOT_SUPPORTED -> return null
            // Switched on and not allowed: the nights are on the phone, and the run says what is missing.
            RecordsState.NOT_ALLOWED -> return HealthSinkException(notAllowed = true)
            RecordsState.READY -> Unit
        }
        // The person's details, when given: every record then names the Patient, and the marks say which
        // details they were written with (the random mark saved with them, which tells nothing of them), so a
        // change writes them again.
        val record = patients?.read() ?: PatientRecord.Absent
        val details = (record as? PatientRecord.Loaded)?.details
        var unsaved = 0
        return try {
            val fresh = records.open()
            if (fresh && progress.state.patientWritten != null) {
                // The place was made again: no Patient record is there.
                progress.state = progress.state.with(patientWritten = null)
                store.write(progress.state)
            }
            val wanted = when (record) {
                is PatientRecord.Loaded -> record.mark
                PatientRecord.Absent -> null
                // Not known now (the person is asked to enter them again): what is in the health records is left
                // as it is, and a record written meanwhile names the Patient record only if one is known to be there.
                PatientRecord.Unreadable, PatientRecord.Unavailable -> progress.state.patientWritten?.takeIf { it != SyncState.PATIENT_UNKNOWN }
            }
            if (details != null && progress.state.patientWritten != wanted) {
                // Before any record names it.
                records.writePatient(FhirObservations.patient(details))
                progress.state = progress.state.with(patientWritten = wanted)
                store.write(progress.state)
                progress.written++
            }
            if (fresh && byDate.values.any { it.recorded != null }) {
                // PAPRelay's place in the health records was not there and has been made again: the
                // user removed it, or Health Connect's data was cleared. Nothing recorded before is
                // there now, whatever is remembered here.
                for ((date, stored) in byDate.toMap()) byDate[date] = StoredNight(stored.night, null)
                // Saved at once: if the run dies here, the next finds the place there and would believe the old marks.
                nightStore.write(Nights(tag, byDate, planVersion))
            }
            for (date in byDate.keys.sorted()) {
                currentCoroutineContext().ensureActive()
                val stored = byDate.getValue(date)
                // What was written, and in which form: a change in either means writing it again.
                val mark = "${FhirObservations.FORMAT}:${stored.night.healthFingerprint()}" + (wanted?.let { ":$it" } ?: "")
                if (!stored.night.complete || stored.recorded == mark) continue
                val resources = FhirObservations.forNight(stored.night, tag, withSubject = wanted != null)
                // Whatever the night could have had before and has no longer must not linger. Asked for
                // every time, known to be there or not: what was recorded before may be forgotten here
                // (a rewrite, a lost file) and still be there.
                val gone = FhirObservations.idsFor(stored.night, tag) - resources.map { it.id }.toSet()
                records.write(resources, gone)
                byDate[date] = StoredNight(stored.night, mark)
                progress.written += resources.size
                if (++unsaved >= SAVE_EVERY_NIGHTS) {
                    nightStore.write(Nights(tag, byDate, planVersion))
                    unsaved = 0
                }
            }
            if (record == PatientRecord.Absent && progress.state.patientWritten != null) {
                // Removed by the person: once no record names it any more, the Patient record goes too.
                if (unsaved > 0) {
                    nightStore.write(Nights(tag, byDate, planVersion))
                    unsaved = 0
                }
                records.writePatient(null)
                progress.state = progress.state.with(patientWritten = null)
                store.write(progress.state)
            }
            null
        } catch (e: HealthSinkException) {
            e
        } finally {
            if (unsaved > 0) runCatching { nightStore.write(Nights(tag, byDate, planVersion)) }
        }
    }

    /**
     * Copies the breathing events from where the last run left off (a day before, as the machine may
     * add to what it has written), each once. Kinds that are not passed on are not kept. A record
     * that cannot be read leaves the run short and is read again next time, for as long as the hold
     * limit allows.
     */
    private suspend fun breathingEvents(session: MachineSession, firmware: Firmware, tag: String, machineNow: MachineTime, correction: ClockCorrection, progress: Progress) {
        val eventStore = events ?: return
        val onDisk = eventStore.read()
        val kept = if (onDisk.machineTag == tag) onDisk else Events.EMPTY
        // What is kept stands: read again, an event keeps the corrected time it was first given.
        val byId = LinkedHashMap<String, BreathingEvent>()
        for (event in kept.all) byId[event.identity] = event
        var resume = kept.resumeFromMs
        var changed = onDisk.machineTag != tag
        // Events kept under another set of kinds are read again from the start, once: the kinds that were
        // added are in the nights before too. The re-reading is marked as begun, so that a history longer
        // than one run takes is taken up where it reached, and the set is kept only once the log was read
        // to its end.
        val pending = PENDING + EventPlan.VERSION
        var kinds = kept.kindsVersion
        if (kinds != EventPlan.VERSION && kinds != pending) {
            kinds = pending
            resume = null
            // Stored at once: a run cut off in its first round still leaves the re-reading begun.
            eventStore.write(Events(tag, byId.values, null, kinds))
            changed = false
        }
        val rereading = kinds == pending
        var address: SpoolAddress = SpoolAddress.FromMachineTime(
            maxOf(EARLIEST_START_MS, resume?.let { it - OVERLAP_MS } ?: (machineNow.epochMs - FIRST_WINDOW_MS)),
        )
        var rounds = 0
        var emptyRounds = 0
        var bytes = 0L
        var troubled = false
        // The newest stamp read, and the newest read before anything failed.
        var newest: Long? = null
        var lastBeforeTrouble: Long? = null

        fun reached(): Long? = if (!troubled || newest == null) {
            // Nothing read at all is no reason to move: on a first run, the history is still all to be read.
            newest ?: resume
        } else {
            minOf(maxOf(lastBeforeTrouble ?: resume ?: Long.MIN_VALUE, machineNow.epochMs - HOLD_LIMIT_MS), newest ?: Long.MAX_VALUE)
        }

        fun save() {
            val now = reached()
            if (!changed && now == resume) return
            eventStore.write(Events(tag, byId.values, now, kinds))
            resume = now
            changed = false
        }

        while (true) {
            if (++rounds > MAX_EVENT_ROUNDS || bytes > MAX_EVENT_BYTES) {
                // A long history is taken up by the next run, from where this one reached.
                progress.partial = true
                break
            }
            val round = session.pull(SpoolName.RESPIRATORY_EVENTS, address)
            keepRaw(SpoolName.RESPIRATORY_EVENTS, round)
            bytes += round.data.size
            val log = try {
                EventDecoder.respiratory(round.data, firmware)
            } catch (e: SpoolDecodeException) {
                // Not this log at all, or a layout not known: nothing in it can be told apart. The nights
                // and the respiratory rate do not depend on it, so the run goes on without it.
                troubled = true
                progress.partial = true
                break
            }
            if (log.entries.isNotEmpty()) {
                emptyRounds = 0
            } else if (++emptyRounds > MAX_EMPTY_ROUNDS) {
                progress.partial = true
                break
            }
            for (entry in log.entries) {
                when (entry) {
                    is RecordResult.Failed -> {
                        // The events around it are kept; the list is short by at least one.
                        troubled = true
                        progress.partial = true
                    }
                    is RecordResult.Decoded -> {
                        val record = entry.value
                        newest = maxOf(newest ?: Long.MIN_VALUE, record.time.epochMs)
                        if (!troubled) lastBeforeTrouble = newest
                        val event = EventPlan.event(log, record, correction) ?: continue
                        if (byId.putIfAbsent(event.identity, event) == null) changed = true
                    }
                }
            }
            val next = round.next
            if (next == null && rereading) {
                kinds = EventPlan.VERSION
                changed = true
            }
            save()
            address = SpoolAddress.Continuation(next ?: break)
        }
        // As many as the nights kept: events from before the oldest night go, and the oldest go beyond the bound.
        val oldestNight = nights?.let { runCatching { it.read() }.getOrNull() }?.byDate?.values?.minOfOrNull { it.night.start.toInstant().toEpochMilli() }
        if (oldestNight != null) changed = byId.values.removeAll { it.machineTime.epochMs < oldestNight } || changed
        if (byId.size > EventStore.MAX_EVENTS) {
            val keep = byId.values.sortedByDescending { it.machineTime.epochMs }.take(EventStore.MAX_EVENTS).map { it.identity }.toSet()
            byId.keys.retainAll(keep)
            changed = true
        }
        save()
    }

    /**
     * [night] with each session's corrected start as it was first given, where [kept] has the same
     * session: a correction, once made, is not made again with a later measurement.
     */
    private fun keepCorrections(night: Night, kept: Night?): Night {
        val list = night.sessionList ?: return night
        val before = kept?.sessionList?.associateBy { it.start.epochMs to it.minutes } ?: return night
        val merged = list.map { session -> before[session.start.epochMs to session.minutes]?.let { session.withCorrected(it.correctedMs) } ?: session }
        if (merged == list) return night
        return Night(night.date, night.zoneOffsetMinutes, night.usageMinutes, night.sessions, night.hundredths, night.complete, night.hours, merged)
    }

    /** Whether [failure] is the connection itself failing, after which nothing more can be read in this run. */
    private fun connectionLost(failure: Exception?): Boolean =
        failure is As11Exception || failure is NoAnswerException || failure is BluetoothOffException

    /** Whether the clock moved, since [before], by more than it drifts: it was set. */
    private fun stepped(before: ClockBaseline, machineNow: MachineTime, offset: ClockOffset): Boolean {
        val days = abs(machineNow.epochMs - before.machineMs) / DAY_MS
        return abs(offset.millis - before.offsetMs) > CLOCK_STEP_MS + days * DRIFT_PER_DAY_MS
    }

    /** The nights as kept. A file that cannot be read is put aside under another name, and the list starts empty. */
    private fun readNights(nightStore: NightStore): Nights = try {
        nightStore.read()
    } catch (e: NightsUnreadableException) {
        nightStore.setAside(DAMAGED)
        Nights.EMPTY
    }

    private fun storeEntry(progress: Progress, key: String, entry: LedgerEntry?) {
        val ledger = LinkedHashMap(progress.state.ledger)
        if (entry == null) ledger.remove(key) else ledger[key] = entry
        progress.state = progress.state.with(ledger = ledger)
        store.write(progress.state)
    }

    /**
     * Reads the machine's clock three times and uses the reading with the shortest round
     * trip, which is the one least blurred by the radio. The three must agree: a machine
     * whose clock cannot be read steadily gives no time worth correcting.
     */
    private suspend fun measureClock(session: MachineSession): Pair<MachineTime, ClockOffset> {
        val readings = List(CLOCK_READINGS) { session.clock() }
        val offsets = readings.map { it.offsetMs }
        if (offsets.max() - offsets.min() > CLOCK_AGREEMENT_MS) throw Stop(SyncOutcome.CLOCK_UNRELIABLE)
        val best = readings.minBy { it.roundTripMs }
        if (best.roundTripMs > MAX_ROUND_TRIP_MS || abs(best.offsetMs) > MAX_OFFSET_MS) throw Stop(SyncOutcome.CLOCK_UNRELIABLE)
        val machineNow = MachineTime(best.machineEpochMs)
        return machineNow to ClockOffset.measured(machineNow, trueNowMs = best.sentAtMs + best.roundTripMs / 2)
    }

    companion object {
        /** How far back the first synchronisation asks for. The machine sends what it still has. */
        const val FIRST_WINDOW_MS = 400L * 24 * 60 * 60 * 1000

        /** Each later one starts this much before the newest session already dealt with. */
        const val OVERLAP_MS = 24L * 60 * 60 * 1000
        const val MAX_ROUNDS = 2_000

        /** A change in the measured offset beyond this, plus ordinary drift, means the clock was set. */
        const val CLOCK_STEP_MS = 30_000L
        const val DRIFT_PER_DAY_MS = 3_000L
        const val DAY_MS = 24L * 60 * 60 * 1000

        /** How far back the place to resume from is kept for the sake of a night that cannot be read. */
        const val HOLD_LIMIT_MS = 30 * DAY_MS

        /** The names nights are put aside under. */
        const val DAMAGED = "damaged"
        const val PREVIOUS = "previous"

        /** How far before the newest finished night the summaries are asked for again. */
        const val NIGHT_OVERLAP_MS = 3 * DAY_MS

        /** How far back the usage log is read the first time, and how many rounds a run reads of it. */
        const val USAGE_WINDOW_MS = 2 * 24L * 60 * 60 * 1000
        const val MAX_USAGE_ROUNDS = 10

        /** Marks a set of kinds whose history is being read again, and not yet to its end. */
        private const val PENDING = "reading:"

        /** Bounds on one run's reading of the breathing events; a longer history is taken up by the next. */
        const val MAX_EVENT_ROUNDS = 40
        const val MAX_EVENT_BYTES = 4_000_000L

        /** How many nights are kept on the phone: more than three years. */
        const val MAX_NIGHTS = 1200

        /** How often the record of what has been passed on is saved while many nights are passed on at once. */
        const val SAVE_EVERY_NIGHTS = 20

        /** Sessions of at least this many minutes show that a software version's data is read correctly. */
        const val VOUCH_SAMPLES = 30

        /**
         * The same, for software the per-minute log was not compared on. With every known signal
         * readable, a record this long that was decoded with a wrong modulus practically always
         * shows it, mostly because its signals then disagree about its length (and for some
         * signals because a value leaves the range the machine can report).
         */
        const val STRICT_VOUCH_SAMPLES = 60

        /** The signals whose place and modulus are known. A record that vouches for uncompared software has them all. */
        private val KNOWN_SIGNALS = MinuteSignal.entries.filter { it.layoutConfirmed }.toSet()

        /** A session whose last minute is more recent than this may still be in progress, and waits. */
        const val SETTLE_MS = 10L * 60 * 1000

        /** How many rounds in a row may bring nothing while the machine says there is more. */
        const val MAX_EMPTY_ROUNDS = 20

        /** Under the ten minutes the system allows a background run. */
        const val RUN_LIMIT_MS = 8L * 60 * 1000

        /** A corrected time may be this far ahead of the phone's clock before it is refused. */
        const val FUTURE_ALLOWANCE_MS = 60_000L
        const val EARLIEST_START_MS = 946_684_800_000L
        const val CLOCK_READINGS = 3
        const val CLOCK_AGREEMENT_MS = 2_000L
        const val MAX_ROUND_TRIP_MS = 2_000L

        /** Ten years: beyond this the machine's clock was never set, and old data cannot be placed. */
        const val MAX_OFFSET_MS = 3_650L * 24 * 60 * 60 * 1000

        /**
         * The name a machine's records are filed under: derived from its serial number, so that
         * it is the same after a reinstall and on another phone (and the same records are then
         * written over, not beside), but slow to reverse and not the serial itself.
         */
        fun machineTag(serialNumber: String): String {
            val spec: KeySpec = PBEKeySpec(serialNumber.toCharArray(), "PAPRelay machine tag v1".toByteArray(Charsets.US_ASCII), 120_000, 64)
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return "m-" + bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
