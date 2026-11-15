// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.everysoft.paprelay.as11.Advertisement
import com.everysoft.paprelay.as11.As11TransportException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** One device seen during a scan. */
class Sighting(
    val device: BluetoothDevice,
    val rssi: Int,
    val advertisement: Advertisement,
    /** The payload with names and addresses blanked, for diagnosing parser disagreements. */
    val redactedHex: String,
    /** Whether Android's own parser recovered the vendor service from this payload. */
    val platformSawService: Boolean,
    val platformSawName: Boolean,
)

/** Finds AirSense 11 machines nearby. Callers must hold BLUETOOTH_SCAN. */
@SuppressLint("MissingPermission")
class MachineScanner(context: Context) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter

    val bluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    /**
     * A handle for connecting without having seen the machine advertise. Android
     * assumes a public address here; if the machine uses another kind, only a
     * device obtained from a scan will connect.
     */
    fun deviceByAddress(address: String): BluetoothDevice? = adapter?.takeIf { it.isEnabled }?.getRemoteDevice(address)

    /**
     * Scans for up to [durationMs] and returns the strongest sighting of each
     * device that looks like an AirSense 11.
     *
     * @param platformFilter when true, asks the system to filter on the vendor
     *   service. The machine's advertising payload is malformed, so this may
     *   find nothing where an unfiltered scan succeeds; comparing the two is how
     *   that is established.
     * @param stopAtAddress end the scan as soon as this address is seen.
     */
    suspend fun scan(durationMs: Long, platformFilter: Boolean, stopAtAddress: String? = null): List<Sighting> {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: throw As11TransportException("Bluetooth is off")
        val seen = ConcurrentHashMap<String, Sighting>()
        // Completes early: with an error code if the scan could not run, or with 0 once the wanted device is seen.
        val finished = CompletableDeferred<Int>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord ?: return
                val raw = record.bytes ?: return
                val advertisement = Advertisement.parse(raw)
                val platformSawService = record.serviceUuids?.contains(SERVICE) == true
                val wanted = stopAtAddress != null && result.device.address.equals(stopAtAddress, ignoreCase = true)
                if (!advertisement.looksLikeAirSense && !platformSawService && !wanted) return
                val sighting = Sighting(
                    result.device,
                    result.rssi,
                    advertisement,
                    Advertisement.redactedHex(raw),
                    platformSawService,
                    record.deviceName != null,
                )
                seen.merge(result.device.address, sighting) { old, new -> if (new.rssi > old.rssi) new else old }
                if (wanted) finished.complete(0)
            }

            override fun onScanFailed(errorCode: Int) {
                finished.complete(errorCode)
            }
        }
        val filters = if (platformFilter) listOf(ScanFilter.Builder().setServiceUuid(SERVICE).build()) else null
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filters, settings, callback)
        val outcome = try {
            withTimeoutOrNull(durationMs) { finished.await() }
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
        if (outcome != null && outcome != 0) throw As11TransportException("scan could not run, code $outcome")
        // A scan cut short by the radio going off looks exactly like an empty one.
        if (adapter?.isEnabled != true) throw As11TransportException("Bluetooth was turned off")
        return seen.values.sortedByDescending { it.rssi }
    }

    private companion object {
        val SERVICE = ParcelUuid(GattLink.SERVICE)
    }
}
