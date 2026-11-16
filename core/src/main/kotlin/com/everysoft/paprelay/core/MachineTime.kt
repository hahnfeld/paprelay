// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.core

import java.time.Instant

/**
 * A moment as the machine's own clock recorded it, in milliseconds since the
 * Unix epoch.
 *
 * The machine's clock is set by hand or by its modem and drifts: on the first
 * machine examined it was six minutes fast. A time read from the machine is
 * therefore not a real-world time, and this type keeps the two apart: the only
 * way to an [Instant] is through a measured clock offset.
 */
/**
 * How far the machine's clock is from true time: what must be added to a
 * [MachineTime] to get the real moment. Negative when the machine's clock is fast.
 *
 * It can only be made from the two clocks read together, or read back from
 * where one made that way was stored, so that the sign cannot be got wrong by
 * hand: on the first machine examined a wrong sign would have put every
 * reading twelve minutes out.
 */
@JvmInline
public value class ClockOffset private constructor(public val millis: Long) {
    override fun toString(): String = "ClockOffset"

    public companion object {
        /**
         * @param machineNow what the machine's clock said.
         * @param trueNowMs the true time at that same moment, in milliseconds since the epoch.
         */
        public fun measured(machineNow: MachineTime, trueNowMs: Long): ClockOffset = ClockOffset(Math.subtractExact(trueNowMs, machineNow.epochMs))

        /** An offset read back from storage, where [millis] of one that was measured had been kept. */
        public fun restored(millis: Long): ClockOffset = ClockOffset(millis)
    }
}

@JvmInline
public value class MachineTime(public val epochMs: Long) : Comparable<MachineTime> {
    /** The real-world moment, given how far the machine's clock was from true time. */
    public fun toInstant(offset: ClockOffset): Instant = Instant.ofEpochMilli(Math.addExact(epochMs, offset.millis))

    // The arithmetic below throws ArithmeticException on overflow, which no real time can cause.

    public operator fun plus(milliseconds: Long): MachineTime = MachineTime(Math.addExact(epochMs, milliseconds))

    public operator fun minus(milliseconds: Long): MachineTime = MachineTime(Math.subtractExact(epochMs, milliseconds))

    /** Milliseconds from [other] to this moment, both by the machine's clock. */
    public fun since(other: MachineTime): Long = Math.subtractExact(epochMs, other.epochMs)

    override fun compareTo(other: MachineTime): Int = epochMs.compareTo(other.epochMs)

    /** Shows nothing: when someone went to bed is health data, and must not reach a log by way of a string template. */
    override fun toString(): String = "MachineTime"
}
