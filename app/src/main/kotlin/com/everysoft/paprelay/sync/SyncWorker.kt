// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.everysoft.paprelay.PapRelayApp
import com.everysoft.paprelay.pairing.PairingState

/**
 * The hourly background run. It always reports success to the system: a machine that is
 * out of range is the ordinary state of affairs while the phone is away from home, and
 * the next hour's run is the retry. What happened is in the app's own record.
 */
class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as PapRelayApp
        // With no machine there is nothing to do, now or in an hour.
        if (app.pairingStore.current() == PairingState.Absent) {
            app.sync.unschedule()
            return Result.success()
        }
        app.sync.runOnce()
        return Result.success()
    }
}
