// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.sync

import android.content.Context
import android.os.SystemClock
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.everysoft.paprelay.BuildConfig
import com.everysoft.paprelay.as11.SpoolName
import com.everysoft.paprelay.as11.SpoolRound
import com.everysoft.paprelay.ble.MachineClaim
import com.everysoft.paprelay.ble.PresenceWatch
import com.everysoft.paprelay.pairing.PairingState
import com.everysoft.paprelay.pairing.PairingStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.DateTimeException
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** What the screen needs to know about synchronising. */
class SyncStatus(
    val running: Boolean,
    val lastReport: SyncReport?,
    val lastCompleteAtMs: Long?,
    /** True when the stored record of synchronising is damaged and cannot be read. */
    val storeUnreadable: Boolean,
)

/**
 * Runs synchronisations for the app. There are three reasons for one: the user asks, the
 * phone hears the machine come into range, and an hour has passed. All three are handed to
 * the system as work to run, so that a run is not frozen or dropped when the screen goes
 * off or the app is left; all go through the same engine, and all need the [claim] on the
 * machine, so only one thing talks to the machine at a time.
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
    private val pairings: PairingStore,
    describe: (Throwable) -> String?,
) {
    private val store = SyncStore(File(context.noBackupFilesDir, "sync.json"))
    private val engine =
        SyncEngine(connector, sink, store, System::currentTimeMillis, ::keepRaw, phoneClockWrong = ::phoneClockWrong, describe = describe)
    private val presence = PresenceWatch(context)

    /** Asked for by hand, and taken up by nobody yet. */
    private val requested = AtomicBoolean(false)

    /** The system has begun the run that was asked for by hand; it has not reached the machine yet. */
    private val starting = AtomicBoolean(false)

    /** A run of this object's holds the machine. */
    private val active = AtomicBoolean(false)

    /** The report of the latest run in this process: shown even if it could not be stored. */
    @Volatile
    private var latest: SyncReport? = null

    private val _status = MutableStateFlow(SyncStatus(running = false, lastReport = null, lastCompleteAtMs = null, storeUnreadable = false))
    val status: StateFlow<SyncStatus> = _status

    val enabled: Boolean get() = BuildConfig.SYNC_ENABLED

    /** Reads what the last run left, for the screen. */
    suspend fun load() = refresh()

    /**
     * Asks for a run now and returns at once. It is given to the system to run, so that it
     * carries on if the screen goes off; if the system has not begun it within a few seconds
     * it is run here instead.
     */
    fun syncNow() {
        if (!enabled || active.get() || starting.get() || !requested.compareAndSet(false, true)) return
        publish()
        val handedOver = runCatching { enqueue(NOW_WORK, SyncWorker.TAG_NOW) }.isSuccess
        scope.launch {
            if (handedOver) delay(START_GRACE_MS)
            // Whoever takes the request has it: the system's run when it begins, or this one. Never both.
            if (requested.compareAndSet(true, false)) {
                runCatching { WorkManager.getInstance(context).cancelUniqueWork(NOW_WORK) }
                if (runOnce() != null) keepWatch() else publish()
            }
        }
    }

    /**
     * Called first thing by the system's run of a request made by hand: takes the request, so
     * that it is not also run here. [doneStarting] must follow, whatever happens.
     */
    fun beginRequested() {
        starting.set(true)
        requested.set(false)
        publish()
    }

    fun doneStarting() {
        starting.set(false)
        publish()
    }

    /** The phone has heard the paired machine come into range. */
    fun onMachineHeard() {
        if (!enabled) return
        // Decided here when it can be, to spare the system a piece of work that would do nothing;
        // decided again by the work itself, which knows the stored state for certain.
        when (val wait = heardWaitMs()) {
            null -> Unit
            0L -> enqueue(HEARD_WORK, SyncWorker.TAG_HEARD)
            else -> retryLater(wait)
        }
    }

    /**
     * How long a run begun because the machine was heard should wait: not at all, some
     * milliseconds, or null for no run. Everything came across a short while ago; nothing can
     * change until the user has acted; or an attempt was made minutes ago, as happens with a
     * machine at the edge of range that is heard, lost and heard again.
     */
    fun heardWaitMs(): Long? {
        val report = _status.value.lastReport ?: return 0
        val age = abs(System.currentTimeMillis() - report.atMs)
        return when {
            report.outcome in USER_DECIDES -> null
            report.outcome == SyncOutcome.COMPLETE && age < FRESH_MS -> null
            age < HEARD_GAP_MS -> HEARD_GAP_MS - age
            else -> 0
        }
    }

    /**
     * One more attempt in a while, after the machine was heard but could not be reached or
     * not everything came across: it may have been at the edge of range. One, not a series;
     * the hourly run is the series.
     */
    fun retryLater(waitMs: Long = HEARD_GAP_MS) {
        if (!enabled) return
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(waitMs.coerceIn(1_000, HEARD_GAP_MS), TimeUnit.MILLISECONDS)
            .addTag(SyncWorker.TAG_RETRY)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(RETRY_WORK, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * The app has come to the front. If the last attempt is old, or was stopped by something
     * the user may since have put right (Bluetooth, a permission), a run is started, so that
     * what the screen says is how things are now. Never before the first run by hand.
     */
    fun onForeground() {
        if (!enabled) return
        scope.launch {
            // Not beside a device check, which has the machine and the user's attention.
            if (claim.taken) return@launch
            pairings.current()
            refresh()
            val status = _status.value
            val report = status.lastReport ?: return@launch
            if (status.running || status.storeUnreadable) return@launch
            val age = abs(System.currentTimeMillis() - report.atMs)
            val again = when (report.outcome) {
                in PRECONDITIONS -> age > RECHECK_MS && engine.blockedBy() == null
                in USER_DECIDES -> false
                else -> age > FRESH_MS
            }
            if (again && !claim.taken) syncNow()
        }
    }

    /**
     * Runs one synchronisation to its end. Returns its report, or null if it did not run:
     * something else has the machine (a device check, another run), or this build does not
     * synchronise.
     */
    suspend fun runOnce(): SyncReport? {
        if (!enabled) return null
        if (!claim.tryTake()) return null
        active.set(true)
        publish()
        var report: SyncReport? = null
        try {
            // The machine does not answer a new connection in the first seconds after the last one
            // ended. Only if this run can get as far as calling it at all.
            if (engine.blockedBy() == null) delay(claim.restMs())
            report = engine.sync()?.also { latest = it }
            return report
        } finally {
            // In this order, and at once: the screen must never show a run that holds nothing,
            // nor offer a button while the machine is still taken.
            claim.release(connected = report?.outcome !in PRECONDITIONS)
            active.set(false)
            publish()
            withContext(NonCancellable) { refresh() }
        }
    }

    /**
     * The user accepts the machine's clock as it now is, after it was found to have been changed.
     * Everything is then looked at again from the start: a clock that was set back has put its
     * new nights before the place the last run had reached.
     */
    fun acceptMachineClock() {
        scope.launch {
            change { it.with(clock = null).fromTheStart() }
            syncNow()
        }
    }

    /** Writes everything again, for when Health Connect's data was cleared. */
    fun rewriteEverything() {
        scope.launch {
            change { it.with(rewriteRequested = true).fromTheStart() }
            syncNow()
        }
    }

    /**
     * Throws away a record of synchronising that can no longer be read, and starts again.
     * Every night the machine still holds is then cleared in Health Connect and written afresh.
     */
    fun startAgain() {
        scope.launch {
            engine.whileIdle { withContext(Dispatchers.IO) { store.delete() } }
            latest = null
            refresh()
            syncNow()
        }
    }

    /** Sets or removes the leak rule. Every night is then looked at again. */
    suspend fun setLeakLimit(litresPerMinute: Int?) = change { it.withLeakLimit(litresPerMinute) }

    /**
     * Forgets everything about synchronising, when the machine is forgotten, and stops the
     * background runs. Health Connect is not touched.
     */
    suspend fun forget() {
        stopWatch()
        engine.whileIdle {
            withContext(Dispatchers.IO) {
                store.delete()
                File(context.filesDir, RAW_DIRECTORY).deleteRecursively()
            }
        }
        latest = null
        refresh()
    }

    /**
     * From now on: a run about once an hour, and one when the machine is heard coming into
     * range. Asked for after a run by hand and at every start of the app. Safe to call repeatedly.
     */
    fun keepWatch() {
        if (!enabled) return
        val hourly = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
            // Not at once: the run that led here has only just finished.
            .setInitialDelay(1, TimeUnit.HOURS)
            .build()
        // Kept if it is already there: replacing it would cancel the very run that may have started this process.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(HOURLY_WORK, ExistingPeriodicWorkPolicy.KEEP, hourly)
        listenForMachine()
    }

    /**
     * Asks the phone to say when the paired machine is heard. Renewed by every background run:
     * the phone forgets the request when Bluetooth is switched off or the phone restarts. A
     * request already in place is left as it is.
     */
    fun listenForMachine() {
        if (!enabled) return
        (pairings.state.value as? PairingState.Paired)?.let { presence.start(it.pairing.address) }
    }

    /** Stops the background runs. */
    fun stopWatch() {
        val work = WorkManager.getInstance(context)
        for (name in listOf(HOURLY_WORK, NOW_WORK, HEARD_WORK, RETRY_WORK)) work.cancelUniqueWork(name)
        presence.stop()
    }

    /** The last attempt, for the saved report: its outcome, when, and a failure label. No counts: a count of minutes is a night's length. */
    fun reportText(): String {
        val status = _status.value
        val report = status.lastReport
        return buildString {
            appendLine("sync enabled $enabled")
            if (status.storeUnreadable) appendLine("sync record unreadable")
            if (report != null) {
                appendLine("sync last ${report.outcome} at ${Instant.ofEpochMilli(report.atMs)}")
                report.code?.let { appendLine("  $it") }
            }
            status.lastCompleteAtMs?.let { appendLine("sync last complete at ${Instant.ofEpochMilli(it)}") }
        }
    }

    private fun enqueue(name: String, tag: String) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            // Run promptly and kept alive while it runs; if the system has no room for that, as ordinary work.
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(tag)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
    }

    /** Waits for a run in progress to finish: it holds the state in hand, and would overwrite the change. */
    private suspend fun change(transform: (SyncState) -> SyncState) {
        engine.whileIdle { withContext(Dispatchers.IO) { runCatching { store.write(transform(store.read())) } } }
        refresh()
    }

    private fun publish() {
        _status.update { SyncStatus(requested.get() || starting.get() || active.get(), it.lastReport, it.lastCompleteAtMs, it.storeUnreadable) }
    }

    /** Reads the stored report again. Whether a run is in progress is not its affair. */
    private suspend fun refresh() {
        val state = withContext(Dispatchers.IO) { runCatching { store.read() } }
        _status.update { before ->
            val stored = state.getOrNull()
            when {
                stored != null -> {
                    // A run that could not store its report has still happened, and is what the screen should say.
                    val report = listOfNotNull(stored.lastReport, latest).maxByOrNull { it.atMs }
                    SyncStatus(before.running, report, stored.lastCompleteAtMs, storeUnreadable = false)
                }
                // Damaged: the user is told, and offered a fresh start.
                state.exceptionOrNull() is SyncStateUnreadableException -> SyncStatus(before.running, latest, null, storeUnreadable = true)
                // Could not be read this time (the storage was busy or full). What is known stays as it was.
                else -> SyncStatus(before.running, latest ?: before.lastReport, before.lastCompleteAtMs, before.storeUnreadable)
            }
        }
    }

    /**
     * Whether the phone's clock is known to be wrong: compared with the time the phone has from
     * its network, when it has one. With none to compare with, the clock is taken as it is.
     */
    private fun phoneClockWrong(): Boolean = try {
        abs(SystemClock.currentNetworkTimeClock().millis() - System.currentTimeMillis()) > PHONE_CLOCK_TOLERANCE_MS
    } catch (e: DateTimeException) {
        false
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
        const val HOURLY_WORK = "sync"
        const val NOW_WORK = "sync-now"
        const val HEARD_WORK = "sync-heard"
        const val RETRY_WORK = "sync-retry"
        const val RAW_DIRECTORY = "raw"
        const val MAX_RAW_FILES = 400
        const val PHONE_CLOCK_TOLERANCE_MS = 30_000L

        /** How long the system is given to begin a run asked for by hand. */
        const val START_GRACE_MS = 4_000L

        /** A complete run younger than this is not repeated just because the app is opened or the machine heard. */
        const val FRESH_MS = 15L * 60 * 1000

        /** The least time between an attempt of any kind and one begun because the machine was heard. */
        const val HEARD_GAP_MS = 5L * 60 * 1000

        /** The least time before a run stopped by a missing permission or the like is tried again on opening the app. */
        const val RECHECK_MS = 60_000L

        /** Stopped before anything was asked of the machine, by something the user can put right. */
        val PRECONDITIONS = setOf(
            SyncOutcome.BLUETOOTH_OFF, SyncOutcome.BLUETOOTH_NOT_ALLOWED, SyncOutcome.HEALTH_CONNECT_NOT_ALLOWED,
            SyncOutcome.HEALTH_CONNECT_UNAVAILABLE, SyncOutcome.NOT_PAIRED, SyncOutcome.PHONE_CLOCK_WRONG,
        )

        /** Trying again changes nothing until the user has acted. */
        val USER_DECIDES = setOf(
            SyncOutcome.CLOCK_CHANGED, SyncOutcome.PAIRING_REJECTED, SyncOutcome.WRONG_MACHINE, SyncOutcome.SOFTWARE_NOT_CONFIRMED,
        )
    }
}
