// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.everysoft.paprelay.PapRelayApp
import com.everysoft.paprelay.pairing.PairingState

/**
 * A run handed to the system: the hourly one, one asked for by hand, one begun because the
 * machine was heard, or the one further attempt that may follow that. It always reports
 * success to the system: a machine that is out of range is the ordinary state of affairs
 * while the phone is away from home, and the next run is the retry. What happened is in the
 * app's own record.
 */
class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as PapRelayApp
        val sync = app.sync
        if (TAG_NOW in tags) {
            sync.beginRequested()
            try {
                // A run by hand begins the background runs.
                if (app.pairingStore.current() != PairingState.Absent && sync.runOnce() != null) sync.keepWatch()
            } finally {
                sync.doneStarting()
            }
            return Result.success()
        }
        // With no machine there is nothing to do, now or in an hour.
        if (app.pairingStore.current() == PairingState.Absent) {
            sync.stopWatch()
            return Result.success()
        }
        sync.load()
        when {
            TAG_HEARD in tags -> when (val wait = sync.heardWaitMs()) {
                null -> Unit
                0L -> if (sync.runOnce()?.outcome in WORTH_ANOTHER_TRY) sync.retryLater()
                else -> sync.retryLater(wait)
            }
            // The one further attempt: made unless there is no longer any point, and not followed by another.
            TAG_RETRY in tags -> if (sync.heardWaitMs() != null) sync.runOnce()
            else -> sync.runOnce()
        }
        sync.listenForMachine()
        return Result.success()
    }

    companion object {
        const val TAG_NOW = "now"
        const val TAG_HEARD = "heard"
        const val TAG_RETRY = "retry"

        /** Heard, but not reached, or lost on the way: the machine may have been at the edge of range. */
        private val WORTH_ANOTHER_TRY = setOf(SyncOutcome.MACHINE_AWAY, SyncOutcome.CONNECTION_LOST)
    }
}
