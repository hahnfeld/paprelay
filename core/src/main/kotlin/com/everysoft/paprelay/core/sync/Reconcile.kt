// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.core.sync

import com.everysoft.paprelay.core.ClockOffset
import com.everysoft.paprelay.core.MachineTime
import java.time.Instant
import java.time.ZoneId

/**
 * What PAPRelay knows about one session's records in Health Connect.
 *
 * It is written **before** Health Connect is touched as well as after, so that it never
 * claims less than may be there: at any moment Health Connect holds records for no minute
 * outside [minutes] and [pendingMinutes], and, once the entry has been confirmed for the
 * first time, none at a version above [version].
 *
 * @property minutes the minute numbers confirmed to have a record, in order. It can be empty
 *   in a confirmed entry: a session none of whose minutes are wanted is remembered too, so
 *   that it is cleared once and not at every synchronisation.
 * @property fingerprint of the confirmed readings; see [SessionReadings.fingerprint]. Empty
 *   when nothing has been confirmed yet.
 * @property clockOffset the clock correction the records are written with. Fixed when the
 *   session is first written and reused ever after, so that a later, slightly different
 *   measurement does not shift a night already recorded.
 * @property zone the time zone the records are labelled with, fixed in the same way: a
 *   night recorded at home stays labelled with home time if it is rewritten while travelling.
 * @property version the highest Health Connect record version ever sent for this session,
 *   confirmed or not. Every later write goes out above it.
 * @property pendingMinutes null when the last change was confirmed. Otherwise a change was
 *   begun and not confirmed, and these are all the minutes it may have written or left in
 *   place. The session must then be reconciled again whatever else is true.
 */
public class LedgerEntry(
    public val machineTag: String,
    public val firstMinuteEnd: MachineTime,
    minutes: List<Int>,
    public val fingerprint: String,
    public val clockOffset: ClockOffset,
    public val zone: ZoneId,
    public val version: Long,
    pendingMinutes: List<Int>?,
) {
    public val minutes: List<Int> = minutes.toList()
    public val pendingMinutes: List<Int>? = pendingMinutes?.toList()

    init {
        require(SessionReadings.isValidTag(machineTag)) { "not a machine tag" }
        require(version in 1..MAX_VERSION) { "not a record version" }
        require(fingerprint.isEmpty() || FINGERPRINT.matches(fingerprint)) { "not a fingerprint" }
        require(rising(this.minutes) && rising(this.pendingMinutes.orEmpty())) { "minutes must rise" }
        require(this.minutes.isEmpty() || fingerprint.isNotEmpty()) { "confirmed minutes without their fingerprint" }
        require(fingerprint.isNotEmpty() || this.pendingMinutes != null) { "an entry with nothing in it" }
    }

    public val key: String get() = "$machineTag/${firstMinuteEnd.epochMs}"

    /** Whether a change to this session has ever been carried through and confirmed. */
    public val everConfirmed: Boolean get() = fingerprint.isNotEmpty()

    /** Every minute Health Connect may hold a record for. */
    public val possiblyHeld: List<Int> get() = (minutes + pendingMinutes.orEmpty()).distinct().sorted()

    private companion object {
        val FINGERPRINT = Regex("[0-9a-f]{64}")

        /** Far beyond any clock, and far enough from the end of the numbers that adding one is always possible. */
        const val MAX_VERSION = Long.MAX_VALUE / 2

        fun rising(minutes: List<Int>) = minutes.all { it >= 0 } && minutes.zipWithNext().all { (a, b) -> b > a }
    }
}

/** One record to put into Health Connect, or to replace there. */
public class RecordWrite(
    public val recordId: String,
    public val version: Long,
    public val minuteEnd: MachineTime,
    public val clockOffset: ClockOffset,
    public val zone: ZoneId,
    public val breathsPerMinute: Double,
) {
    /** The real-world moment the record is to carry. */
    public val time: Instant get() = minuteEnd.toInstant(clockOffset)
}

/**
 * What has to happen for one session, in this order:
 *
 *  1. store [intent] in the ledger;
 *  2. delete [deleteRecordIds] from Health Connect;
 *  3. write [writes] to Health Connect;
 *  4. store [confirmed] in the ledger (or remove the entry, if it is null: only when the
 *     session's records were removed at the user's request).
 *
 * Step 1 comes first so that the ledger always covers whatever Health Connect may hold. If
 * the app dies anywhere after it, the entry is still marked as pending, and the next
 * reconciliation works the whole session out again from scratch: every step can be repeated
 * without harm, and nothing is lost or left behind whatever has changed in between.
 */
public class SessionChange(
    public val intent: LedgerEntry,
    public val deleteRecordIds: List<String>,
    public val writes: List<RecordWrite>,
    public val confirmed: LedgerEntry?,
)

/** Works out the difference between what should be in Health Connect and what may be. */
public object Reconcile {
    /**
     * @param desired the session as it should stand, from the latest download.
     * @param ledger the session's ledger entry, or null if it has none.
     * @param measuredOffset the clock correction measured in this sync; used only for a
     *   session with no entry.
     * @param zone the phone's time zone now; used only for a session with no entry.
     * @param nowMs the phone's clock, which record versions must never fall behind.
     * @param force true to write the session again even if the ledger says it is up to date:
     *   for when the ledger cannot be believed, such as after Health Connect's data was cleared.
     * @return the change to make, or null if the session is confirmed to be up to date already.
     * @throws IllegalArgumentException if [ledger] is for a different session.
     */
    public fun session(
        desired: SessionReadings,
        ledger: LedgerEntry?,
        measuredOffset: ClockOffset,
        zone: ZoneId,
        nowMs: Long,
        force: Boolean = false,
    ): SessionChange? {
        require(ledger == null || ledger.key == desired.key) { "the ledger entry is for another session" }
        val wanted = desired.minutes
        val fingerprint = desired.fingerprint()
        if (!force && ledger != null && ledger.pendingMinutes == null && ledger.minutes == wanted && ledger.fingerprint == fingerprint) {
            return null
        }

        // With no entry, nothing is known: the ledger may have been lost (a reinstall) while Health
        // Connect kept what an earlier installation wrote, under these same ids and at versions
        // nobody remembers. The same holds for an entry that has never been confirmed, since it
        // began from nothing. In both cases every minute the session has, and every minute the
        // entry names, is cleared first, the wanted ones too, so that what is written afterwards
        // cannot lose to an old record of a higher version. An entry that has been confirmed once
        // is trusted from then on to cover what is there and to outrank it: each confirmation
        // follows a complete clearing or a complete write above every earlier version.
        val trusted = ledger != null && ledger.everConfirmed
        val possiblyHeld = if (trusted) {
            ledger.possiblyHeld
        } else {
            (ledger?.possiblyHeld.orEmpty() + (0 until desired.minuteCount)).distinct().sorted()
        }
        val toDelete = if (trusted) (possiblyHeld.toSet() - wanted.toSet()).sorted() else possiblyHeld
        val deletions = toDelete.map { SessionReadings.recordId(desired.machineTag, desired.firstMinuteEnd, it) }

        // Higher than anything ever sent for this session, and never behind the clock, so that
        // a rewrite always wins over what Health Connect holds, including the remains of a
        // write that was never confirmed.
        val version = maxOf(nowMs, (ledger?.version ?: 0) + 1)
        val offset = ledger?.clockOffset ?: measuredOffset
        val heldZone = ledger?.zone ?: zone
        // Every reading is written again, not only the new ones: the version then describes
        // the whole session, and a half-finished earlier write is put right as a side effect.
        val writes = desired.readings.mapIndexed { index, reading ->
            RecordWrite(
                recordId = SessionReadings.recordId(desired.machineTag, desired.firstMinuteEnd, wanted[index]),
                version = version,
                minuteEnd = reading.minuteEnd,
                clockOffset = offset,
                zone = heldZone,
                breathsPerMinute = reading.breathsPerMinute,
            )
        }
        val intent = LedgerEntry(
            machineTag = desired.machineTag,
            firstMinuteEnd = desired.firstMinuteEnd,
            minutes = ledger?.minutes.orEmpty(),
            fingerprint = ledger?.fingerprint.orEmpty(),
            clockOffset = offset,
            zone = heldZone,
            version = version,
            pendingMinutes = (possiblyHeld + wanted).distinct().sorted(),
        )
        val confirmed = LedgerEntry(desired.machineTag, desired.firstMinuteEnd, wanted, fingerprint, offset, heldZone, version, pendingMinutes = null)
        return SessionChange(intent, deletions, writes, confirmed)
    }

    /**
     * Removes everything that may have been written for a session, for "delete my data".
     * A session the latest download no longer contains is otherwise left alone: whether the
     * machine has dropped old data or the session was never real is not something a
     * download can say, so records are deleted only for a session that is present and has
     * changed, or when the user asks.
     */
    public fun remove(ledger: LedgerEntry, nowMs: Long): SessionChange {
        val version = maxOf(nowMs, ledger.version + 1)
        val intent = LedgerEntry(
            ledger.machineTag, ledger.firstMinuteEnd, ledger.minutes, ledger.fingerprint, ledger.clockOffset, ledger.zone, version,
            pendingMinutes = ledger.possiblyHeld,
        )
        val ids = ledger.possiblyHeld.map { SessionReadings.recordId(ledger.machineTag, ledger.firstMinuteEnd, it) }
        return SessionChange(intent, ids, emptyList(), confirmed = null)
    }
}
