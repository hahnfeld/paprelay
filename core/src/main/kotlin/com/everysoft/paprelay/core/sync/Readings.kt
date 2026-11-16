// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.core.sync

import com.everysoft.paprelay.core.MachineTime
import java.security.MessageDigest

/**
 * One respiratory-rate reading PAPRelay wants Health Connect to hold.
 *
 * @property minuteEnd the end of the minute the reading describes, by the machine's clock.
 * @property hundredthsPerMinute the rate in hundredths of a breath per minute. Whole
 *   numbers, so that two runs over the same data compare equal with no rounding in play.
 */
public class RateReading(public val minuteEnd: MachineTime, public val hundredthsPerMinute: Int) {
    init {
        require(hundredthsPerMinute in 1..MAX_HUNDREDTHS) { "a rate must be positive and believable" }
    }

    public val breathsPerMinute: Double get() = hundredthsPerMinute / 100.0

    public companion object {
        /** 90 breaths a minute: the top of the range the machine itself declares. */
        public const val MAX_HUNDREDTHS: Int = 9_000
    }
}

/**
 * The readings of one mask-on period, as they should stand in Health Connect.
 *
 * A session is identified by the machine and by the time stamp of its first
 * minute, which the machine never changes: a session still in progress when it
 * is first downloaded comes back later under the same stamp, longer.
 *
 * @property machineTag a stable, non-identifying name for the machine; part of every record id.
 * @property firstMinuteEnd the time stamp of the session's first minute, whether or not
 *   that minute has a reading.
 * @property readings in time order, at most one per minute, all within the session.
 */
public class SessionReadings(
    public val machineTag: String,
    public val firstMinuteEnd: MachineTime,
    readings: List<RateReading>,
) {
    public val readings: List<RateReading> = readings.toList()

    init {
        require(TAG.matches(machineTag)) { "a machine tag is 4 to 32 letters, digits or dashes" }
        var previous: MachineTime? = null
        for (reading in this.readings) {
            val offset = reading.minuteEnd.since(firstMinuteEnd)
            require(offset >= 0 && offset % MINUTE_MS == 0L) { "a reading does not sit on the session's minutes" }
            require(previous == null || reading.minuteEnd > previous) { "readings out of order" }
            previous = reading.minuteEnd
        }
    }

    /** The key the ledger files this session under. */
    public val key: String get() = "$machineTag/${firstMinuteEnd.epochMs}"

    /** The minute number of each reading within the session, counting from zero. */
    public val minutes: List<Int> get() = readings.map { (it.minuteEnd.since(firstMinuteEnd) / MINUTE_MS).toInt() }

    /**
     * A fingerprint of exactly what is to be held: which minutes, and each value. Two runs
     * over the same download give the same fingerprint; any change to any reading changes it.
     */
    public fun fingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (reading in readings) {
            val minute = reading.minuteEnd.since(firstMinuteEnd) / MINUTE_MS
            digest.update("$minute=${reading.hundredthsPerMinute};".toByteArray(Charsets.US_ASCII))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    public companion object {
        public const val MINUTE_MS: Long = 60_000L
        private val TAG = Regex("[A-Za-z0-9-]{4,32}")

        /**
         * The Health Connect client record id of the reading for one minute of a session. Built
         * from the machine's own time stamp, so it is the same on every run and on every phone,
         * and does not move when the clock correction does.
         */
        public fun recordId(machineTag: String, firstMinuteEnd: MachineTime, minute: Int): String {
            require(minute >= 0) { "a minute number is not negative" }
            return "rr-$machineTag-${firstMinuteEnd.epochMs + minute * MINUTE_MS}"
        }
    }
}
