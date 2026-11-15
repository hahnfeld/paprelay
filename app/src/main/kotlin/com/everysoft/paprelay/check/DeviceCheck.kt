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
import com.everysoft.paprelay.ble.GattLink
import com.everysoft.paprelay.ble.MachineScanner
import com.everysoft.paprelay.ble.Sighting
import com.everysoft.paprelay.hc.HealthConnectProbe
import com.everysoft.paprelay.hc.HealthConnectUnresponsiveException
import com.everysoft.paprelay.pairing.FileNonceRegistry
import com.everysoft.paprelay.pairing.Pairing
import com.everysoft.paprelay.pairing.PairingState
import com.everysoft.paprelay.pairing.PairingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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

    fun reportText(): String = synchronized(report) { report.toString() }

    /**
     * Starts a run; ignored if one is in progress. It outlives the screen that
     * started it and stops on its own after [OVERALL_LIMIT_MS] at most.
     *
     * @param full false to skip scanning and pairing and only prove that the
     *   stored pairing still opens a session (used after toggling Bluetooth or
     *   rebooting, to learn whether the machine still recognises the phone).
     */
    @Synchronized
    fun start(full: Boolean) {
        // A cancelled run is still unwinding until it completes; wait for that.
        if (job?.isCompleted == false) return
        _running.value = true
        job = scope.launch {
            try {
                _steps.value = template()
                synchronized(report) {
                    report.setLength(0)
                    report.appendLine("PAPRelay device check  ${Instant.now()}")
                    report.appendLine("app ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})  android ${Build.VERSION.RELEASE} sdk ${Build.VERSION.SDK_INT}")
                    report.appendLine("phone ${Build.MANUFACTURER} ${Build.MODEL}  mode ${if (full) "full" else "reconnect"}")
                }
                val finished = withTimeoutOrNull(OVERALL_LIMIT_MS) { execute(full) }
                if (finished == null) closeOpenSteps(StepState.FAILED, Message(R.string.detail_took_too_long))
            } catch (e: CancellationException) {
                closeOpenSteps(StepState.CANCELLED, Message(R.string.detail_stopped))
                throw e
            } finally {
                _passkeyRequest.value = null
                _machineChoice.value = null
                _running.value = false
            }
        }
    }

    @Synchronized
    fun cancel() {
        job?.cancel()
    }

    /**
     * Deletes the therapy data the check downloaded and forgets the last run's
     * outcome, so nothing on screen describes a machine that is no longer paired.
     * Called when the machine is forgotten; does nothing to a run in progress.
     */
    suspend fun clearData() {
        withContext(Dispatchers.IO) { dataDirectory().deleteRecursively() }
        synchronized(this) {
            if (job?.isCompleted != false) {
                _steps.value = template()
                synchronized(report) { report.setLength(0) }
            }
        }
    }

    private suspend fun execute(full: Boolean) {
        step(HEALTH) {
            if (!healthConnect.available) throw CheckFailure(Message(R.string.detail_hc_unavailable), onPhone = true)
            val allowed = withTimeoutOrNull(HEALTH_ANSWER_MS) { healthConnect.hasPermission() }
                ?: throw HealthConnectUnresponsiveException()
            if (!allowed) throw CheckFailure(Message(R.string.detail_hc_no_permission), onPhone = true)
            val result = healthConnect.writeAndRemove()
            note("  wrote ${result.written}, removed ${result.removed}")
            if (!result.removed) throw CheckFailure(Message(R.string.detail_hc_value_left), onPhone = true)
            if (result.written != 1) throw CheckFailure(Message(R.string.detail_hc_not_written), onPhone = true)
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
        if (full && state !is PairingState.Paired) {
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

        var target: BluetoothDevice? = null
        // True once a full check has scanned for an already-paired machine; `target` then says whether it was seen.
        var scannedForPaired = false
        if (full) {
            val alreadyPaired = (state as? PairingState.Paired)?.pairing
            val scanned = step(SCAN) {
                if (!scanner.bluetoothEnabled) throw CheckFailure(Message(R.string.detail_bluetooth_off), onPhone = true)
                val found = scanner.scan(SCAN_MS, platformFilter = false)
                found.forEachIndexed { index, sighting -> note("  machine ${index + 1}: ${describe(sighting)}") }
                // Whether the system's own scan filter can see this machine decides how
                // background discovery has to work. Recorded in the report only.
                val filtered = try {
                    scanner.scan(FILTER_SCAN_MS, platformFilter = true).size
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                note("  system scan filter: ${filtered?.let { "saw $it machine(s)" } ?: "could not run"}")

                if (alreadyPaired != null) {
                    // Nothing to choose: the question is only whether the paired machine is there.
                    scannedForPaired = true
                    val mine = found.firstOrNull { it.device.address.equals(alreadyPaired.address, ignoreCase = true) }
                    if (mine == null) {
                        // Tells "out of range" apart from "its address changed".
                        note("  paired machine not seen; ${found.size} other AirSense-like device(s) in range")
                        throw Observation(Message(R.string.detail_paired_not_seen))
                    }
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
        } else {
            skip(SCAN, Message(R.string.detail_not_in_reconnect))
        }

        if (full && state !is PairingState.Paired) {
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
            skip(PAIR, Message(if (state is PairingState.Paired) R.string.detail_already_paired else R.string.detail_not_in_reconnect))
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
                // Prefer a device seen advertising: it carries the right address type. A full
                // check has just scanned, so that answer is used rather than asking again.
                val seen: BluetoothDevice? = when {
                    target != null -> target
                    scannedForPaired -> null
                    else -> scanner.scan(SCAN_MS, platformFilter = false, stopAtAddress = paired.address)
                        .firstOrNull { it.device.address.equals(paired.address, ignoreCase = true) }?.device
                }
                note("  paired machine ${if (seen != null) "seen advertising" else "not seen advertising; trying a direct connection"}")
                val device = seen ?: scanner.deviceByAddress(paired.address)
                    ?: throw CheckFailure(Message(R.string.detail_bluetooth_off), onPhone = true)
                val opened = try {
                    // A machine that is not advertising cannot be connected to, so do not wait long for it.
                    GattLink.open(context, device, if (seen != null) CONNECT_MS else UNSEEN_CONNECT_MS)
                } catch (e: As11TransportException) {
                    if (seen != null) throw e
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

            step(DOWNLOAD) {
                val directory = withContext(Dispatchers.IO) {
                    dataDirectory().apply {
                        deleteRecursively() // never mix rounds from different runs
                        mkdirs()
                    }
                }
                var totalBytes = 0L
                var succeeded = 0
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
                            if (rounds == MAX_ROUNDS) throw IllegalStateException("more rounds than this check reads in one go")
                        }
                        totalBytes += bytes
                        succeeded++
                        note("  ${spool.wireName}: $bytes bytes in $rounds round(s), ${System.currentTimeMillis() - started} ms")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // One log failing must not hide whether the others work.
                        note("  ${spool.wireName}: FAILED ${code(e)}")
                        if (session.terminated.isCompleted) {
                            note("  the connection ended; later logs were not attempted")
                            break
                        }
                    }
                }
                val logs = SpoolName.entries.size
                if (succeeded < logs) throw CheckFailure(Message(R.string.detail_download_partial, succeeded, logs))
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
            "truncated=${s.advertisement.truncated} uuids=${s.advertisement.serviceUuids16.map { it.toString(16) }} " +
            "platformName=${s.platformSawName} platformService=${s.platformSawService}" +
            (if (Build.VERSION.SDK_INT >= 35) " addressType=${s.device.addressType}" else "") +
            " adv=${s.redactedHex}"

    /** Runs one step. Returns true if it passed, false if it failed or only made an observation. */
    private suspend fun step(id: String, body: suspend () -> Message): Boolean {
        set(id, StepState.RUNNING, null)
        return try {
            set(id, StepState.PASSED, body())
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Observation) {
            set(id, StepState.NOTED, e.detail)
            // An observation is not a failure: later steps still run.
            true
        } catch (e: Exception) {
            note("  ${code(e)}")
            set(id, StepState.FAILED, explain(e), onPhone = isPhoneSide(e))
            false
        }
    }

    /** Whether a failure lies with this phone and not with the machine or the link to it. */
    private fun isPhoneSide(e: Throwable): Boolean = when (e) {
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
        for (step in _steps.value) {
            when (step.state) {
                StepState.RUNNING -> set(step.id, running, why)
                StepState.PENDING -> set(step.id, StepState.SKIPPED, null)
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

    private companion object {
        const val HEALTH = CheckSteps.HEALTH
        const val SCAN = CheckSteps.SCAN
        const val PAIR = CheckSteps.PAIR
        const val SESSION = CheckSteps.SESSION
        const val IDENTITY = CheckSteps.IDENTITY
        const val CLOCK = CheckSteps.CLOCK
        const val DOWNLOAD = CheckSteps.DOWNLOAD

        const val SCAN_MS = 8_000L
        const val FILTER_SCAN_MS = 5_000L
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
        fun explain(e: Throwable): Message = when (e) {
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
            is IOException -> Message(R.string.error_storage)
            is SecurityException -> Message(R.string.error_permission)
            else -> Message(R.string.error_internal)
        }

        /** A stable label for the report; survives release builds. */
        fun code(e: Throwable): String = when (e) {
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
                "$kind [${e.recovery}] ${printable(e.message.orEmpty())}" + (e.cause?.let { " <- ${code(it)}" } ?: "")
            }
            is HealthConnectUnresponsiveException -> "health-connect.unresponsive"
            else -> {
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
                val message = if (safe) ": ${printable(e.message.orEmpty(), limit = 200)}" else ""
                "unexpected $type$message at $frames" + (e.cause?.takeIf { it !== e }?.let { " <- ${code(it)}" } ?: "")
            }
        }

        val PLATFORM_PREFIXES = listOf("java.", "javax.", "android.", "kotlin.", "kotlinx.")
    }
}
