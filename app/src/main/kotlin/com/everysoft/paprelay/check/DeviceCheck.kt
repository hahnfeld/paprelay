// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.check

import android.bluetooth.BluetoothDevice
import android.content.Context
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

enum class StepState { PENDING, RUNNING, PASSED, FAILED, SKIPPED, NOTED }

data class Step(val id: String, val title: String, val state: StepState = StepState.PENDING, val detail: String = "")

/** More than one machine is in range and the user has to say which is theirs. */
class MachineChoice(val options: List<String>, val answer: CompletableDeferred<Int>)

/** A check that failed for a reason this class can state plainly. */
private class CheckFailure(message: String) : Exception(message)

/**
 * The first-contact check: establishes, on the user's own phone and machine,
 * the facts the rest of the app depends on. Each step records what it saw so
 * the outcome can be read from the exported report alone.
 *
 * It reads from the machine and never changes anything on it. Its one write
 * anywhere is a Health Connect test value that it removes again.
 *
 * The report never contains the pairing key, the code, a full serial number, a
 * Bluetooth address or an advertised name.
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
        if (job?.isActive == true) return
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
                if (finished == null) closeOpenSteps("Stopped: the check took too long")
            } catch (e: CancellationException) {
                closeOpenSteps("Cancelled")
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

    /** Deletes the therapy data the check downloaded. Called when the machine is forgotten. */
    suspend fun clearData() = withContext(Dispatchers.IO) {
        dataDirectory().deleteRecursively()
        Unit
    }

    private suspend fun execute(full: Boolean) {
        step(HEALTH) {
            if (!healthConnect.available) throw CheckFailure("Health Connect is not available on this phone")
            if (!healthConnect.hasPermission()) throw CheckFailure("Permission to write respiratory rate was not granted")
            val result = healthConnect.writeAndRemove()
            if (!result.removed) {
                throw CheckFailure("A test value was written but could not be removed. Delete it in Health Connect, under PAPRelay")
            }
            "Test value written and removed"
        }

        var state = store.current()
        var target: BluetoothDevice? = null
        if (full) {
            step(SCAN) {
                if (!scanner.bluetoothEnabled) throw CheckFailure("Bluetooth is off")
                val found = scanner.scan(SCAN_MS, platformFilter = false)
                found.forEachIndexed { index, sighting -> note("  machine ${index + 1}: ${describe(sighting)}") }
                target = when (found.size) {
                    0 -> throw CheckFailure("No machine found. Put it in pairing mode and make sure it is not paired with myAir")
                    1 -> found[0].device
                    else -> found[choose(found)].device
                }
                if (found.size == 1) "1 machine found (${signal(found[0].rssi)})" else "${found.size} machines found; using the one you chose"
            }
            step(FILTERED_SCAN, noteOnly = true) {
                val found = scanner.scan(SCAN_MS, platformFilter = true)
                if (found.isEmpty()) "Not usable with this machine; PAPRelay scans without it" else "Works"
            }
        } else {
            skip(SCAN, "Not part of a reconnect check")
            skip(FILTERED_SCAN, "Not part of a reconnect check")
        }

        if (full && state !is PairingState.Paired) {
            val device = target
            step(PAIR) {
                val machine = device ?: throw CheckFailure("No machine to pair with")
                // Prove the pairing can be stored before spending one on the machine.
                try {
                    store.selfTest()
                } catch (e: IOException) {
                    throw CheckFailure("Secure storage is not working on this phone, so pairing was not attempted")
                }
                GattLink.open(context, machine, CONNECT_MS).use { link ->
                    note("  mtu ${link.summary.mtu}, connect attempts ${link.summary.connectAttempts}")
                    link.summary.services.forEach { note("  service $it") }
                    As11Client(link, scope).use { client ->
                        val credentials = client.pair { askPasskey() }
                        try {
                            store.save(Pairing(credentials, machine.address, null, System.currentTimeMillis()))
                        } catch (e: IOException) {
                            throw CheckFailure(
                                "The machine accepted the code but the pairing could not be saved. " +
                                    "Put the machine back in pairing mode and run the check again",
                            )
                        }
                    }
                }
                "Paired"
            }
            state = store.current()
            // The machine needs a few seconds before it will accept a new connection.
            if (state is PairingState.Paired) delay(RECONNECT_PAUSE_MS)
        } else {
            skip(PAIR, if (state is PairingState.Paired) "Already paired" else "Not part of a reconnect check")
        }

        val paired = (state as? PairingState.Paired)?.pairing
        if (paired == null) {
            val why = if (state is PairingState.Unreadable) "The stored pairing can no longer be read; pair again" else "Not paired"
            listOf(SESSION, IDENTITY, CLOCK, DOWNLOAD).forEach { skip(it, why) }
            return
        }

        var link: GattLink? = null
        var client: As11Client? = null
        try {
            step(SESSION) {
                // Prefer a device seen advertising: it carries the right address type.
                val sighting = scanner.scan(SCAN_MS, platformFilter = false, stopAtAddress = paired.address)
                    .firstOrNull { it.device.address.equals(paired.address, ignoreCase = true) }
                note("  paired machine ${if (sighting != null) "seen advertising, ${signal(sighting.rssi)}" else "not seen advertising; trying a direct connection"}")
                val device = sighting?.device ?: scanner.deviceByAddress(paired.address) ?: throw CheckFailure("Bluetooth is off")
                val opened = GattLink.open(context, device, CONNECT_MS)
                link = opened
                note("  mtu ${opened.summary.mtu}, connect attempts ${opened.summary.connectAttempts}")
                val created = As11Client(opened, scope)
                client = created
                created.startSession(paired.credentials, FileNonceRegistry(File(context.noBackupFilesDir, "nonces.txt")))
                "Encrypted session open and confirmed by the machine"
            }
            val session = client?.takeIf { it.isSecure }
            if (session == null) {
                listOf(IDENTITY, CLOCK, DOWNLOAD).forEach { skip(it, "No session") }
                return
            }

            var rightMachine = false
            step(IDENTITY) {
                val read = session.get(listOf(GetItem.SERIAL_NUMBER, GetItem.PRODUCT_NAME, GetItem.PRODUCT_CODE, GetItem.APPLICATION_IDENTIFIER, GetItem.DATA_MODEL_VERSION_IDENTIFIER))
                val serial = (read.values["SerialNumber"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw CheckFailure("The machine did not report a serial number")
                for (key in REPORTED_IDENTITY) {
                    (read.values[key] as? JsonPrimitive)?.let { note("  $key = ${printable(it.content)}") }
                }
                if (read.unavailable.isNotEmpty()) note("  unavailable ${read.unavailable.map(::printable)}")
                note("  version sections ${session.getVersion().keys.map(::printable)}")
                if (paired.serialNumber == null) {
                    store.save(Pairing(paired.credentials, paired.address, serial, paired.pairedAtMs))
                } else if (paired.serialNumber != serial) {
                    throw CheckFailure("This is not the machine that was paired")
                }
                rightMachine = true
                val name = (read.values["ProductName"] as? JsonPrimitive)?.content?.let(::printable) ?: "Machine"
                "$name, serial ending ${serial.takeLast(4)}"
            }
            if (!rightMachine) {
                listOf(CLOCK, DOWNLOAD).forEach { skip(it, "Machine identity not confirmed") }
                return
            }

            var machineNow = System.currentTimeMillis()
            step(CLOCK) {
                val readings = (1..3).map { session.getDateTime() }
                readings.forEach { note("  machine ${Instant.ofEpochMilli(it.machineEpochMs)} round trip ${it.roundTripMs} ms offset ${it.offsetMs} ms") }
                val best = readings.minBy { it.roundTripMs }
                machineNow = best.machineEpochMs
                val seconds = best.offsetMs / 1000.0
                val size = String.format(Locale.US, "%.1f", kotlin.math.abs(seconds))
                when {
                    kotlin.math.abs(seconds) < 0.5 -> "Machine clock agrees with this phone"
                    seconds > 0 -> "Machine clock is $size s behind this phone"
                    else -> "Machine clock is $size s ahead of this phone"
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
                var failed = 0
                for (spool in SpoolName.entries) {
                    try {
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
                            address = SpoolAddress.Continuation(round.next ?: break)
                        }
                        totalBytes += bytes
                        note("  ${spool.wireName}: $bytes bytes in $rounds round(s), ${System.currentTimeMillis() - started} ms")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // One log failing must not hide whether the others work.
                        failed++
                        note("  ${spool.wireName}: FAILED ${code(e)}: ${explain(e)}")
                        if (session.terminated.isCompleted) break
                    }
                }
                val logs = SpoolName.entries.size
                if (failed > 0) throw CheckFailure("${logs - failed} of $logs logs downloaded; see the report for the rest")
                "$logs logs downloaded and verified (${totalBytes / 1024} kB)"
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
        val options = found.map { "${it.advertisement.localName?.let(::printable) ?: "AirSense 11"} · ${signal(it.rssi)}" }
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

    private suspend fun step(id: String, noteOnly: Boolean = false, body: suspend () -> String) {
        set(id, StepState.RUNNING, "")
        try {
            val detail = body()
            set(id, if (noteOnly) StepState.NOTED else StepState.PASSED, detail)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            note("  ${code(e)}")
            set(id, StepState.FAILED, explain(e))
        }
    }

    private fun skip(id: String, why: String) = set(id, StepState.SKIPPED, why)

    private fun closeOpenSteps(why: String) {
        for (step in _steps.value) {
            when (step.state) {
                StepState.RUNNING -> set(step.id, StepState.FAILED, why)
                StepState.PENDING -> set(step.id, StepState.SKIPPED, why)
                else -> Unit
            }
        }
    }

    private fun set(id: String, state: StepState, detail: String) {
        _steps.update { list -> list.map { if (it.id == id) it.copy(state = state, detail = detail) else it } }
        if (state != StepState.RUNNING) note("[$state] $id: $detail")
    }

    private fun note(line: String) {
        synchronized(report) { report.appendLine(line) }
    }

    private fun dataDirectory() = File(context.filesDir, "check")

    private companion object {
        const val HEALTH = "health"
        const val SCAN = "scan"
        const val FILTERED_SCAN = "filtered"
        const val PAIR = "pair"
        const val SESSION = "session"
        const val IDENTITY = "identity"
        const val CLOCK = "clock"
        const val DOWNLOAD = "download"

        const val SCAN_MS = 8_000L
        const val CONNECT_MS = 20_000L
        const val RECONNECT_PAUSE_MS = 6_000L
        const val OVERALL_LIMIT_MS = 20 * 60_000L
        const val DAY_MS = 86_400_000L
        const val SUMMARY_DAYS = 30
        const val DETAIL_DAYS = 3
        const val MAX_ROUNDS = 50

        /** Identity values that are safe to write to the report. The serial is never among them. */
        val REPORTED_IDENTITY = listOf("ProductName", "ProductCode", "ApplicationIdentifier", "DataModelVersionIdentifier")

        fun template() = listOf(
            Step(HEALTH, "Health Connect accepts writes"),
            Step(SCAN, "Machine is in range"),
            Step(FILTERED_SCAN, "System scan filter"),
            Step(PAIR, "Pairing"),
            Step(SESSION, "Encrypted session"),
            Step(IDENTITY, "Machine identity"),
            Step(CLOCK, "Machine clock"),
            Step(DOWNLOAD, "Stored data downloads and verifies"),
        )

        fun signal(rssi: Int): String = when {
            rssi >= -60 -> "strong signal"
            rssi >= -75 -> "good signal"
            else -> "weak signal"
        }

        /** Device-supplied text made safe for one line of the report or the screen. */
        fun printable(text: String): String = text.take(60).map { if (it in ' '..'~') it else '?' }.joinToString("")

        /**
         * What went wrong, in words fit for the screen. Written out case by case:
         * class names are meaningless in a release build, where they are shortened.
         */
        fun explain(e: Throwable): String = when (e) {
            is CheckFailure -> e.message ?: "Failed"
            is As11Exception -> when (e) {
                is As11TransportException -> "Connection problem: ${e.message}"
                is As11TimeoutException -> "The machine did not answer (${e.operation})"
                is As11AuthException.PasskeyRejected -> "The machine rejected the code. Put it back in pairing mode and try again"
                is As11AuthException.MachineUnauthenticated -> "This device could not prove it is the machine. Nothing was saved"
                is As11AuthException.PairingUnknown -> "The machine does not recognise this phone's pairing"
                is As11AuthException.SessionProofRejected -> "The machine rejected this phone's pairing"
                is As11AuthException.SessionUnproven -> "The machine did not confirm the session"
                is As11AuthException.NonceReplayed -> "The machine repeated an old session value. Nothing was read"
                is As11PairingCancelledException -> "Pairing was cancelled. The machine must be put back in pairing mode"
                is As11RpcException -> "The machine refused the request (${e.method}, code ${e.code})"
                is As11SpoolIntegrityException -> "A download arrived damaged"
                is As11SpoolUnverifiedException -> "The machine sent data without an integrity hash"
                is As11SpoolStalledException -> "A download stopped arriving"
                is As11SpoolUnavailableException -> "The machine says this data is not available"
                is As11ProtocolException -> "Unexpected answer from the machine"
                is As11NonceStoreException -> "Storage problem on this phone"
                is As11DefectException -> "Internal error. Please export the report"
            }
            is IOException -> "Storage problem on this phone"
            is SecurityException -> "A permission is missing"
            else -> "Unexpected error. Please export the report"
        }

        /** A stable label for the report; survives release builds. */
        fun code(e: Throwable): String = when (e) {
            is CheckFailure -> "check"
            is As11Exception -> {
                val kind = when (e) {
                    is As11TransportException -> "transport"
                    is As11TimeoutException -> "timeout"
                    is As11AuthException.PasskeyRejected -> "auth.passkey-rejected"
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
            is IOException -> "io"
            is SecurityException -> "permission"
            is IllegalStateException -> "state"
            is IllegalArgumentException -> "argument"
            else -> "other"
        }
    }
}
