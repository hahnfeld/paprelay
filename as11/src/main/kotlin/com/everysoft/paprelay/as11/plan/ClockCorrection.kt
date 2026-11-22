// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11.plan

import com.everysoft.paprelay.core.ClockOffset
import com.everysoft.paprelay.core.MachineTime
import kotlin.math.abs

/**
 * Turns a time stamp of the machine's into true time, where the clock's error is known well
 * enough: the one rule for a session's start and for a breathing event.
 *
 * @param offset how far the machine's clock is off, as measured in this connection, or null when it
 *   is not to be used (the clock was found to have been changed since the last measurement).
 * @param machineNow the machine's clock at that measurement.
 * @param trueNowMs the phone's clock at that measurement.
 */
public class ClockCorrection(private val offset: ClockOffset?, private val machineNow: MachineTime, private val trueNowMs: Long) {
    /**
     * [time] in true time, milliseconds since the epoch, or null: too far from the measurement for
     * the clock's drift since to be known, no measurement to go by, or a result in the future.
     */
    public fun of(time: MachineTime): Long? = offset
        ?.takeIf { abs(machineNow.since(time)) <= WINDOW_MS }
        ?.let { time.toInstant(it).toEpochMilli() }
        ?.takeIf { it <= trueNowMs + FUTURE_ALLOWANCE_MS }

    public companion object {
        /**
         * How far from the moment the clock was measured a time may be and still be corrected with
         * that measurement. The machine's clock drifts by seconds a day; within a week that stays
         * under half a minute (the synchronising engine allows three seconds a day). An older time,
         * as the first synchronisation reads back months of them, keeps its machine time alone: how
         * far the clock was off then is not known.
         */
        public const val WINDOW_MS: Long = 7L * 24 * 60 * 60 * 1000

        /** A corrected time may be this far ahead of the phone's clock; beyond it the correction is not believed. */
        public const val FUTURE_ALLOWANCE_MS: Long = 60_000L

        /** No correction at all. */
        public val NONE: ClockCorrection = ClockCorrection(null, MachineTime(0), 0)
    }
}
