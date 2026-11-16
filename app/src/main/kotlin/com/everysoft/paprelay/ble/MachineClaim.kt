// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.ble

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The machine accepts one connection at a time, and the stored pairing must not change
 * under a connection that is using it. Whatever talks to the machine or changes the pairing
 * (a device check, a synchronisation, forgetting the machine) takes this first, and does
 * not start if it is taken.
 */
class MachineClaim {
    private val held = AtomicBoolean(false)

    @Volatile
    private var releasedAt = Long.MIN_VALUE

    /** Takes the claim, or returns false at once if something else holds it. */
    fun tryTake(): Boolean = held.compareAndSet(false, true)

    /** Gives it up. Only whoever took it may call this. */
    fun release() {
        releasedAt = SystemClock.elapsedRealtime()
        held.set(false)
    }

    /**
     * How long the holder should still wait before calling the machine: after a connection
     * ends, the machine needs a few seconds before it answers a new one.
     */
    fun restMs(): Long {
        val since = releasedAt
        if (since == Long.MIN_VALUE) return 0
        return (REST_MS - (SystemClock.elapsedRealtime() - since)).coerceIn(0, REST_MS)
    }

    private companion object {
        const val REST_MS = 6_000L
    }
}
