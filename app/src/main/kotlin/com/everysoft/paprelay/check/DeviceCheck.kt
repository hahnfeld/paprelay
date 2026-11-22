// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.check

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import com.everysoft.paprelay.R
import android.os.Build
import com.everysoft.paprelay.BuildConfig
import com.everysoft.paprelay.as11.As11AuthException
import com.everysoft.paprelay.as11.As11Client
import com.everysoft.paprelay.as11.As11DefectException
import com.everysoft.paprelay.as11.As11Exception
import com.everysoft.paprelay.as11.As11NonceStoreException
import com.everysoft.paprelay.as11.As11PairingCancelledException
import com.everysoft.paprelay.as11.As11ProtocolException
import com.everysoft.paprelay.as11.As11RpcException
import com.everysoft.paprelay.as11.As11SpoolIntegrityException
import com.everysoft.paprelay.as11.As11SpoolStalledException
import com.everysoft.paprelay.as11.As11SpoolUnavailableException
import com.everysoft.paprelay.as11.As11SpoolUnverifiedException
import com.everysoft.paprelay.as11.As11TimeoutException
import com.everysoft.paprelay.as11.As11TransportException
import com.everysoft.paprelay.as11.GetItem
import com.everysoft.paprelay.as11.Hex
import com.everysoft.paprelay.as11.SpoolAddress
import com.everysoft.paprelay.as11.SpoolName
import com.everysoft.paprelay.ble.BluetoothOffException
import com.everysoft.paprelay.ble.GattLink
import com.everysoft.paprelay.ble.MachineClaim
import com.everysoft.paprelay.ble.MachineScanner
import com.everysoft.paprelay.ble.NoAnswerException
import com.everysoft.paprelay.ble.Sighting
import com.everysoft.paprelay.hc.HealthConnectProbe
import com.everysoft.paprelay.hc.HealthRecords
import com.everysoft.paprelay.hc.HealthConnectUnresponsiveException
import com.everysoft.paprelay.pairing.FileNonceRegistry
import com.everysoft.paprelay.pairing.Pairing
import com.everysoft.paprelay.pairing.PairingState
import com.everysoft.paprelay.pairing.PairingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.Locale

enum class StepState { PENDING, RUNNING, PASSED, FAILED, SKIPPED, NOTED, CANCELLED }

/**
 * A piece of text for the screen, held as a resource and its arguments so it is
 * shown in the user's language. An argument may itself be a [Message].
 */
class Message(@StringRes val id: Int, vararg val args: Any) {
    fun resolve(context: Context): String =
        context.getString(id, *args.map { if (it is Message) it.resolve(context) else it }.toTypedArray())
}

data class Step(
    val id: String,
    val state: StepState = StepState.PENDING,
    val detail: Message? = null,
    /** For a failed step: true when the cause is on this phone (storage, Bluetooth, a permission), not the machine. */
    val onPhone: Boolean = false,
)

/** Identifiers of the steps, for anything that needs to tell them apart. */
object CheckSteps {
    const val HEALTH = "health"
    const val SCAN = "scan"
    const val PAIR = "pair"
    const val SESSION = "session"
    const val IDENTITY = "identity"
    const val CLOCK = "clock"
    const val DOWNLOAD = "download"

    @StringRes
    fun title(id: String): Int = when (id) {
        HEALTH -> R.string.step_health
        SCAN -> R.string.step_scan
        PAIR -> R.string.step_pair
        SESSION -> R.string.step_session
        IDENTITY -> R.string.step_identity
        CLOCK -> R.string.step_clock
        else -> R.string.step_download
    }
}

/**
 * The end of a device check, as the screens weigh it against synchronising. Which synchronisation
 * was the latest when the check ended is noted by its report's own stamp, so that a later one is
 * recognised as later without comparing two clocks: a report stamped while the phone's clock was
 * wrong would otherwise outrank every check that follows it.
 */
class CheckEnd(
    /** The stamp of the latest synchronisation's report when the check ended, or null if there was none. */
    val lastSyncAtMs: Long?,
)

/** More than one machine is in range and the user has to say which is theirs. */
class MachineChoice(val options: List<Message>, val answer: CompletableDeferred<Int>)

/** A check that failed for a reason this class can state plainly. */
private class CheckFailure(val detail: Message, val onPhone: Boolean = false) : Exception()

/** A step that found something worth saying but is not a failure. */
private class Observation(val detail: Message) : Exception()

/**
 * The first-contact check: establishes, on the user's own phone and machine,
 * the facts the rest of the app depends on. Each step records what it saw so
 * the outcome can be read from the exported report alone.
 *
 * It reads from the machine and never changes anything on it. Its one write
 * anywhere is a Health Connect test value that it removes again.
 *
 * Everything shown on screen is a string resource, so it can be translated.
 * The report is always written in English, for whoever diagnoses it, and never
 * contains the pairing key, the code, a full serial number, a Bluetooth address
 * or an advertised name.
 */
class DeviceCheck(
    private val context: Context,
    private val scope: CoroutineScope,
    private val store: PairingStore,
    private val scanner: MachineScanner,
    private val healthConnect: HealthConnectProbe,
    private val healthRecords: HealthRecords,
    /** Whether each is switched on to be written to Health Connect: the nights as health records, and respiratory rate. */
    private val recordsWanted: () -> Boolean,
    private val vitalsWanted: () -> Boolean,
    /** The time stamp of the latest synchronisation's report, if there is one: it tells that report apart from any later one. */
    private val lastSyncAtMs: () -> Long?,
    private val claim: MachineClaim,
    /** Removes what synchronising remembers about the machine, when the machine is forgotten. */
    private val forgetSync: suspend () -> Unit,
) {
    private val report = StringBuilder()
    private var job: Job? = null

    /** Resolves messages in English whatever the phone's language, for the report. */
    private val english: Context by lazy {
        context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) })
    }

    private val _steps = MutableStateFlow(template())
    val steps: StateFlow<List<Step>> = _steps

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    /** Non-null while the machine is showing a code the user must type in. */
    private val _passkeyRequest = MutableStateFlow<CompletableDeferred<String>?>(null)
    val passkeyRequest: StateFlow<CompletableDeferred<String>?> = _passkeyRequest

    private val _machineChoice = MutableStateFlow<MachineChoice?>(null)
    val machineChoice: StateFlow<MachineChoice?> = _machineChoice

    /** True from the moment Stop is pressed until the run has actually ended. */
    private val _stopping = MutableStateFlow(false)
    val stopping: StateFlow<Boolean> = _stopping

    /** True while the pairing is being removed. */
    private val _forgetting = MutableStateFlow(false)
    val forgetting: StateFlow<Boolean> = _forgetting

    /**
     * How the last run stands against synchronising: set when a run ends (passed, failed or
     * stopped), null before the first run and again once the machine is forgotten. Kept in memory
     * only, like the steps, so that the screen can tell whether the check or a synchronisation
     * happened last.
     */
    private val _ended = MutableStateFlow<CheckEnd?>(null)
    val ended: StateFlow<CheckEnd?> = _ended

    fun reportText(): String = synchronized(report) { report.toString() }

    fun hasReport(): Boolean = synchronized(report) { report.isNotEmpty() }

    /**
     * Starts a run; ignored if one is in progress. It outlives the screen that
     * started it and stops on its own after [OVERALL_LIMIT_MS] at most. With a
     * machine already paired it looks for that machine and connects to it,
     * without pairing again.
     */
    @Synchronized
    fun start() {
        // A cancelled run is still unwinding until it completes; wait for that. And a run
        // must not begin on a pairing that is in the middle of being forgotten.
        if (job?.isCompleted == false || _forgetting.value) return
        // The machine takes one connection at a time: a synchronisation that has it finishes first.
        if (!claim.tryTake()) return
        _stopping.value = false
        _running.value = true
        // Atomic: a run stopped before it has been given a thread still reaches its clean-up.
        @OptIn(DelicateCoroutinesApi::class)
        job = scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                _steps.value = template()
                synchronized(report) {
                    report.setLength(0)
                    report.appendLine("PAPRelay device check  ${Instant.now()}")
                    report.appendLine("app ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.GIT_COMMIT}  android ${Build.VERSION.RELEASE} sdk ${Build.VERSION.SDK_INT}")
                    report.appendLine("phone ${Build.MANUFACTURER} ${Build.MODEL}")
                }
                val finished = withTimeoutOrNull(OVERALL_LIMIT_MS) { execute() }
                if (finished == null) closeOpenSteps(StepState.FAILED, Message(R.string.detail_took_too_long))
            } catch (e: CancellationException) {
                closeOpenSteps(StepState.CANCELLED, Message(R.string.detail_stopped))
                throw e
            } finally {
                _passkeyRequest.value = null
                _machineChoice.value = null
                // Noted while the machine is still held, so that no synchronisation can end in between.
                _ended.value = CheckEnd(lastSyncAtMs())
                // Given up before the screen hears that the run is over, so that whatever the
                // screen starts next finds the machine free.
                claim.release()
                // "running" first, so the screen never shows a run that is over as still stopping.
                // A Stop that arrives in the instant before the job completes can still set
                // "stopping" again; nothing reads it while no run is shown, and start() clears it.
                _running.value = false
                _stopping.value = false
            }
        }
    }

    /** Asks the run to stop. It may take a while: a step that must not be cut short is finished first. */
    @Synchronized
    fun cancel() {
        val running = job?.takeIf { !it.isCompleted } ?: return
        _stopping.value = true
        running.cancel()
    }

    /**
     * Removes the stored pairing, what the check downloaded and what synchronising
     * remembers. Ignored while a run or a synchronisation is in progress; neither
     * can start until it has finished. Returns false if it was ignored.
     */
    @Synchronized
    fun forgetMachine(): Boolean {
        if (job?.isCompleted == false || _forgetting.value) return false
        // Not under a synchronisation either: it is using the pairing this removes.
        if (!claim.tryTake()) return false
        _forgetting.value = true
        scope.launch {
            try {
                store.clear()
                clearData()
                forgetSync()
            } finally {
                claim.release(connected = false)
                _forgetting.value = false
            }
        }
        return true
    }

    /**
     * Deletes the therapy data the check downloaded and clears the last run's
     * outcome from the screen, so nothing describes a machine that is no longer
     * paired. The report is kept: it holds nothing secret, and after a failed
     * attempt it is the one thing worth saving. Called when the machine is
     * forgotten; does nothing to a run in progress.
     */
    private suspend fun clearData() {
        withContext(Dispatchers.IO) { dataDirectory().deleteRecursively() }
        synchronized(this) {
            if (job?.isCompleted != false) {
                _steps.value = template()
                _ended.value = null
            }
        }
    }

    private suspend fun execute() {
        step(HEALTH) {
            // Two things can be written: each night's figures as health records, where the phone keeps
            // them, and respiratory rate. What is switched on is proved; what is switched off is left alone.
            val wantRecords = recordsWanted() && healthRecords.supported
            val wantVitals = vitalsWanted()
            if (!wantRecords && !wantVitals) throw Observation(Message(R.string.detail_hc_nothing))
            if (!healthConnect.available) throw CheckFailure(Message(R.string.detail_hc_unavailable), onPhone = true)
            if (wantRecords) {
                if (!healthRecords.allowed) throw CheckFailure(Message(R.string.detail_records_not_allowed), onPhone = true)
                val probe = try {
                    healthRecords.probe()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    note("  health records: ${code(e)}")
                    val silent = generateSequence<Throwable>(e) { it.cause?.takeIf { cause -> cause !== it } }.take(MAX_CAUSE_DEPTH)
                        .any { it is HealthConnectUnresponsiveException }
                    throw CheckFailure(Message(if (silent) R.string.error_hc_unresponsive else R.string.error_hc_failed), onPhone = true)
                }
                // The reason, when there is one, is Health Connect's word about a made-up night: nothing of the user's.
                note(
                    "  health records: trial night accepted ${probe.accepted}, removed ${probe.removed}, " +
                        "removing what is not there accepted ${probe.removingAbsentAccepted}" + (probe.reason?.let { " (${printable(it, limit = 300)})" } ?: ""),
                )
                if (!probe.accepted) throw CheckFailure(Message(R.string.detail_records_refused), onPhone = true)
                if (!probe.removed) throw CheckFailure(Message(R.string.detail_hc_value_left), onPhone = true)
            } else {
                note("  health records: " + if (healthRecords.supported) "switched off" else "not on this phone")
            }
            if (!wantVitals) {
                note("  respiratory rate: switched off")
                return@step Message(R.string.detail_hc_ok)
            }
            val vitals = withTimeoutOrNull(HEALTH_ANSWER_MS) { healthConnect.hasPermission() }
                ?: throw HealthConnectUnresponsiveException()
            if (!vitals) throw CheckFailure(Message(R.string.detail_hc_no_permission), onPhone = true)
            val result = try {
                healthConnect.writeAndRemove()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (healthConnect.testValueMayRemain) {
                    note("  the test value may still be in Health Connect; PAPRelay removes it the next time the app starts or a check runs")
                }
                // Health Connect's own "something went wrong inside" arrives as this, with no code.
                if (e !is android.os.RemoteException) throw e
                note("  ${code(e)}")
                throw CheckFailure(Message(R.string.error_hc_failed), onPhone = true)
            }
            note("  wrote ${result.written}, removed ${result.removed}, removing what is not there accepted ${result.removingAbsentAccepted}")
            if (!result.removed) throw CheckFailure(Message(R.string.detail_hc_value_left), onPhone = true)
            if (result.written != 1) throw CheckFailure(Message(R.string.detail_hc_not_written), onPhone = true)
            if (result.removingAbsentAccepted == false) throw CheckFailure(Message(R.string.detail_hc_absent_refused), onPhone = true)
            Message(R.string.detail_hc_ok)
        }

        var state = store.current()
        if (state is PairingState.Unavailable) {
            // Checked before anything is asked of the user: pairing now could replace a
            // pairing that is still good, and a choice of machine would be thrown away.
            skip(SCAN, Message(R.string.detail_pairing_state_unknown))
            fail(PAIR, Message(R.string.detail_storage_unreadable_now))
            skipAll(listOf(SESSION, IDENTITY, CLOCK, DOWNLOAD), Message(R.string.detail_pairing_state_unknown))
            return
        }
        if (state !is PairingState.Paired) {
            // Prove a pairing can be stored before the user is asked anything or one is spent on the machine.
            try {
                store.selfTest()
            } catch (e: IOException) {
                note("  ${code(e)}")
                skip(SCAN, Message(R.string.detail_not_attempted))
                fail(PAIR, Message(R.string.detail_storage_not_working))
                skipAll(listOf(SESSION, IDENTITY, CLOCK, DOWNLOAD), Message(R.string.detail_not_paired))
                return
            }
        }

        // The machine heard in the scan, if it was; with a machine already paired, null when it was not heard.
        var target: BluetoothDevice? = null
        // False if every attempt to scan for the paired machine was refused, so that nothing is known about it.
        var looked = true
        val alreadyPaired = (state as? PairingState.Paired)?.pairing
        val scanned = step(SCAN) {
            if (!scanner.bluetoothEnabled) throw CheckFailure(Message(R.string.detail_bluetooth_off), onPhone = true)
            // For a paired machine the scan ends the moment its address is heard, and whatever it
            // sends is recorded: once paired, a machine need not announce itself the way it
            // does while it waits to be paired.
            val heard = try {
                scanner.scan(
                    if (alreadyPaired != null) PAIRED_SCAN_MS else SCAN_MS,
                    platformFilter = false,
                    stopAtAddress = alreadyPaired?.address,
                )
            } catch (e: As11TransportException) {
                // Without a pairing there is nothing to do but report it. With one, a scan that
                // could not run (Android refuses too many in a short time) is only a missed
                // look: the connection that follows needs no scan. Bluetooth being off is not that.
                if (alreadyPaired == null || phoneBluetoothOff(e)) throw e
                note("  scan could not run: ${code(e)}")
                null
            }
            val mainScanRan = heard != null
            val found = heard.orEmpty()
            found.take(MAX_LISTED).forEachIndexed { index, sighting -> note("  machine ${index + 1}: ${describe(sighting)}") }
            if (found.size > MAX_LISTED) note("  and ${found.size - MAX_LISTED} more, not listed")
            // Whether the system's own scan filters can see this machine decides how background
            // discovery has to work: by service while unpaired, by address once paired.
            // Recorded in the report only.
            val filtered = try {
                if (alreadyPaired != null) {
                    scanner.scan(FILTER_SCAN_MS, platformFilter = false, stopAtAddress = alreadyPaired.address, addressFilter = true)
                } else {
                    scanner.scan(FILTER_SCAN_MS, platformFilter = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (phoneBluetoothOff(e)) throw e
                null
            }
            note("  system ${if (alreadyPaired != null) "address" else "service"} filter: ${filtered?.let { "saw ${it.size} machine(s)" } ?: "could not run"}")

            if (alreadyPaired != null) {
                // Nothing to choose: the question is only whether the paired machine is there.
                val inScan = found.indexOfFirst { it.device.address.equals(alreadyPaired.address, ignoreCase = true) }
                // Heard by either scan is heard; the filtered one's sighting is as good as the other's.
                val mine = found.getOrNull(inScan)
                    ?: filtered?.firstOrNull { it.device.address.equals(alreadyPaired.address, ignoreCase = true) }
                        ?.also { note("  paired machine, by the address filter only: ${describe(it)}") }
                if (mine == null) {
                    // Not a failure: a paired machine has been seen to accept a direct connection
                    // while no scan could hear it. The next step finds out.
                    // The filtered scan alone is too short a look to conclude the machine is away.
                    looked = mainScanRan
                    note(
                        if (mainScanRan) {
                            "  paired machine not heard in a scan; ${found.size} other AirSense-like device(s) in range"
                        } else {
                            "  paired machine not heard; the scan could not run, so nothing is known about what is in range"
                        },
                    )
                    throw Observation(Message(R.string.detail_paired_not_seen))
                }
                // In a building with several machines the report must say which line is this one.
                note("  paired machine heard${if (inScan in 0 until MAX_LISTED) " (machine ${inScan + 1} above)" else ""}: looks like a machine=${mine.advertisement.looksLikeAirSense}")
                // Beyond the lines listed above, it has not been described yet.
                if (inScan >= MAX_LISTED) note("  paired machine: ${describe(mine)}")
                target = mine.device
                Message(R.string.detail_paired_found, signal(mine.rssi))
            } else {
                target = when (found.size) {
                    0 -> throw CheckFailure(Message(R.string.detail_none_found))
                    1 -> found[0].device
                    else -> found[choose(found)].device
                }
                if (found.size == 1) {
                    Message(R.string.detail_one_found, signal(found[0].rssi))
                } else {
                    Message(R.string.detail_many_found)
                }
            }
        }
        if (!scanned) {
            // One cause, one failure: nothing after this could succeed, so nothing is attempted.
            skipAll(listOf(PAIR, SESSION, IDENTITY, CLOCK, DOWNLOAD), Message(R.string.detail_not_attempted))
            return
        }

        if (state !is PairingState.Paired) {
            val machine = target
            if (machine == null) {
                skipAll(listOf(PAIR, SESSION, IDENTITY, CLOCK, DOWNLOAD), Message(R.string.detail_not_attempted))
                return
            }
            step(PAIR) {
                GattLink.open(context, machine, CONNECT_MS).use { link ->
                    note("  mtu ${link.summary.mtu}, connect attempts ${link.summary.connectAttempts}")
                    link.summary.services.forEach { note("  service $it") }
                    As11Client(link, scope).use { client ->
                        val credentials = client.pair { askPasskey() }
                        try {
                            store.save(Pairing(credentials, machine.address, null, System.currentTimeMillis()))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            note("  ${code(e)}")
                            throw CheckFailure(Message(R.string.detail_pair_not_saved), onPhone = true)
                        }
                    }
                }
                Message(R.string.detail_paired)
            }
            state = store.current()
            // The machine needs a few seconds before it will accept a new connection.
            if (state is PairingState.Paired) delay(RECONNECT_PAUSE_MS)
        } else {
            skip(PAIR, Message(R.string.detail_already_paired))
        }

        val paired = (state as? PairingState.Paired)?.pairing
        if (paired == null) {
            val why = if (state is PairingState.Unreadable) R.string.detail_pairing_unreadable else R.string.detail_not_paired
            skipAll(listOf(SESSION, IDENTITY, CLOCK, DOWNLOAD), Message(why))
            return
        }

        var link: GattLink? = null
        var client: As11Client? = null
        try {
            step(SESSION) {
                // Prefer a device seen advertising: it carries the right address type. The check
                // has just scanned, so that answer is used rather than asking again.
                val seen: BluetoothDevice? = target
                note(
                    "  paired machine " + when {
                        seen != null -> "seen advertising"
                        looked -> "not seen advertising; trying a direct connection"
                        else -> "not looked for, as no scan could run; trying a direct connection"
                    },
                )
                val device = seen ?: scanner.deviceByAddress(paired.address)
                    ?: throw CheckFailure(Message(R.string.detail_bluetooth_off), onPhone = true)
                val opened = try {
                    // A machine no scan has heard may still answer (a paired one has been seen to), but
                    // if it is simply not there the wait is wasted, so it is kept short. That is only
                    // fair when a scan actually looked for it.
                    GattLink.open(context, device, if (seen != null || !looked) CONNECT_MS else UNSEEN_CONNECT_MS)
                } catch (e: NoAnswerException) {
                    // Nothing answered a machine no scan had heard: most likely it is not there.
                    // Any other failure means something did answer, and is reported as itself.
                    if (seen != null || !looked || phoneBluetoothOff(e)) throw e
                    note("  ${code(e)}")
                    throw CheckFailure(Message(R.string.detail_not_in_range))
                }
                link = opened
                note("  mtu ${opened.summary.mtu}, connect attempts ${opened.summary.connectAttempts}")
                val created = As11Client(opened, scope)
                client = created
                created.startSession(paired.credentials, FileNonceRegistry(File(context.noBackupFilesDir, "nonces.txt")))
                Message(R.string.detail_session_ok)
            }
            val session = client?.takeIf { it.isSecure }
            if (session == null) {
                skipAll(listOf(IDENTITY, CLOCK, DOWNLOAD), Message(R.string.detail_no_session))
                return
            }

            val rightMachine = step(IDENTITY) {
                val read = session.get(listOf(GetItem.SERIAL_NUMBER, GetItem.PRODUCT_NAME, GetItem.PRODUCT_CODE, GetItem.APPLICATION_IDENTIFIER, GetItem.DATA_MODEL_VERSION_IDENTIFIER))
                val serial = (read.values["SerialNumber"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw CheckFailure(Message(R.string.detail_no_serial))
                for (key in REPORTED_IDENTITY) {
                    (read.values[key] as? JsonPrimitive)?.let { note("  $key = ${printable(it.content)}") }
                }
                if (read.unavailable.isNotEmpty()) note("  unavailable ${read.unavailable.map(::printable)}")
                note("  version sections ${session.getVersion().keys.map(::printable)}")
                if (paired.serialNumber == null) {
                    store.save(Pairing(paired.credentials, paired.address, serial, paired.pairedAtMs))
                } else if (paired.serialNumber != serial) {
                    throw CheckFailure(Message(R.string.detail_wrong_machine))
                }
                Message(R.string.detail_identity_ok, serial.takeLast(4))
            }
            if (!rightMachine) {
                skipAll(listOf(CLOCK, DOWNLOAD), Message(R.string.detail_identity_unconfirmed))
                return
            }

            var machineNow = System.currentTimeMillis()
            step(CLOCK) {
                val readings = (1..3).map { session.getDateTime() }
                readings.forEach { note("  machine ${Instant.ofEpochMilli(it.machineEpochMs)} round trip ${it.roundTripMs} ms offset ${it.offsetMs} ms") }
                val best = readings.minBy { it.roundTripMs }
                machineNow = best.machineEpochMs
                val seconds = kotlin.math.abs(best.offsetMs) / 1000.0
                when {
                    seconds < 0.5 -> Message(R.string.detail_clock_agrees)
                    best.offsetMs > 0 -> Message(R.string.detail_clock_behind, seconds)
                    else -> Message(R.string.detail_clock_ahead, seconds)
                }
            }

            if (session.terminated.isCompleted) {
                // The connection has ended. If an earlier step failed it has said why; if the link
                // dropped between steps nothing has, so the reason is written down here.
                note("  not downloaded; the connection ended: ${code(session.terminated.await())}")
                skip(DOWNLOAD, Message(R.string.detail_no_session))
            } else step(DOWNLOAD) {
                val directory = withContext(Dispatchers.IO) {
                    dataDirectory().apply {
                        deleteRecursively() // never mix rounds from different runs
                        mkdirs()
                    }
                }
                var totalBytes = 0L
                var succeeded = 0
                var machineFault = false
                for (spool in SpoolName.entries) {
                    try {
                        progress(DOWNLOAD, Message(R.string.detail_downloading, friendly(spool), totalBytes / 1024))
                        val days = if (spool == SpoolName.SUMMARY) SUMMARY_DAYS else DETAIL_DAYS
                        var address: SpoolAddress = SpoolAddress.FromMachineTime(machineNow - days * DAY_MS)
                        var rounds = 0
                        var bytes = 0L
                        val started = System.currentTimeMillis()
                        while (rounds < MAX_ROUNDS) {
                            val round = session.pullSpoolRound(spool, address)
                            withContext(Dispatchers.IO) { File(directory, "${spool.name.lowercase()}-$rounds.bin").writeBytes(round.data) }
                            note("  ${spool.wireName} round $rounds: ${round.data.size} bytes sha256 ${round.sha256?.let { Hex.encode(it).take(16) } ?: "-"} more=${round.next != null}")
                            rounds++
                            bytes += round.data.size
                            progress(DOWNLOAD, Message(R.string.detail_downloading, friendly(spool), (totalBytes + bytes) / 1024))
                            address = SpoolAddress.Continuation(round.next ?: break)
                            // Reaching the check's own limit is not a fault of anything: reading works.
                            if (rounds == MAX_ROUNDS) note("  ${spool.wireName}: stopped at the check's limit of $MAX_ROUNDS rounds; the machine has more")
                        }
                        totalBytes += bytes
                        succeeded++
                        note("  ${spool.wireName}: $bytes bytes in $rounds round(s), ${System.currentTimeMillis() - started} ms")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // One log failing must not hide whether the others work.
                        // With Bluetooth off there is nothing more to learn log by log; the step says so.
                        if (phoneBluetoothOff(e)) throw e
                        note("  ${spool.wireName}: FAILED ${code(e)}")
                        if (!isPhoneSide(e)) machineFault = true
                        if (session.terminated.isCompleted) {
                            note("  the connection ended; later logs were not attempted")
                            break
                        }
                    }
                }
                val logs = SpoolName.entries.size
                // If every failure was this phone failing to store what it received, the machine is not to blame.
                if (succeeded < logs) throw CheckFailure(Message(R.string.detail_download_partial, succeeded, logs), onPhone = !machineFault)
                Message(R.string.detail_download_ok, totalBytes / 1024)
            }
            note("counters ${session.counters.snapshot()} discarded=${session.discardedBytes} crcFailures=${session.payloadCrcFailures} abandonedFrames=${session.abandonedFrames}")
        } finally {
            client?.close()
            link?.close()
        }
    }

    private suspend fun askPasskey(): String {
        val request = CompletableDeferred<String>()
        _passkeyRequest.value = request
        try {
            return request.await()
        } finally {
            _passkeyRequest.value = null
        }
    }

    private suspend fun choose(found: List<Sighting>): Int {
        // Shown on the user's own screen only; the name helps tell two machines apart.
        val options = found.map {
            Message(R.string.choice_option, it.advertisement.localName?.let(::printable) ?: "AirSense 11", signal(it.rssi))
        }
        val request = MachineChoice(options, CompletableDeferred())
        _machineChoice.value = request
        try {
            return request.answer.await().coerceIn(found.indices)
        } finally {
            _machineChoice.value = null
        }
    }

    private fun describe(s: Sighting): String =
        "rssi ${s.rssi} name=${if (s.advertisement.localName != null) "present" else "absent"} " +
            // Not the list itself: a payload can dress anything up as a list of service numbers.
            "truncated=${s.advertisement.truncated} machineService=${MACHINE_SERVICE in s.advertisement.serviceUuids16} " +
            "serviceCount=${s.advertisement.serviceUuids16.size} " +
            "platformName=${s.platformSawName} platformService=${s.platformSawService} " +
            "connectable=${s.connectable} legacy=${s.legacy}" +
            (if (Build.VERSION.SDK_INT >= 35) " addressType=${s.device.addressType}" else "") +
            " adv=${s.redactedHex.ifEmpty { "(none)" }}"

    /** Runs one step. Returns true if it passed, false if it failed or only made an observation. */
    private suspend fun step(id: String, body: suspend () -> Message): Boolean {
        set(id, StepState.RUNNING, null)
        return try {
            set(id, StepState.PASSED, body())
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: As11PairingCancelledException) {
            // The user backed out of typing the code. This is not a failure.
            note("  ${code(e)}")
            set(id, StepState.CANCELLED, explain(e))
            false
        } catch (e: Observation) {
            set(id, StepState.NOTED, e.detail)
            // An observation is not a failure: later steps still run.
            true
        } catch (e: Exception) {
            note("  ${code(e)}")
            if (phoneBluetoothOff(e)) {
                set(id, StepState.FAILED, Message(R.string.detail_bluetooth_off), onPhone = true)
            } else {
                set(id, StepState.FAILED, explain(e), onPhone = isPhoneSide(e))
            }
            false
        }
    }

    /**
     * Whether Bluetooth being off on this phone explains a failure. The radio's own
     * report of a lost link can arrive before the notice that Bluetooth was switched
     * off, so a link failure with Bluetooth now off is counted too.
     */
    private fun phoneBluetoothOff(e: Throwable): Boolean {
        if (bluetoothOff(e)) return true
        val linkFailure = generateSequence(e) { it.cause?.takeIf { cause -> cause !== it } }
            .take(MAX_CAUSE_DEPTH)
            .any { it is As11TransportException || it is As11TimeoutException }
        return linkFailure && !scanner.bluetoothEnabled
    }

    /** Whether a failure lies with this phone and not with the machine or the link to it. */
    private fun isPhoneSide(e: Throwable): Boolean = bluetoothOff(e) || when (e) {
        is CheckFailure -> e.onPhone
        is As11NonceStoreException, is As11DefectException -> true
        is As11Exception -> false
        else -> true // storage, permissions, Health Connect, anything unexpected in the app itself
    }

    private fun skip(id: String, why: Message) = set(id, StepState.SKIPPED, why)

    private fun skipAll(ids: List<String>, why: Message) = ids.forEach { skip(it, why) }

    private fun fail(id: String, why: Message) = set(id, StepState.FAILED, why, onPhone = true)

    /** What a long step is doing right now. Shown on screen, not written to the report. */
    private fun progress(id: String, detail: Message) {
        _steps.update { list -> list.map { if (it.id == id && it.state == StepState.RUNNING) it.copy(detail = detail) else it } }
    }

    private fun closeOpenSteps(running: StepState, why: Message) {
        // A run that ends between two steps ended at the next one; without this the
        // screen would show nothing but blank rows.
        var marked = _steps.value.any { it.state == StepState.RUNNING }
        for (step in _steps.value) {
            when (step.state) {
                StepState.RUNNING -> set(step.id, running, why)
                StepState.PENDING -> if (marked) {
                    set(step.id, StepState.SKIPPED, null)
                } else {
                    marked = true
                    set(step.id, running, why)
                }
                else -> Unit
            }
        }
    }

    private fun set(id: String, state: StepState, detail: Message?, onPhone: Boolean = false) {
        _steps.update { list -> list.map { if (it.id == id) it.copy(state = state, detail = detail, onPhone = onPhone) else it } }
        if (state != StepState.RUNNING) note("[$state] $id: ${detail?.resolve(english).orEmpty()}")
    }

    private fun note(line: String) {
        synchronized(report) { report.appendLine(line) }
    }

    private fun dataDirectory() = File(context.filesDir, "check")

    internal companion object {
        const val HEALTH = CheckSteps.HEALTH
        const val SCAN = CheckSteps.SCAN
        const val PAIR = CheckSteps.PAIR
        const val SESSION = CheckSteps.SESSION
        const val IDENTITY = CheckSteps.IDENTITY
        const val CLOCK = CheckSteps.CLOCK
        const val DOWNLOAD = CheckSteps.DOWNLOAD

        const val SCAN_MS = 8_000L

        /** The machine's 16-bit service number, and how many sightings a report lists. */
        const val MACHINE_SERVICE = 0xFD56
        const val MAX_LISTED = 8

        /** Shorter: nothing has to be chosen, and the connection attempt that follows is the real test. */
        const val PAIRED_SCAN_MS = 5_000L
        const val FILTER_SCAN_MS = 4_000L
        const val CONNECT_MS = 20_000L
        const val UNSEEN_CONNECT_MS = 6_000L
        const val RECONNECT_PAUSE_MS = 6_000L
        const val HEALTH_ANSWER_MS = 10_000L
        const val OVERALL_LIMIT_MS = 20 * 60_000L
        const val DAY_MS = 86_400_000L
        const val SUMMARY_DAYS = 30
        const val DETAIL_DAYS = 3
        const val MAX_ROUNDS = 50

        /** Identity values that are safe to write to the report. The serial is never among them. */
        val REPORTED_IDENTITY = listOf("ProductName", "ProductCode", "ApplicationIdentifier", "DataModelVersionIdentifier")

        fun template() = listOf(HEALTH, SCAN, PAIR, SESSION, IDENTITY, CLOCK, DOWNLOAD).map { Step(it) }

        fun friendly(spool: SpoolName): Message = Message(
            when (spool) {
                SpoolName.USAGE_EVENTS -> R.string.log_usage_events
                SpoolName.SUMMARY -> R.string.log_summaries
                SpoolName.RESPIRATORY_EVENTS -> R.string.log_breathing_events
                SpoolName.ONE_MINUTE -> R.string.log_per_minute
            },
        )

        fun signal(rssi: Int): Message = Message(
            when {
                rssi >= -60 -> R.string.signal_strong
                rssi >= -75 -> R.string.signal_good
                else -> R.string.signal_weak
            },
        )

        /** Device-supplied text made safe for one line of the report or the screen. */
        fun printable(text: String, limit: Int = 60): String =
            text.take(limit).map { if (it in ' '..'~') it else '?' }.joinToString("")

        /**
         * What went wrong, in words fit for the screen. Written out case by case:
         * class names are meaningless in a release build, where they are shortened.
         */
        fun explain(e: Throwable): Message = if (bluetoothOff(e)) Message(R.string.detail_bluetooth_off) else when (e) {
            is CheckFailure -> e.detail
            is As11Exception -> when (e) {
                is As11TransportException -> Message(R.string.error_connection)
                is As11TimeoutException -> Message(R.string.error_no_answer)
                is As11AuthException.PasskeyRejected -> Message(R.string.error_code_rejected)
                is As11AuthException.PasskeyInvalid -> Message(R.string.error_code_invalid)
                is As11AuthException.MachineUnauthenticated -> Message(R.string.error_not_the_machine)
                is As11AuthException.PairingUnknown -> Message(R.string.error_pairing_unknown)
                is As11AuthException.SessionProofRejected -> Message(R.string.error_pairing_rejected)
                is As11AuthException.SessionUnproven -> Message(R.string.error_session_unconfirmed)
                is As11AuthException.NonceReplayed -> Message(R.string.error_replayed)
                is As11PairingCancelledException -> Message(R.string.error_pairing_cancelled)
                is As11RpcException -> Message(R.string.error_refused, e.code ?: 0)
                is As11SpoolIntegrityException -> Message(R.string.error_download_damaged)
                is As11SpoolUnverifiedException -> Message(R.string.error_download_unverified)
                is As11SpoolStalledException -> Message(R.string.error_download_stalled)
                is As11SpoolUnavailableException -> Message(R.string.error_data_unavailable)
                is As11ProtocolException -> Message(R.string.error_unexpected_answer)
                is As11NonceStoreException -> Message(R.string.error_storage)
                is As11DefectException -> Message(R.string.error_internal)
            }
            is HealthConnectUnresponsiveException -> Message(R.string.error_hc_unresponsive)
            else -> healthConnectError(e)?.let {
                // Its "not permitted" is the one fault the user can put right, so it keeps its own advice.
                if (it.errorCode == android.health.connect.HealthConnectException.ERROR_SECURITY) {
                    Message(R.string.detail_hc_no_permission)
                } else {
                    Message(R.string.error_hc_reported, it.errorCode)
                }
            } ?: when (e) {
                is IOException -> Message(R.string.error_storage)
                is SecurityException -> Message(R.string.error_permission)
                else -> Message(R.string.error_internal)
            }
        }

        /** Health Connect reports its own faults wrapped inside ordinary exceptions; this digs one out. */
        fun healthConnectError(e: Throwable): android.health.connect.HealthConnectException? =
            generateSequence(e) { it.cause?.takeIf { cause -> cause !== it } }
                .take(MAX_CAUSE_DEPTH)
                .filterIsInstance<android.health.connect.HealthConnectException>()
                .firstOrNull()

        /**
         * Whether Bluetooth being off on this phone is what ended things. A link that
         * fails for that reason reaches the check wrapped in the protocol layer's own
         * "link failed", so the causes are searched too.
         */
        fun bluetoothOff(e: Throwable): Boolean =
            generateSequence(e) { it.cause?.takeIf { cause -> cause !== it } }.take(MAX_CAUSE_DEPTH).any { it is BluetoothOffException }

        const val MAX_CAUSE_DEPTH = 8

        /**
         * A stable label for the report; survives release builds.
         *
         * @param messages false to leave out every message, keeping kinds, codes and places only.
         *   Messages here are made printable and never hold a key or a full serial, but some quote
         *   a short value from the machine or from Health Connect; a label kept on the phone
         *   between runs does without them.
         */
        fun code(e: Throwable, messages: Boolean = true): String = when (e) {
            is CheckFailure -> "check"
            is Observation -> "observation"
            is As11Exception -> {
                val kind = when (e) {
                    is As11TransportException -> "transport"
                    is As11TimeoutException -> "timeout"
                    is As11AuthException.PasskeyRejected -> "auth.passkey-rejected"
                    is As11AuthException.PasskeyInvalid -> "auth.passkey-invalid"
                    is As11AuthException.MachineUnauthenticated -> "auth.machine-unauthenticated"
                    is As11AuthException.PairingUnknown -> "auth.pairing-unknown"
                    is As11AuthException.SessionProofRejected -> "auth.session-proof-rejected"
                    is As11AuthException.SessionUnproven -> "auth.session-unproven"
                    is As11AuthException.NonceReplayed -> "auth.nonce-replayed"
                    is As11PairingCancelledException -> "pairing-cancelled"
                    is As11RpcException -> "rpc"
                    is As11SpoolIntegrityException -> "spool.integrity"
                    is As11SpoolUnverifiedException -> "spool.unverified"
                    is As11SpoolStalledException -> "spool.stalled"
                    is As11SpoolUnavailableException -> "spool.unavailable"
                    is As11ProtocolException -> "protocol"
                    is As11NonceStoreException -> "nonce-store"
                    is As11DefectException -> "defect"
                }
                // A few messages quote a short value from the machine, so they are made printable.
                "$kind [${e.recovery}]" + (if (messages) " ${printable(e.message.orEmpty())}" else "") + (e.cause?.let { " <- ${code(it, messages)}" } ?: "")
            }
            is HealthConnectUnresponsiveException -> "health-connect.unresponsive"
            else -> healthConnectError(e)?.let {
                "health-connect code ${it.errorCode}" + if (messages) " ${printable(it.message.orEmpty(), limit = 200)}" else ""
            } ?: run {
                // Platform class names are never shortened by the release build, so they are
                // safe to print. Others would be meaningless; the frames below can be mapped back.
                val name = e.javaClass.name
                val type = if (PLATFORM_PREFIXES.any(name::startsWith)) name else "app"
                val frames = e.stackTrace.take(3).joinToString(" < ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
                // Only storage and permission errors have their message printed. Others, such
                // as a number or date that failed to parse, quote what they were given, and
                // that can be text from the machine.
                val safe = e is IOException || e is SecurityException || e is java.security.GeneralSecurityException ||
                    e is java.security.ProviderException || e is android.security.KeyStoreException || e is android.os.RemoteException
                val message = if (safe && messages) ": ${printable(e.message.orEmpty(), limit = 200)}" else ""
                "unexpected $type$message at $frames" + (e.cause?.takeIf { it !== e }?.let { " <- ${code(it, messages)}" } ?: "")
            }
        }

        val PLATFORM_PREFIXES = listOf("java.", "javax.", "android.", "kotlin.", "kotlinx.")
    }
}
