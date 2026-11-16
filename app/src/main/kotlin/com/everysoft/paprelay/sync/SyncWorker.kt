// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.everysoft.paprelay.PapRelayApp

/**
 * The hourly background run. It always reports success to the system: a machine that is
 * out of range is the ordinary state of affairs while the phone is away from home, and
 * the next hour's run is the retry. What happened is in the app's own record.
 */
class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as PapRelayApp
        app.pairingStore.current()
        app.sync.runOnce()
        return Result.success()
    }
}
