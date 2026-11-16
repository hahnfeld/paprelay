// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.sync

import com.everysoft.paprelay.as11.As11AuthException
import com.everysoft.paprelay.as11.As11Exception
import com.everysoft.paprelay.as11.MachineClockReading
import com.everysoft.paprelay.as11.SpoolAddress
import com.everysoft.paprelay.as11.SpoolName
import com.everysoft.paprelay.as11.SpoolRound
import com.everysoft.paprelay.as11.decode.Firmware
import com.everysoft.paprelay.as11.decode.MinuteDecoder
import com.everysoft.paprelay.as11.decode.RecordResult
import com.everysoft.paprelay.as11.decode.SpoolDecodeException
import com.everysoft.paprelay.as11.decode.SummaryDecoder
import com.everysoft.paprelay.as11.plan.NightPlan
import com.everysoft.paprelay.as11.plan.RespiratoryRatePlan
import com.everysoft.paprelay.as11.plan.SessionPlan
import com.everysoft.paprelay.ble.BluetoothOffException
import com.everysoft.paprelay.ble.NoAnswerException
import com.everysoft.paprelay.core.ClockOffset
import com.everysoft.paprelay.core.MachineTime
import com.everysoft.paprelay.core.nights.FhirObservations
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
     * Returns only when Health Connect has confirmed both. (The ledger is the caller's affair.)
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
     *   made afresh, so whatever was written before is gone and must be written again.
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
 * says why, and the next one starts afresh.
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
        if (!firmware.layoutsConfirmed) throw Stop(SyncOutcome.SOFTWARE_NOT_CONFIRMED)

        val tag = knownTag?.takeIf { it.first == identity.serialNumber }?.second
            ?: machineTag(identity.serialNumber).also { knownTag = identity.serialNumber to it }
        if (progress.state.machineTag != tag) {
            // Nothing is remembered yet, or what is remembered is another machine's (its record
            // outlived the forgetting of it). That machine's nights stay in Health Connect, as
            // forgetting promises; the record of them is of no use here, and this one starts afresh.
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
        // Nothing is written until the user has said how to treat them.
        val clockChanged = progress.state.clock?.let { before ->
            val days = abs(machineNow.epochMs - before.machineMs) / DAY_MS
            abs(offset.millis - before.offsetMs) > CLOCK_STEP_MS + days * DRIFT_PER_DAY_MS
        } ?: false
        if (progress.state.softwareSeen != identity.applicationIdentifier) {
            // Other software than last time. Sessions already on the machine were recorded by the
            // old one; only what begins from now on can show how the new one writes.
            progress.state = progress.state.with(
                softwareSeen = identity.applicationIdentifier,
                softwareSinceMs = if (progress.state.softwareSeen == null) null else machineNow.epochMs,
                // Whatever was vouched for before is not what is running now, even if its name comes back.
                vouchedSoftware = null,
            )
        }
        if (progress.state.rewriteRequested) {
            // Everything is to be written again: what was passed on may have been cleared, so the
            // word that it is in step counts for nothing. It is taken back here, once, for both
            // kinds of record and whichever of them is switched on at the moment; from here on
            // this is an ordinary run. What it gets through is confirmed and stays so, and what
            // it does not is still marked next time.
            // The nights first, so that dying between the two only has this done again.
            nights?.let { nightStore ->
                val kept = readNights(nightStore)
                if (kept.byDate.values.any { it.recorded != null }) {
                    nightStore.write(Nights(kept.machineTag, kept.byDate.mapValues { (_, stored) -> StoredNight(stored.night, null) }))
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
            progress.state = progress.state.with(ledger = marked, rewriteRequested = false).fromTheStart()
        }
        store.write(progress.state)

        // The nights first. They are whole days by the machine's own calendar, so they need no
        // clock correction and nothing that follows can hold them up; and they are what matters most.
        val recordsFailure = nights(session, firmware, tag, machineNow, progress)

        if (!progress.state.respiratoryRate) {
            // Nothing is written minute by minute. The machine's clock as last noted while it was
            // is left as it is, so that a clock set in the meantime is still noticed if it is switched on.
            recordsFailure?.let { throw it }
            return
        }
        // What stands in the way of the respiratory rate (Health Connect missing, or not allowing it) is said
        // now that the nights are in: it is no reason to keep those from the phone.
        sink.notReady()?.let { throw Stop(it) }
        if (clockChanged) throw Stop(SyncOutcome.CLOCK_CHANGED)
        progress.state = progress.state.with(clock = ClockBaseline(offset.millis, machineNow.epochMs))
        store.write(progress.state)

        var vouched = progress.state.vouchedSoftware == identity.applicationIdentifier
        val vouchFrom = progress.state.softwareSinceMs
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
            // A machine that keeps promising more and sending nothing is not followed for ever.
            if (log.entries.isNotEmpty()) {
                emptyRounds = 0
            } else if (++emptyRounds > MAX_EMPTY_ROUNDS) {
                progress.partial = true
                break
            }
            // Something in this download failed the way a misread layout would. A long record that
            // decoded cleanly beside it answers the doubt: the layout is being read as it is meant,
            // and what failed was that one record. With none, the short records are not believed.
            val doubtful = log.doubted && log.decoded.none { it.sampleCount >= VOUCH_SAMPLES }
            for (entry in log.entries) {
                // A stop asked for from outside is heard between sessions, never inside one.
                currentCoroutineContext().ensureActive()
                val record = when (entry) {
                    is RecordResult.Decoded -> entry.value
                    // Not understood: its minutes are unknown. The sessions after it are still
                    // written (one unreadable night must not hold up every night that follows),
                    // but the place to resume from stays before it, so that it is read again.
                    is RecordResult.Failed -> {
                        trouble()
                        continue
                    }
                }
                newest = record.firstMinuteEnd.epochMs
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
                    // A record no rule was written for (far too long, say) costs itself, like one that cannot be read.
                    trouble()
                    continue
                } catch (e: IllegalStateException) {
                    trouble()
                    continue
                }
                when (plan) {
                    is SessionPlan.Unplanned -> if (plan.minutesUnknown) trouble()
                    is SessionPlan.Planned -> {
                        val canVouch = record.sampleCount >= VOUCH_SAMPLES && (vouchFrom == null || record.firstMinuteEnd.epochMs >= vouchFrom)
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
            address = SpoolAddress.Continuation(round.next ?: break)
        }
        // Nothing long enough came to vouch for the software: the short sessions wait for the next run.
        val unvouched = waiting.isNotEmpty()
        if (unvouched) trouble()
        // Where the next run begins: at the newest session (it may still be in progress and grow),
        // or, if something could not be dealt with, before it, so that it is read again. Where the
        // trouble came before any session that could be dealt with, the place stays where it was.
        // How far back it is kept is bounded: never more than the hold limit before now. A night
        // that still cannot be read when it is that old stops being asked for, or every run would
        // read months of history again for its sake.
        val resume = when {
            // Sessions that are only waiting for the software to be vouched for can be read; they are
            // never given up for being old.
            unvouched -> lastBeforeTrouble ?: progress.state.resumeFromMs
            troubled -> minOf(
                maxOf(lastBeforeTrouble ?: progress.state.resumeFromMs ?: Long.MIN_VALUE, machineNow.epochMs - HOLD_LIMIT_MS),
                newest ?: Long.MAX_VALUE,
            )
            else -> newest ?: progress.state.resumeFromMs
        }
        progress.state = progress.state.with(resumeFromMs = resume)
        store.write(progress.state)
        progress.sessionInProgress = skippedInProgress
        recordsFailure?.let { throw it }
    }

    /**
     * Brings the stored nights up to date from the machine's daily summaries, and passes on to
     * the phone's health records every finished night that is new or has changed.
     *
     * The nights are kept before anything is passed on, so that the app can show them whatever
     * becomes of the rest. A failure to pass them on is returned, not thrown, so that the caller
     * can finish its other work first.
     */
    private suspend fun nights(session: MachineSession, firmware: Firmware, tag: String, machineNow: MachineTime, progress: Progress): HealthSinkException? {
        val nightStore = nights ?: return null
        val onDisk = readNights(nightStore)
        if (onDisk.machineTag != null && onDisk.machineTag != tag) {
            // Another machine's nights are not this one's. They are put aside, not thrown away, and the list starts afresh.
            nightStore.setAside(PREVIOUS)
        }
        val before = if (onDisk.machineTag == tag) onDisk.byDate else emptyMap()
        val byDate = LinkedHashMap(before)
        var changed = onDisk.machineTag != tag
        // What this download has already given for a date: the start of its day, and when the machine wrote it.
        val given = HashMap<LocalDate, Pair<Long, Long?>>()
        val disputed = HashSet<LocalDate>()
        // From a little before the end of the newest finished night: the machine may still amend a day it has closed.
        val newestEnd = byDate.values.filter { it.night.complete }.maxOfOrNull { it.night.end.toInstant().toEpochMilli() }
        val from = maxOf(EARLIEST_START_MS, newestEnd?.let { it - NIGHT_OVERLAP_MS } ?: (machineNow.epochMs - FIRST_WINDOW_MS))
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
                val night = NightPlan.night(record, machineNow)
                if (night == null) {
                    // A record that cannot stand as a night. For a day still being written that is to
                    // be expected; for one that has ended, a night is missing and the run says so.
                    if (record.periodEnd <= machineNow) progress.partial = true
                    continue
                }
                if (night.date in disputed) continue
                val earlier = given[night.date]
                if (earlier != null && earlier.first != record.periodStart.epochMs) {
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
            address = SpoolAddress.Continuation(round.next ?: break)
        }
        // The oldest go when there are more than anyone will look at.
        while (byDate.size > MAX_NIGHTS) {
            byDate.remove(byDate.keys.min())
            changed = true
        }
        if (changed) nightStore.write(Nights(tag, byDate))

        val records = records ?: return null
        if (!progress.state.healthRecords) return null
        when (records.state()) {
            RecordsState.NOT_SUPPORTED -> return null
            // Switched on and not allowed: the nights are on the phone, and the run says what is missing.
            RecordsState.NOT_ALLOWED -> return HealthSinkException(notAllowed = true)
            RecordsState.READY -> Unit
        }
        var unsaved = 0
        return try {
            if (records.open() && byDate.values.any { it.recorded != null }) {
                // PAPRelay's place in the health records was not there and has been made afresh: the
                // user removed it, or Health Connect's data was cleared. Nothing recorded before is
                // there now, whatever is remembered here.
                for ((date, stored) in byDate.toMap()) byDate[date] = StoredNight(stored.night, null)
                unsaved = SAVE_EVERY_NIGHTS
            }
            for (date in byDate.keys.sorted()) {
                currentCoroutineContext().ensureActive()
                val stored = byDate.getValue(date)
                // What was written, and in which form: a change in either means writing it again.
                val mark = "${FhirObservations.FORMAT}:${stored.night.fingerprint()}"
                if (!stored.night.complete || stored.recorded == mark) continue
                val resources = FhirObservations.forNight(stored.night, tag)
                // Whatever the night could have had before and has no longer must not linger. Asked for
                // every time, known to be there or not: what was recorded before may be forgotten here
                // (a rewrite, a lost file) and still be there.
                val gone = FhirObservations.idsFor(stored.night, tag) - resources.map { it.id }.toSet()
                records.write(resources, gone)
                byDate[date] = StoredNight(stored.night, mark)
                progress.written += resources.size
                if (++unsaved >= SAVE_EVERY_NIGHTS) {
                    nightStore.write(Nights(tag, byDate))
                    unsaved = 0
                }
            }
            null
        } catch (e: HealthSinkException) {
            e
        } finally {
            if (unsaved > 0) runCatching { nightStore.write(Nights(tag, byDate)) }
        }
    }

    /** The nights as kept. A file that cannot be read is put aside under another name, and the list starts afresh. */
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

        /** How many nights are kept on the phone: more than three years. */
        const val MAX_NIGHTS = 1200

        /** How often the record of what has been passed on is saved while many nights are passed on at once. */
        const val SAVE_EVERY_NIGHTS = 20

        /** Sessions of at least this many minutes show that a software version's data is read correctly. */
        const val VOUCH_SAMPLES = 30

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
