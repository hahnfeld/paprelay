// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.sync

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
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
import com.everysoft.paprelay.as11.decode.Firmware
import com.everysoft.paprelay.ble.MachineClaim
import com.everysoft.paprelay.ble.PresenceWatch
import com.everysoft.paprelay.core.nights.Night
import com.everysoft.paprelay.core.nights.NightReport
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.DateTimeException
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** What the screen needs to know about synchronising. */
class SyncStatus(
    val running: Boolean,
    val lastReport: SyncReport?,
    val lastCompleteAtMs: Long?,
    /** True when the stored record of synchronising is damaged and cannot be read. */
    val storeUnreadable: Boolean,
    /** What the user has chosen to have passed on, and where. */
    val respiratoryRate: Boolean = false,
    val healthRecords: Boolean = true,
    val exportFile: String? = null,
    /** True when the table of nights could not be written to the chosen file at the last attempt. */
    val exportFailed: Boolean = false,
    /** The chosen file's name as the user sees it, when it is known. */
    val exportName: String? = null,
    /** False when the machine's software, as last seen, is one whose respiratory rate is not read. */
    val rateReadable: Boolean = true,
) {
    fun with(
        running: Boolean = this.running,
        lastReport: SyncReport? = this.lastReport,
        lastCompleteAtMs: Long? = this.lastCompleteAtMs,
        storeUnreadable: Boolean = this.storeUnreadable,
        respiratoryRate: Boolean = this.respiratoryRate,
        healthRecords: Boolean = this.healthRecords,
        exportFile: String? = this.exportFile,
        exportFailed: Boolean = this.exportFailed,
        exportName: String? = this.exportName,
        rateReadable: Boolean = this.rateReadable,
    ) = SyncStatus(running, lastReport, lastCompleteAtMs, storeUnreadable, respiratoryRate, healthRecords, exportFile, exportFailed, exportName, rateReadable)
}

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
    /** The phone's health records, or null on a phone that has none. */
    records: NightRecords?,
    describe: (Throwable) -> String?,
) {
    private val store = SyncStore(File(context.noBackupFilesDir, "sync.json"))
    private val nightStore = NightStore(File(context.noBackupFilesDir, "nights.json"))
    private val nightFile = NightFile(context)
    private val engine = SyncEngine(
        connector, sink, store, System::currentTimeMillis, ::keepRaw,
        phoneClockWrong = ::phoneClockWrong, describe = describe, nights = nightStore, records = records,
    )

    private val exporting = Mutex()

    /** What the table was when it was last written to the chosen file in this process: its hash and length. */
    @Volatile
    private var exported: Pair<Int, Int>? = null

    private val _nights = MutableStateFlow<List<Night>>(emptyList())

    /** The nights kept on the phone, newest first. */
    val nights: StateFlow<List<Night>> = _nights
    private val presence = PresenceWatch(context)

    /**
     * Where a run asked for by hand stands: not asked for; asked for and taken up by nobody yet;
     * taken by the system's run; or taken by the run made here in its place. Exactly one of the
     * last two ever takes a request.
     */
    private val request = AtomicInteger(IDLE)

    /** Counts the requests made by hand, so that the wait begun for one cannot act on the next. */
    private val requests = AtomicInteger(0)

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
        if (!enabled || active.get() || !request.compareAndSet(IDLE, REQUESTED)) return
        val mine = requests.incrementAndGet()
        publish()
        val handedOver = runCatching { enqueue(NOW_WORK, SyncWorker.TAG_NOW) }.isSuccess
        scope.launch {
            if (handedOver) delay(START_GRACE_MS)
            // Whoever takes the request has it: the system's run when it begins, or this one. Never both.
            // (And only the request this wait was begun for: a later one has its own.)
            if (requests.get() == mine && request.compareAndSet(REQUESTED, LOCAL)) {
                try {
                    runCatching { WorkManager.getInstance(context).cancelUniqueWork(NOW_WORK) }
                    val report = runOnce()
                    if (report != null) keepWatch()
                    followUp(report)
                } finally {
                    request.set(IDLE)
                    publish()
                }
            }
        }
    }

    /**
     * Called first thing by the system's run of a request made by hand. Returns true if the run
     * is the system's to make: it has taken the request, or there was none in this process (the
     * app was restarted in between). False if the request is already being run here.
     * [doneRequested] must follow a true, whatever happens.
     */
    fun beginRequested(): Boolean {
        val mine = request.compareAndSet(REQUESTED, WORKER) || request.compareAndSet(IDLE, WORKER)
        publish()
        return mine
    }

    fun doneRequested() {
        request.compareAndSet(WORKER, IDLE)
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
            else -> heardTooSoon(wait)
        }
    }

    /**
     * The machine was heard within minutes of an attempt. If that attempt got no answer at all,
     * one more is worth making once the gap has passed: the phone has probably just come into
     * range. After any other attempt the hearing is let go: a connection that ends makes the
     * machine be heard again, and each run would bring the next.
     */
    fun heardTooSoon(waitMs: Long) {
        if (_status.value.lastReport?.outcome == SyncOutcome.MACHINE_AWAY) retryLater(waitMs)
    }

    /**
     * After a run that left a session unwritten because it had only just ended, or might still
     * be going on: one further run once it has settled. Soon, and not an hour from now, when
     * the phone may have left the house.
     */
    fun followUp(report: SyncReport?) {
        if (report?.sessionInProgress == true) retryLater(SyncEngine.SETTLE_MS + 60_000)
    }

    /** False when a further attempt could change nothing until the user has acted. */
    fun worthRetrying(): Boolean = _status.value.lastReport?.outcome !in USER_DECIDES

    /**
     * How long a run begun because the machine was heard should wait: not at all, some
     * milliseconds, or null for no run. Everything was copied a short while ago; nothing can
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
     * not everything was copied: it may have been at the edge of range. Only one retry;
     * the hourly run covers the rest.
     */
    fun retryLater(waitMs: Long = HEARD_GAP_MS) {
        if (!enabled) return
        val work = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(waitMs.coerceIn(1_000, MAX_RETRY_WAIT_MS), TimeUnit.MILLISECONDS)
            .addTag(SyncWorker.TAG_RETRY)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(RETRY_WORK, ExistingWorkPolicy.KEEP, work)
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
        // True until it is known that this run cannot get as far as calling the machine.
        var mayConnect = true
        var report: SyncReport? = null
        try {
            mayConnect = engine.blockedBy() == null
            // The machine does not answer a new connection in the first seconds after the last one ended.
            if (mayConnect) delay(claim.restMs())
            report = engine.sync()?.also { latest = it }
            return report
        } finally {
            // In this order, and at once: the screen must never show a run that holds nothing,
            // nor offer a button while the machine is still taken.
            // (What stood in the way may have gone between the look above and the run itself.)
            claim.release(connected = mayConnect || report?.outcome !in PRECONDITIONS)
            active.set(false)
            publish()
            withContext(NonCancellable) {
                refresh()
                export()
            }
        }
    }

    /**
     * The user accepts the machine's clock as it now is, after it was found to have been changed.
     * Everything is then looked at again from the start: a clock that was set back has put its
     * new nights before the place the last run had reached.
     */
    fun acceptMachineClock() {
        scope.launch {
            // The clock may have been set back past the moment the machine's present software was
            // first seen, so that moment no longer tells its sessions from older ones.
            change { it.with(clock = null, softwareSinceMs = null).fromTheStart() }
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
     * Throws away a record of synchronising that can no longer be read, and starts as if new.
     * The Sharing choices go back to their defaults (respiratory rate off, no file). The nights
     * keep their own marks and are not written again. Respiratory rate is cleared and written
     * again once it is switched on again.
     */
    fun startAgain() {
        scope.launch {
            engine.whileIdle { withContext(Dispatchers.IO) { store.delete() } }
            latest = null
            refresh()
            syncNow()
        }
    }

    /**
     * Switches the minute-by-minute respiratory rate on or off. Switched on, every night the
     * machine holds is read for it; switched off, what was written stays where it is.
     */
    fun setRespiratoryRate(on: Boolean) {
        scope.launch {
            change { if (on) it.with(respiratoryRate = true).fromTheStart() else it.with(respiratoryRate = false) }
            if (on || blockedByHealthConnect()) syncNow()
        }
    }

    /** Switches the writing of each night's figures to the phone's health records on or off. */
    fun setHealthRecords(on: Boolean) {
        scope.launch {
            change { it.with(healthRecords = on) }
            if (on || blockedByHealthConnect()) syncNow()
        }
    }

    /**
     * Sets the file the table of nights is kept in, or none. [file] is what the system's "save
     * as" screen returned; the right to write to it is kept until another is chosen.
     */
    fun setExportFile(file: Uri?) {
        scope.launch {
            val before = _status.value.exportFile
            // The new right is taken before anything is stored, and the old one given up only once the new choice is.
            if (file != null) withContext(Dispatchers.IO) { runCatching { nightFile.keep(file) } }
            val stored = change { it.with(exportFile = file?.toString()) }
            if (stored && before != null && before != file?.toString()) withContext(Dispatchers.IO) { nightFile.release(before.toUri()) }
            exported = null
            _status.update { it.with(exportFailed = false, exportName = null) }
            export()
        }
    }

    /** Whether the last run ended on something Health Connect did not allow: switching that thing off is then a reason for another. */
    private fun blockedByHealthConnect(): Boolean = _status.value.lastReport?.outcome == SyncOutcome.HEALTH_CONNECT_NOT_ALLOWED

    /** What the table is, and the table itself, as text to hand to another app. */
    fun shareText(): String = NightReport.EXPLANATION + "\n\n" + NightReport.csv(_nights.value.take(MAX_SHARED_NIGHTS))

    /** Sets or removes the leak rule. Every night is then looked at again. */
    suspend fun setLeakLimit(litresPerMinute: Int?) = change { it.withLeakLimit(litresPerMinute) }

    /**
     * Forgets everything about synchronising, when the machine is forgotten, and stops the
     * background runs. Health Connect is not touched.
     */
    suspend fun forget() {
        stopWatch()
        engine.whileIdle {
            // What the user chose (what is passed on, and the folder) is theirs, not the machine's, and stays.
            withContext(Dispatchers.IO) {
                val kept = runCatching { store.read().cleared() }.getOrNull()
                store.delete()
                if (kept != null) runCatching { store.write(kept) }
                nightStore.delete()
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
        // Kept if it is already there: asking again must not disturb the run that may have started this process.
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
        val work = OneTimeWorkRequestBuilder<SyncWorker>()
            // Run promptly and kept alive while it runs; if the system has no room for that, as ordinary work.
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(tag)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, work)
    }

    /**
     * Waits for a run in progress to finish: it holds the state in hand, and would overwrite the change.
     * Returns whether the change was stored.
     */
    private suspend fun change(transform: (SyncState) -> SyncState): Boolean {
        val stored = engine.whileIdle { withContext(Dispatchers.IO) { runCatching { store.write(transform(store.read())) }.isSuccess } }
        refresh()
        return stored
    }

    private fun publish() {
        _status.update { it.with(running = request.get() != IDLE || active.get()) }
    }

    /**
     * Writes the table of nights to the chosen file, if one is chosen and the table has changed
     * since it was last written there (or the last attempt failed). One at a time.
     */
    private suspend fun export() = exporting.withLock {
        val file = _status.value.exportFile ?: return@withLock
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                val all = nightStore.read().all
                // With nothing to say the file is left as it is: an empty table must never replace a full one.
                if (all.none { it.complete }) return@runCatching null
                val table = NightReport.csv(all)
                val print = table.hashCode() to table.length
                if (exported == print && !_status.value.exportFailed) return@runCatching null
                val written = nightFile.write(file.toUri(), table)
                if (written) exported = print
                written to nightFile.name(file.toUri())
            }.getOrDefault(false to null)
        } ?: return@withLock
        _status.update { it.with(exportFailed = !outcome.first, exportName = outcome.second ?: it.exportName) }
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
                    before.with(
                        lastReport = report, lastCompleteAtMs = stored.lastCompleteAtMs, storeUnreadable = false,
                        respiratoryRate = stored.respiratoryRate, healthRecords = stored.healthRecords, exportFile = stored.exportFile,
                        // Not known until the machine has been seen once; until then nothing is said against it.
                        rateReadable = stored.softwareSeen?.let { Firmware.reportedByMachine(it).perMinuteAccepted } ?: true,
                    )
                }
                // Damaged: the user is told, and offered a fresh start.
                state.exceptionOrNull() is SyncStateUnreadableException -> before.with(lastReport = latest, lastCompleteAtMs = null, storeUnreadable = true)
                // Could not be read this time (the storage was busy or full). What is known stays as it was.
                else -> before.with(lastReport = latest ?: before.lastReport)
            }
        }
        withContext(Dispatchers.IO) { runCatching { nightStore.read().all } }.getOrNull()?.let { _nights.value = it }
        // The name of the chosen file, asked for once.
        val file = _status.value.exportFile
        if (file != null && _status.value.exportName == null) {
            withContext(Dispatchers.IO) { nightFile.name(file.toUri()) }?.let { name -> _status.update { it.with(exportName = name) } }
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
    } catch (e: RuntimeException) {
        // The time service itself failed. That says nothing about the clock.
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
        const val IDLE = 0
        const val REQUESTED = 1
        const val WORKER = 2
        const val LOCAL = 3
        const val HOURLY_WORK = "sync"
        const val NOW_WORK = "sync-now"
        const val HEARD_WORK = "sync-heard"
        const val RETRY_WORK = "sync-retry"
        const val RAW_DIRECTORY = "raw"
        const val MAX_RAW_FILES = 400

        /** A year and a little: as many nights as are worth handing over as text. */
        const val MAX_SHARED_NIGHTS = 400
        const val PHONE_CLOCK_TOLERANCE_MS = 30_000L

        /** How long the system is given to begin a run asked for by hand. */
        const val START_GRACE_MS = 4_000L

        /** A complete run younger than this is not repeated just because the app is opened or the machine heard. */
        const val FRESH_MS = 15L * 60 * 1000

        /** The least time between an attempt of any kind and one begun because the machine was heard. */
        const val HEARD_GAP_MS = 5L * 60 * 1000

        const val MAX_RETRY_WAIT_MS = 30L * 60 * 1000

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
