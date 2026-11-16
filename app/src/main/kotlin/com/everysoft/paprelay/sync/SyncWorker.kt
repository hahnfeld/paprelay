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
            // Already being run in the app itself, which had waited long enough for this.
            if (!sync.beginRequested()) return Result.success()
            val report = try {
                if (app.pairingStore.current() != PairingState.Absent) sync.runOnce() else null
            } finally {
                sync.doneRequested()
            }
            // A run by hand begins the background runs.
            if (report != null) sync.keepWatch()
            sync.followUp(report)
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
                0L -> again(sync, sync.runOnce(), heard = true)
                else -> sync.heardTooSoon(wait)
            }
            // The one further attempt: made unless there is no longer any point, and not followed by another.
            TAG_RETRY in tags -> if (sync.worthRetrying()) sync.runOnce()
            else -> again(sync, sync.runOnce(), heard = false)
        }
        sync.listenForMachine()
        return Result.success()
    }

    /** Asks for the one further attempt where the run that has just ended gives a reason for it. */
    private fun again(sync: SyncRunner, report: SyncReport?, heard: Boolean) {
        when {
            report == null -> Unit
            // Reached and then lost: the machine is there, and nothing else will say so again soon.
            report.outcome == SyncOutcome.CONNECTION_LOST -> sync.retryLater()
            // Heard, but no answer: it may have been at the edge of range. (An hourly run that gets
            // no answer is simply a phone away from home, and waits for the next.)
            heard && report.outcome == SyncOutcome.MACHINE_AWAY -> sync.retryLater()
            else -> sync.followUp(report)
        }
    }

    companion object {
        const val TAG_NOW = "now"
        const val TAG_HEARD = "heard"
        const val TAG_RETRY = "retry"
    }
}
