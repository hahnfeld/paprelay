// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.everysoft.paprelay.PapRelayApp
import com.everysoft.paprelay.pairing.PairingState

/**
 * A run handed to the system: the hourly one, one asked for by hand, or one begun because
 * the machine was heard. It always reports success to the system: a machine that is out of
 * range is the ordinary state of affairs while the phone is away from home, and the next
 * run is the retry. What happened is in the app's own record.
 */
class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as PapRelayApp
        // With no machine there is nothing to do, now or in an hour.
        if (app.pairingStore.current() == PairingState.Absent) {
            app.sync.stopWatch()
            return Result.success()
        }
        app.sync.load()
        if (TAG_NOW in tags) {
            // A run by hand begins the background runs.
            if (app.sync.runOnce() != null) app.sync.keepWatch()
            return Result.success()
        }
        // Hearing the machine again minutes after an attempt is not a reason to ask it again.
        if (TAG_HEARD !in tags || !app.sync.heardTooSoon()) app.sync.runOnce()
        app.sync.listenForMachine()
        return Result.success()
    }

    companion object {
        const val TAG_NOW = "now"
        const val TAG_HEARD = "heard"
    }
}
