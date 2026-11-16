// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.sync

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.everysoft.paprelay.as11.SpoolName
import com.everysoft.paprelay.as11.SpoolRound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * Both go through the same engine, which lets only one run at a time.
 */
class SyncRunner(
    private val context: Context,
    private val scope: CoroutineScope,
    connector: MachineConnector,
    sink: HealthSink,
) {
    private val store = SyncStore(File(context.noBackupFilesDir, "sync.json"))
    private val engine = SyncEngine(connector, sink, store, System::currentTimeMillis, ::keepRaw)
    private val changes = Mutex()

    private val _status = MutableStateFlow(SyncStatus(running = false, lastReport = null, lastCompleteAtMs = null, storeUnreadable = false))
    val status: StateFlow<SyncStatus> = _status

    /** Reads what the last run left, for the screen. */
    suspend fun load() = refresh(running = _status.value.running)

    /** Starts a run and returns at once. */
    fun syncNow() {
        scope.launch { runOnce() }
    }

    /** Runs one synchronisation to its end. Returns its report, or null if another was already running. */
    suspend fun runOnce(): SyncReport? {
        _status.value = SyncStatus(true, _status.value.lastReport, _status.value.lastCompleteAtMs, _status.value.storeUnreadable)
        return try {
            engine.sync()
        } finally {
            refresh(running = false)
        }
    }

    /** The user accepts the machine's clock as it now is, after it was found to have been changed. */
    suspend fun acceptMachineClock() = change { it.with(clock = null) }

    /** Sets or removes the leak rule. Every night is then looked at again. */
    suspend fun setLeakLimit(litresPerMinute: Int?) = change { it.withLeakLimit(litresPerMinute) }

    /** Asks for everything to be written again, for when Health Connect's data was cleared. */
    suspend fun requestRewrite() = change { it.with(rewriteRequested = true).fromTheStart() }

    /** Forgets everything about synchronising, when the machine is forgotten. Health Connect is not touched. */
    suspend fun forget() = changes.withLock {
        withContext(Dispatchers.IO) {
            store.delete()
            File(context.filesDir, RAW_DIRECTORY).deleteRecursively()
        }
        refresh(running = false)
    }

    /** Has the system run a synchronisation about once an hour from now on. Safe to call repeatedly. */
    fun schedule() {
        val hourly = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, hourly)
    }

    fun unschedule() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    private suspend fun change(transform: (SyncState) -> SyncState) = changes.withLock {
        withContext(Dispatchers.IO) { runCatching { store.write(transform(store.read())) } }
        refresh(running = _status.value.running)
    }

    private suspend fun refresh(running: Boolean) {
        val state = withContext(Dispatchers.IO) { runCatching { store.read() } }
        _status.value = SyncStatus(running, state.getOrNull()?.lastReport, state.getOrNull()?.lastCompleteAtMs, storeUnreadable = state.isFailure)
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
