// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.core.sync

import com.everysoft.paprelay.core.ClockOffset
import com.everysoft.paprelay.core.MachineTime

/**
 * What PAPRelay knows Health Connect to hold for one session: written down only
 * after Health Connect has confirmed the write.
 *
 * @property minutes the minute numbers that have a record, in order.
 * @property fingerprint of the readings as written; see [SessionReadings.fingerprint].
 * @property clockOffset the clock correction the records were written with. Fixed when the
 *   session is first written and reused ever after, so that a later, slightly different
 *   measurement does not shift a night already recorded.
 * @property version the Health Connect record version last written; rises with every rewrite.
 */
public class LedgerEntry(
    public val machineTag: String,
    public val firstMinuteEnd: MachineTime,
    minutes: List<Int>,
    public val fingerprint: String,
    public val clockOffset: ClockOffset,
    public val version: Long,
) {
    public val minutes: List<Int> = minutes.toList()

    init {
        require(version >= 1) { "versions start at one" }
        require(this.minutes.zipWithNext().all { (a, b) -> b > a } && this.minutes.all { it >= 0 }) { "minutes must rise" }
    }

    public val key: String get() = "$machineTag/${firstMinuteEnd.epochMs}"
}

/** One record to put into Health Connect, or to replace there. */
public class RecordWrite(
    public val recordId: String,
    public val version: Long,
    public val minuteEnd: MachineTime,
    public val clockOffset: ClockOffset,
    public val breathsPerMinute: Double,
) {
    /** The real-world moment the record is to carry. */
    public val time: java.time.Instant get() = minuteEnd.toInstant(clockOffset)
}

/**
 * What has to happen in Health Connect for one session, and what the ledger should say
 * once it has. The deletions are carried out first, then the writes, and only when both
 * have been confirmed is [ledgerAfter] stored. If the app dies in between, the ledger
 * still describes the old state and the same change is worked out again: every step can
 * be repeated without harm.
 *
 * @property ledgerAfter null when the session ends up with no records and no entry.
 */
public class SessionChange(
    public val writes: List<RecordWrite>,
    public val deleteRecordIds: List<String>,
    public val ledgerAfter: LedgerEntry?,
) {
    public val nothingToDo: Boolean get() = writes.isEmpty() && deleteRecordIds.isEmpty()
}

/** Works out the difference between what should be in Health Connect and what is. */
public object Reconcile {
    /**
     * @param desired the session as it should stand, from the latest download.
     * @param ledger what was last confirmed written for the same session, or null if nothing was.
     * @param measuredOffset the clock correction measured in this sync; used only for a
     *   session not written before.
     * @param nowMs the phone's clock, which record versions must never fall behind.
     * @throws IllegalArgumentException if [ledger] is for a different session.
     */
    public fun session(desired: SessionReadings, ledger: LedgerEntry?, measuredOffset: ClockOffset, nowMs: Long): SessionChange {
        require(ledger == null || ledger.key == desired.key) { "the ledger entry is for another session" }
        val wanted = desired.minutes
        val fingerprint = desired.fingerprint()
        if (ledger != null && ledger.minutes == wanted && ledger.fingerprint == fingerprint) {
            return SessionChange(emptyList(), emptyList(), ledger)
        }

        val held = ledger?.minutes.orEmpty()
        val deletions = (held.toSet() - wanted.toSet()).sorted()
            .map { SessionReadings.recordId(desired.machineTag, desired.firstMinuteEnd, it) }
        if (wanted.isEmpty()) return SessionChange(emptyList(), deletions, null)

        // Higher than anything written for this session before, and never behind the clock,
        // so that a rewrite always wins over what Health Connect already holds.
        val version = maxOf(nowMs, (ledger?.version ?: 0) + 1)
        val offset = ledger?.clockOffset ?: measuredOffset
        // Every reading is written again, not only the new ones: the version then describes
        // the whole session, and a half-finished earlier write is put right as a side effect.
        val writes = desired.readings.mapIndexed { index, reading ->
            RecordWrite(
                recordId = SessionReadings.recordId(desired.machineTag, desired.firstMinuteEnd, wanted[index]),
                version = version,
                minuteEnd = reading.minuteEnd,
                clockOffset = offset,
                breathsPerMinute = reading.breathsPerMinute,
            )
        }
        return SessionChange(
            writes = writes,
            deleteRecordIds = deletions,
            ledgerAfter = LedgerEntry(desired.machineTag, desired.firstMinuteEnd, wanted, fingerprint, offset, version),
        )
    }

    /**
     * For a session the ledger knows but the latest download no longer contains. Whether that
     * means the machine has dropped old data (keep the records) or the session was never real
     * is not something a download can say, so nothing is removed here: records are only ever
     * deleted for a session that is present and has changed, or when the user asks.
     */
    public fun absent(@Suppress("UNUSED_PARAMETER") ledger: LedgerEntry): SessionChange = SessionChange(emptyList(), emptyList(), ledger)

    /** Removes everything written for a session, for "delete my data" and for turning a setting off. */
    public fun remove(ledger: LedgerEntry): SessionChange =
        SessionChange(emptyList(), ledger.minutes.map { SessionReadings.recordId(ledger.machineTag, ledger.firstMinuteEnd, it) }, null)
}
