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
import com.everysoft.paprelay.core.sync.Reconcile
import com.everysoft.paprelay.core.sync.SessionChange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.spec.KeySpec
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
     * Carries out one session's change: its deletions, then its writes. Returns only when
     * Health Connect has confirmed both.
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
 * Every session is its own small transaction: its change is applied, and only then is the
 * ledger updated and stored. Wherever a run is cut off (the phone leaves the room, the app
 * is killed), what was finished stays finished and the rest is taken up next time. Nothing
 * here retries: a failed run ends, says why, and the next one starts afresh.
 */
class SyncEngine(
    private val connector: MachineConnector,
    private val sink: HealthSink,
    private val store: SyncStore,
    private val nowMs: () -> Long,
    /** Called with each downloaded round, so that the bytes can be kept for a later version to decode. */
    private val keepRaw: suspend (SpoolName, SpoolRound) -> Unit = { _, _ -> },
) {
    private val running = Mutex()

    /** Runs one synchronisation, or returns null at once if one is already running. */
    suspend fun sync(): SyncReport? {
        if (!running.tryLock()) return null
        try {
            return attempt()
        } finally {
            running.unlock()
        }
    }

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
            connector.withSession { session -> run(session, progress) }
            if (progress.partial) SyncOutcome.PARTIAL else SyncOutcome.COMPLETE
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

        val tag = progress.state.machineTag ?: machineTag(identity.serialNumber).also {
            progress.state = progress.state.with(machineTag = it)
            store.write(progress.state)
        }

        val (machineNow, offset) = measureClock(session)
        val from = progress.state.resumeFromMs?.let { it - OVERLAP_MS } ?: (machineNow.epochMs - FIRST_WINDOW_MS)
        // Never before the year 2000: the client refuses an earlier start, and no machine holds one.
        var address: SpoolAddress = SpoolAddress.FromMachineTime(maxOf(EARLIEST_START_MS, from))
        var rounds = 0
        // The start of the newest session dealt with: where the next run begins, so that a
        // session still in progress now is read again once it has grown.
        var reached: Long? = null
        download@ while (true) {
            if (++rounds > MAX_ROUNDS) {
                progress.partial = true
                break
            }
            val round = session.pull(SpoolName.ONE_MINUTE, address)
            keepRaw(SpoolName.ONE_MINUTE, round)
            val log = MinuteDecoder.decode(round.data, firmware)
            for (entry in log.entries) {
                val record = when (entry) {
                    is RecordResult.Decoded -> entry.value
                    // Not understood: its minutes are unknown, and nothing after it is touched
                    // until it can be read, so that the place reached never passes a gap.
                    is RecordResult.Failed -> {
                        progress.partial = true
                        break@download
                    }
                }
                when (val plan = RespiratoryRatePlan.session(record, tag, progress.state.leakLimitLitresPerMinute)) {
                    is SessionPlan.Unplanned -> if (plan.minutesUnknown) {
                        progress.partial = true
                        break@download
                    }
                    is SessionPlan.Planned -> {
                        val key = plan.readings.key
                        val change = Reconcile.session(plan.readings, progress.state.ledger[key], offset, nowMs())
                        if (!change.nothingToDo) {
                            sink.apply(change)
                            val ledger = LinkedHashMap(progress.state.ledger)
                            val after = change.ledgerAfter
                            if (after == null) ledger.remove(key) else ledger[key] = after
                            progress.state = progress.state.with(ledger = ledger)
                            // Stored before the next session is begun: this one is now finished for good.
                            store.write(progress.state)
                            progress.written += change.writes.size
                        }
                    }
                }
                progress.sessions++
                reached = record.firstMinuteEnd.epochMs
            }
            address = SpoolAddress.Continuation(round.next ?: break)
        }
        reached?.let {
            progress.state = progress.state.with(resumeFromMs = maxOf(it, progress.state.resumeFromMs ?: Long.MIN_VALUE))
            store.write(progress.state)
        }
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
