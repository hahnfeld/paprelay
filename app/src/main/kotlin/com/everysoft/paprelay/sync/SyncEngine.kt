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
import com.everysoft.paprelay.as11.plan.RespiratoryRatePlan
import com.everysoft.paprelay.as11.plan.SessionPlan
import com.everysoft.paprelay.ble.BluetoothOffException
import com.everysoft.paprelay.ble.NoAnswerException
import com.everysoft.paprelay.core.ClockOffset
import com.everysoft.paprelay.core.MachineTime
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

/** Health Connect refused or failed. [notAllowed] when the cause is a missing permission. */
class HealthSinkException(val notAllowed: Boolean, cause: Throwable? = null) : Exception("Health Connect did not accept the change", cause)

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

    private class Stop(val outcome: SyncOutcome) : Exception()

    private class Progress(var state: SyncState) {
        var sessions = 0
        var written = 0
        var partial = false
    }

    private suspend fun attempt(): SyncReport {
        val startedState = try {
            store.read()
        } catch (e: Exception) {
            // Nothing can be recorded about this attempt either; the screen is told directly.
            return SyncReport(nowMs(), SyncOutcome.INTERNAL, 0, 0)
        }
        val progress = Progress(startedState)
        val outcome = try {
            connector.notReady()?.let { throw Stop(it) }
            sink.notReady()?.let { throw Stop(it) }
            if (phoneClockWrong()) throw Stop(SyncOutcome.PHONE_CLOCK_WRONG)
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
            SyncOutcome.CONNECTION_LOST
        } catch (e: HealthSinkException) {
            if (e.notAllowed) SyncOutcome.HEALTH_CONNECT_NOT_ALLOWED else SyncOutcome.HEALTH_CONNECT_FAILED
        } catch (e: SpoolDecodeException) {
            SyncOutcome.DATA_NOT_UNDERSTOOD
        } catch (e: Exception) {
            SyncOutcome.INTERNAL
        }
        val now = nowMs()
        val report = SyncReport(now, outcome, progress.sessions, progress.written)
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
            progress.state = SyncState.EMPTY.with(machineTag = tag, leakLimitLitresPerMinute = progress.state.leakLimitLitresPerMinute)
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
        progress.state.clock?.let { before ->
            val days = abs(machineNow.epochMs - before.machineMs) / DAY_MS
            if (abs(offset.millis - before.offsetMs) > CLOCK_STEP_MS + days * DRIFT_PER_DAY_MS) throw Stop(SyncOutcome.CLOCK_CHANGED)
        }
        progress.state = progress.state.with(clock = ClockBaseline(offset.millis, machineNow.epochMs))
        store.write(progress.state)

        val force = progress.state.rewriteRequested
        var vouched = progress.state.vouchedSoftware == identity.applicationIdentifier
        val from = maxOf(EARLIEST_START_MS, progress.state.resumeFromMs?.let { it - OVERLAP_MS } ?: (machineNow.epochMs - FIRST_WINDOW_MS))
        var address: SpoolAddress = SpoolAddress.FromMachineTime(from)
        var rounds = 0
        var emptyRounds = 0
        // The start of the newest session read, and of the last one read before anything went wrong.
        var newest: Long? = null
        var lastBeforeTrouble: Long? = null
        var troubled = false
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
            for (entry in log.entries) {
                // A stop asked for from outside is heard between sessions, never inside one.
                currentCoroutineContext().ensureActive()
                val record = when (entry) {
                    is RecordResult.Decoded -> entry.value
                    // Not understood: its minutes are unknown. The sessions after it are still
                    // written (one unreadable night must not hold up every night that follows),
                    // but the place to resume from stays before it, so that it is read again.
                    is RecordResult.Failed -> {
                        progress.partial = true
                        troubled = true
                        continue
                    }
                }
                if (machineNow.epochMs - record.lastMinuteEnd.epochMs in -FUTURE_ALLOWANCE_MS until SETTLE_MS) {
                    // Its last minute has only just been recorded: the session may still be going on.
                    // It is left until it has ended, so that a night is written once, whole, and
                    // nothing depends on how the machine presents a record it is still adding to.
                    // The next run starts at it again.
                    newest = record.firstMinuteEnd.epochMs
                    if (!troubled) lastBeforeTrouble = newest
                    continue
                }
                val plan = RespiratoryRatePlan.session(record, tag, progress.state.leakLimitLitresPerMinute)
                if (!vouched) {
                    if (plan is SessionPlan.Planned && record.sampleCount >= VOUCH_SAMPLES) {
                        vouched = true
                        progress.state = progress.state.with(vouchedSoftware = identity.applicationIdentifier)
                        store.write(progress.state)
                    } else {
                        // Too short to prove that this software's data is being read as it is meant. It
                        // waits, like a night that cannot be read, until a long session has been.
                        progress.partial = true
                        troubled = true
                        continue
                    }
                }
                when (plan) {
                    is SessionPlan.Unplanned -> if (plan.minutesUnknown) {
                        progress.partial = true
                        troubled = true
                    }
                    is SessionPlan.Planned -> {
                        val key = plan.readings.key
                        val now = nowMs()
                        val change = Reconcile.session(plan.readings, progress.state.ledger[key], offset, zone(), now, force)
                        if (change != null) {
                            // A reading that has not happened yet means the clock correction is wrong.
                            if (change.writes.any { it.time.toEpochMilli() > now + FUTURE_ALLOWANCE_MS }) throw Stop(SyncOutcome.CLOCK_UNRELIABLE)
                            // The intent first: from here on the ledger covers whatever is about to exist.
                            storeEntry(progress, key, change.intent)
                            sink.apply(change)
                            // Confirmed, and stored before the next session is begun: this one is finished for good.
                            storeEntry(progress, key, change.confirmed)
                            progress.written += change.writes.size
                        }
                    }
                }
                progress.sessions++
                newest = record.firstMinuteEnd.epochMs
                if (!troubled) lastBeforeTrouble = newest
            }
            address = SpoolAddress.Continuation(round.next ?: break)
        }
        // Where the next run begins: at the newest session (it may still be in progress and grow),
        // or, if something could not be dealt with, before it, so that it is read again. How far
        // before is bounded: never more than the hold limit back from now. A night that still
        // cannot be read when it is that old stops being asked for, or every run would read
        // months of history again for its sake. (Where the trouble came before any session that
        // could be read, there is no telling how old it is, and the bound alone decides.)
        val resume = when {
            troubled -> minOf(maxOf(lastBeforeTrouble ?: Long.MIN_VALUE, machineNow.epochMs - HOLD_LIMIT_MS), newest ?: Long.MAX_VALUE)
            else -> newest
        }
        progress.state = progress.state.with(
            resumeFromMs = resume ?: progress.state.resumeFromMs,
            rewriteRequested = force && progress.partial,
        )
        store.write(progress.state)
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
