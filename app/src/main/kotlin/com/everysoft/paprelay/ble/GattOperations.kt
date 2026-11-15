// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.ble

import com.everysoft.paprelay.as11.As11TransportException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/** The kinds of GATT request that complete with a callback. */
enum class GattOperation { MTU, DISCOVERY, SUBSCRIBE, WRITE }

/**
 * Runs GATT requests one at a time and pairs each with its own completion.
 *
 * Android delivers every completion through the same callback object, and some
 * arrive unasked: the machine starts its own MTU exchange, and a request that
 * timed out can still complete later. A completion is therefore accepted only
 * if it is of the kind that is currently waiting; anything else is ignored.
 * Without that, a late MTU callback would be taken for the end of service
 * discovery, and a real machine would be reported as "not an AirSense 11".
 *
 * Has no Android dependencies, so it is tested on the JVM.
 */
class GattOperations {
    private class Pending(val kind: GattOperation, val done: CompletableDeferred<Int>)

    private val lock = Mutex()
    private val pending = AtomicReference<Pending?>(null)
    private val failure = AtomicReference<As11TransportException?>(null)

    /**
     * Starts one request and waits for its completion status.
     *
     * @param start issues the request; returns false if the stack refused it.
     * @throws As11TransportException if it cannot be started, times out, or the link fails meanwhile.
     */
    suspend fun run(kind: GattOperation, timeoutMs: Long, start: () -> Boolean): Int = lock.withLock {
        failure.get()?.let { throw it }
        val done = CompletableDeferred<Int>()
        pending.set(Pending(kind, done))
        try {
            // A failure recorded between the first check and registration would be missed.
            failure.get()?.let { throw it }
            if (!start()) throw As11TransportException("${kind.name.lowercase()} could not be started")
            withTimeoutOrNull(timeoutMs) { done.await() }
                // The completion may have landed in the same instant the timer fired.
                ?: if (done.isCompleted) done.await() else throw As11TransportException("${kind.name.lowercase()} timed out")
        } finally {
            pending.set(null)
        }
    }

    /** Reports a completion callback. Ignored unless a request of that [kind] is waiting. */
    fun complete(kind: GattOperation, status: Int) {
        pending.get()?.takeIf { it.kind == kind }?.done?.complete(status)
    }

    /** Fails the waiting request, if any, and every later one. */
    fun fail(cause: As11TransportException) {
        if (failure.compareAndSet(null, cause)) pending.get()?.done?.completeExceptionally(cause)
    }
}
