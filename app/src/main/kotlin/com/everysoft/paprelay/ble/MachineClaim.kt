// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.ble

import java.util.concurrent.atomic.AtomicBoolean

/**
 * The machine accepts one connection at a time, and the stored pairing must not change
 * under a connection that is using it. Whatever talks to the machine or changes the pairing
 * (a device check, a synchronisation, forgetting the machine) takes this first, and does
 * not start if it is taken.
 */
class MachineClaim {
    private val held = AtomicBoolean(false)

    /** Takes the claim, or returns false at once if something else holds it. */
    fun tryTake(): Boolean = held.compareAndSet(false, true)

    /** Gives it up. Only whoever took it may call this. */
    fun release() {
        held.set(false)
    }
}
