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
@JvmInline
public value class MachineTime(public val epochMs: Long) : Comparable<MachineTime> {
    /**
     * The real-world moment, given how far the machine's clock was from true time.
     *
     * @param clockOffsetMs true time minus machine time, in milliseconds: negative
     *   when the machine's clock is fast.
     */
    public fun toInstant(clockOffsetMs: Long): Instant = Instant.ofEpochMilli(Math.addExact(epochMs, clockOffsetMs))

    public operator fun plus(milliseconds: Long): MachineTime = MachineTime(Math.addExact(epochMs, milliseconds))

    public operator fun minus(milliseconds: Long): MachineTime = MachineTime(Math.subtractExact(epochMs, milliseconds))

    /** Milliseconds from [other] to this moment, both by the machine's clock. */
    public fun since(other: MachineTime): Long = Math.subtractExact(epochMs, other.epochMs)

    override fun compareTo(other: MachineTime): Int = epochMs.compareTo(other.epochMs)

    /** Deliberately not a calendar date: printing one would suggest it is a real-world time. */
    override fun toString(): String = "machine:$epochMs"
}
