// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.sync

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.everysoft.paprelay.BuildConfig
import com.everysoft.paprelay.as11.SpoolName
import com.everysoft.paprelay.as11.SpoolRound
import com.everysoft.paprelay.ble.MachineClaim
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** What the screen needs to know about synchronising. */
class SyncStatus(
    val running: Boolean,
    val lastReport: SyncReport?,
    val lastCompleteAtMs: Long?,
    /** True when the stored record of synchronising cannot be read. */
    val storeUnreadable: Boolean,
)

/**
 * Runs synchronisations for the app: on request, and on a schedule in the background.
 * Both go through the same engine, and both need the [claim] on the machine, so only one
 * thing talks to the machine at a time.
 *
 * Synchronising is switched off in the debug build. Health Connect files records under the
 * app that wrote them, and the debug build is a different app to it than the released one:
 * the same nights written by both would be there twice.
 */
class SyncRunner(
    private val context: Context,
    private val scope: CoroutineScope,
    connector: MachineConnector,
    sink: HealthSink,
    private val claim: MachineClaim,
) {
    private val store = SyncStore(File(context.noBackupFilesDir, "sync.json"))
    private val engine = SyncEngine(connector, sink, store, System::currentTimeMillis, ::keepRaw)

    private val _status = MutableStateFlow(SyncStatus(running = false, lastReport = null, lastCompleteAtMs = null, storeUnreadable = false))
    val status: StateFlow<SyncStatus> = _status

    val enabled: Boolean get() = BuildConfig.SYNC_ENABLED

    /** Reads what the last run left, for the screen. */
    suspend fun load() = refresh()

    /**
     * Starts a run and returns at once. Once a run has been asked for by hand, the hourly
     * background run is asked for too: the first one is always made with the user watching.
     */
    fun syncNow() {
        scope.launch { if (runOnce() != null) schedule() }
    }

    /**
     * Runs one synchronisation to its end. Returns its report, or null if it did not run:
     * something else has the machine (a device check, another run), or this build does not
     * synchronise.
     */
    suspend fun runOnce(): SyncReport? {
        if (!enabled || !claim.tryTake()) return null
        setRunning(true)
        try {
            return engine.sync()
        } finally {
            // In this order, and at once: the screen must never show a run that holds nothing,
            // nor offer a button while the machine is still taken.
            claim.release()
            setRunning(false)
            withContext(NonCancellable) { refresh() }
        }
    }

    /** The user accepts the machine's clock as it now is, after it was found to have been changed. */
    fun acceptMachineClock() {
        scope.launch {
            change { it.with(clock = null) }
            runOnce()
        }
    }

    /** Writes everything again, for when Health Connect's data was cleared. */
    fun rewriteEverything() {
        scope.launch {
            change { it.with(rewriteRequested = true).fromTheStart() }
            runOnce()
        }
    }

    /**
     * Throws away a record of synchronising that can no longer be read, and starts again.
     * Every night the machine still holds is then cleared in Health Connect and written afresh.
     */
    fun startAgain() {
        scope.launch {
            engine.whileIdle { withContext(Dispatchers.IO) { store.delete() } }
            refresh()
            runOnce()
        }
    }

    /** Sets or removes the leak rule. Every night is then looked at again. */
    suspend fun setLeakLimit(litresPerMinute: Int?) = change { it.withLeakLimit(litresPerMinute) }

    /**
     * Forgets everything about synchronising, when the machine is forgotten, and stops the
     * hourly run. Health Connect is not touched.
     */
    suspend fun forget() {
        unschedule()
        engine.whileIdle {
            withContext(Dispatchers.IO) {
                store.delete()
                File(context.filesDir, RAW_DIRECTORY).deleteRecursively()
            }
        }
        refresh()
    }

    /** Has the system run a synchronisation about once an hour from now on. Safe to call repeatedly. */
    fun schedule() {
        if (!enabled) return
        val hourly = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, hourly)
    }

    fun unschedule() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /** Waits for a run in progress to finish: it holds the state in hand, and would overwrite the change. */
    private suspend fun change(transform: (SyncState) -> SyncState) {
        engine.whileIdle { withContext(Dispatchers.IO) { runCatching { store.write(transform(store.read())) } } }
        refresh()
    }

    private fun setRunning(running: Boolean) {
        _status.update { SyncStatus(running, it.lastReport, it.lastCompleteAtMs, it.storeUnreadable) }
    }

    /** Reads the stored report again. Whether a run is in progress is not its affair. */
    private suspend fun refresh() {
        val state = withContext(Dispatchers.IO) { runCatching { store.read() } }
        _status.update { SyncStatus(it.running, state.getOrNull()?.lastReport, state.getOrNull()?.lastCompleteAtMs, storeUnreadable = state.isFailure) }
    }

    /**
     * Keeps each download as it arrived, so that a later version can decode what this one
     * could not. Named by its content, so the same bytes are kept once; the oldest go when
     * there are too many.
     */
    private suspend fun keepRaw(log: SpoolName, round: SpoolRound) {
        val hash = round.sha256 ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                val directory = File(File(context.filesDir, RAW_DIRECTORY), log.name.lowercase()).apply { mkdirs() }
                val file = File(directory, hash.joinToString("") { "%02x".format(it) } + ".bin")
                if (!file.exists()) file.writeBytes(round.data)
                directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_RAW_FILES)?.forEach { it.delete() }
            }
        }
    }

    private companion object {
        const val WORK_NAME = "sync"
        const val RAW_DIRECTORY = "raw"
        const val MAX_RAW_FILES = 400
    }
}
