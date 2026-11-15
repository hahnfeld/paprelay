// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.check

import android.content.Context
import android.os.Build
import com.everysoft.paprelay.BuildConfig
import com.everysoft.paprelay.as11.As11Client
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
import com.everysoft.paprelay.pairing.PairingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.Instant

enum class StepState { PENDING, RUNNING, PASSED, FAILED, SKIPPED }

data class Step(val id: String, val title: String, val state: StepState = StepState.PENDING, val detail: String = "")

/**
 * The first-contact check: establishes, on the user's own phone and machine,
 * the facts the rest of the app depends on. Each step records what it saw so
 * the outcome can be read from the exported report alone.
 *
 * It reads from the machine and never changes anything on it. Its one write
 * anywhere is a Health Connect test value that it removes again.
 */
class DeviceCheck(
    private val context: Context,
    private val scope: CoroutineScope,
    private val store: PairingStore,
    private val scanner: MachineScanner,
    private val healthConnect: HealthConnectProbe,
) {
    private val running = Mutex()
    private val report = StringBuilder()

    private val _steps = MutableStateFlow(template())
    val steps: StateFlow<List<Step>> = _steps

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /** Non-null while the machine is showing a code the user must type in. */
    private val _passkeyRequest = MutableStateFlow<CompletableDeferred<String>?>(null)
    val passkeyRequest: StateFlow<CompletableDeferred<String>?> = _passkeyRequest

    fun reportText(): String = synchronized(report) { report.toString() }

    /**
     * @param full false to skip scanning and pairing and only prove that the
     *   stored pairing still opens a session (used after toggling Bluetooth or
     *   rebooting, to learn whether the machine still recognises the phone).
     */
    suspend fun run(full: Boolean) {
        if (!running.tryLock()) return
        _busy.value = true
        try {
            _steps.value = template()
            synchronized(report) {
                report.setLength(0)
                report.appendLine("PAPRelay device check  ${Instant.now()}")
                report.appendLine("app ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})  android ${Build.VERSION.RELEASE} sdk ${Build.VERSION.SDK_INT}")
                report.appendLine("phone ${Build.MANUFACTURER} ${Build.MODEL}  mode ${if (full) "full" else "reconnect"}")
            }
            execute(full)
        } finally {
            _passkeyRequest.value = null
            _busy.value = false
            running.unlock()
        }
    }

    private suspend fun execute(full: Boolean) {
        step(HEALTH) {
            check(healthConnect.available) { "Health Connect is not available on this phone" }
            check(healthConnect.hasPermission()) { "permission to write respiratory rate was not granted" }
            healthConnect.writeAndRemove()
        }

        var target: Sighting? = null
        if (full) {
            step(SCAN) {
                check(scanner.bluetoothEnabled) { "Bluetooth is off" }
                val found = scanner.scan(SCAN_MS, platformFilter = false)
                for (s in found) {
                    note(
                        "  seen ..${s.device.address.takeLast(5)} rssi ${s.rssi} name=${s.advertisement.localName} " +
                            "truncated=${s.advertisement.truncated} uuids=${s.advertisement.serviceUuids16.map { it.toString(16) }} " +
                            "platformName=${s.platformName} platformService=${s.platformSawService}" +
                            addressType(s),
                    )
                    note("  raw ${s.rawHex}")
                }
                target = found.firstOrNull() ?: error("no AirSense 11 seen. Is it in pairing mode and unpaired from myAir?")
                "${found.size} machine(s) seen; strongest at ${found.first().rssi} dBm"
            }
            step(FILTERED_SCAN) {
                val found = scanner.scan(SCAN_MS, platformFilter = true)
                "system-filtered scan saw ${found.size} machine(s)" + if (found.isEmpty()) " (filtering is not usable)" else ""
            }
        } else {
            skip(SCAN, "not run in reconnect mode")
            skip(FILTERED_SCAN, "not run in reconnect mode")
        }

        var pairing = store.load()
        if (full && pairing == null) {
            val sighting = target
            step(PAIR) {
                val device = sighting?.device ?: error("no machine to pair with")
                val link = GattLink.open(context, device, CONNECT_MS)
                try {
                    note("  mtu ${link.summary.mtu}")
                    link.summary.services.forEach { note("  service $it") }
                    val client = As11Client(link, scope)
                    try {
                        val credentials = client.pair {
                            val request = CompletableDeferred<String>()
                            _passkeyRequest.value = request
                            try {
                                request.await()
                            } finally {
                                _passkeyRequest.value = null
                            }
                        }
                        val created = Pairing(credentials, device.address, null, System.currentTimeMillis())
                        store.save(created)
                        pairing = created
                        "paired; client id ends ..${credentials.clientId.takeLast(4)}"
                    } finally {
                        client.close()
                    }
                } finally {
                    link.close()
                }
            }
            // The machine needs a few seconds before it will accept a new connection.
            delay(RECONNECT_PAUSE_MS)
        } else {
            skip(PAIR, if (pairing != null) "already paired" else "not run in reconnect mode")
        }

        val paired = pairing
        if (paired == null) {
            listOf(SESSION, IDENTITY, CLOCK, DOWNLOAD).forEach { skip(it, "no pairing") }
            return
        }

        var link: GattLink? = null
        var client: As11Client? = null
        try {
            step(SESSION) {
                val device = scanner.device(paired.address) ?: error("Bluetooth is off")
                val opened = GattLink.open(context, device, CONNECT_MS)
                link = opened
                val created = As11Client(opened, scope)
                client = created
                created.startSession(paired.credentials, FileNonceRegistry(File(context.noBackupFilesDir, "nonces.txt")))
                "encrypted session open; mtu ${opened.summary.mtu}"
            }
            val session = client?.takeIf { it.isSecure }
            if (session == null) {
                listOf(IDENTITY, CLOCK, DOWNLOAD).forEach { skip(it, "no session") }
                return
            }

            step(IDENTITY) {
                val read = session.get(
                    listOf(
                        GetItem.SERIAL_NUMBER, GetItem.PRODUCT_NAME, GetItem.PRODUCT_CODE,
                        GetItem.APPLICATION_IDENTIFIER, GetItem.DATA_MODEL_VERSION_IDENTIFIER,
                    ),
                )
                val identity = read.values
                val serial = (identity["SerialNumber"] as? JsonPrimitive)?.content
                for ((key, value) in identity) {
                    note("  $key = ${if (key == "SerialNumber") "..${serial?.takeLast(4)}" else value.toString().take(120)}")
                }
                if (read.unavailable.isNotEmpty()) note("  unavailable ${read.unavailable}")
                if (serial == null) error("the machine did not report a serial number")
                if (paired.serialNumber == null) {
                    store.save(Pairing(paired.credentials, paired.address, serial, paired.pairedAtMs))
                } else if (paired.serialNumber != serial) {
                    error("this is not the machine that was paired")
                }
                note("  version ${session.getVersion().toString().take(600)}")
                "${(identity["ProductName"] as? JsonPrimitive)?.content ?: "machine"} ..${serial.takeLast(4)}"
            }

            var machineNow = System.currentTimeMillis()
            step(CLOCK) {
                val readings = (1..3).map { session.getDateTime() }
                readings.forEach { note("  machine ${Instant.ofEpochMilli(it.machineEpochMs)} round trip ${it.roundTripMs} ms offset ${it.offsetMs} ms") }
                val best = readings.minBy { it.roundTripMs }
                machineNow = best.machineEpochMs
                "machine clock is ${best.offsetMs / 1000.0} s behind the phone (round trip ${best.roundTripMs} ms)"
            }

            step(DOWNLOAD) {
                val directory = File(context.filesDir, "check").apply { mkdirs() }
                val summary = ArrayList<String>()
                for (spool in SpoolName.entries) {
                    val days = if (spool == SpoolName.SUMMARY) SUMMARY_DAYS else DETAIL_DAYS
                    var address: SpoolAddress = SpoolAddress.FromMachineTime(machineNow - days * DAY_MS)
                    var rounds = 0
                    var bytes = 0
                    val started = System.currentTimeMillis()
                    while (rounds < MAX_ROUNDS) {
                        val round = session.pullSpoolRound(spool, address)
                        File(directory, "${spool.name.lowercase()}-$rounds.bin").writeBytes(round.data)
                        note(
                            "  ${spool.wireName} round $rounds: ${round.data.size} bytes " +
                                "sha256 ${round.sha256?.let { Hex.encode(it).take(16) } ?: "-"} next=${round.next}",
                        )
                        rounds++
                        bytes += round.data.size
                        address = SpoolAddress.Continuation(round.next ?: break)
                    }
                    summary += "${spool.name.lowercase()} $bytes B/$rounds"
                    note("  ${spool.wireName}: $bytes bytes in $rounds round(s), ${System.currentTimeMillis() - started} ms")
                }
                summary.joinToString(", ")
            }
            note(
                "counters ${session.counters.snapshot()} discarded=${session.discardedBytes} " +
                    "crcFailures=${session.payloadCrcFailures} abandonedFrames=${session.abandonedFrames}",
            )
        } finally {
            client?.close()
            link?.close()
        }
    }

    private fun addressType(sighting: Sighting): String =
        if (Build.VERSION.SDK_INT >= 35) " addressType=${sighting.device.addressType}" else ""

    private suspend fun step(id: String, body: suspend () -> String) {
        set(id, StepState.RUNNING, "")
        try {
            val detail = body()
            set(id, StepState.PASSED, detail)
        } catch (e: CancellationException) {
            set(id, StepState.FAILED, "cancelled")
            throw e
        } catch (e: Exception) {
            set(id, StepState.FAILED, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun skip(id: String, why: String) = set(id, StepState.SKIPPED, why)

    private fun set(id: String, state: StepState, detail: String) {
        _steps.update { list -> list.map { if (it.id == id) it.copy(state = state, detail = detail) else it } }
        if (state != StepState.RUNNING) note("[$state] $id: $detail")
    }

    private fun note(line: String) {
        synchronized(report) { report.appendLine(line) }
    }

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
        const val DAY_MS = 86_400_000L
        const val SUMMARY_DAYS = 30
        const val DETAIL_DAYS = 3
        const val MAX_ROUNDS = 50

        fun template() = listOf(
            Step(HEALTH, "Health Connect accepts writes"),
            Step(SCAN, "Machine is visible"),
            Step(FILTERED_SCAN, "System scan filter works"),
            Step(PAIR, "Pairing"),
            Step(SESSION, "Encrypted session"),
            Step(IDENTITY, "Machine identity"),
            Step(CLOCK, "Machine clock"),
            Step(DOWNLOAD, "Stored data downloads and verifies"),
        )
    }
}
