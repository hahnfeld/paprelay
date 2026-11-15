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
import com.everysoft.paprelay.as11.Hex
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/** One device seen during a scan. */
class Sighting(
    val device: BluetoothDevice,
    val rssi: Int,
    val advertisement: Advertisement,
    /** The payload exactly as received, for diagnosing parser disagreements. */
    val rawHex: String,
    /** Whether Android's own parser recovered the vendor service from this payload. */
    val platformSawService: Boolean,
    val platformName: String?,
)

/** Finds AirSense 11 machines nearby. Callers must hold BLUETOOTH_SCAN. */
@SuppressLint("MissingPermission")
class MachineScanner(context: Context) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter

    val bluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    fun device(address: String): BluetoothDevice? = adapter?.getRemoteDevice(address)

    /**
     * Scans for [durationMs] and returns the strongest sighting of each device
     * that looks like an AirSense 11.
     *
     * @param platformFilter when true, asks the system to filter on the vendor
     *   service. The machine's advertising payload is malformed, so this may
     *   find nothing where an unfiltered scan succeeds; comparing the two is how
     *   that is established.
     */
    suspend fun scan(durationMs: Long, platformFilter: Boolean): List<Sighting> {
        val scanner = adapter?.bluetoothLeScanner ?: throw As11TransportException("Bluetooth is off")
        val seen = ConcurrentHashMap<String, Sighting>()
        var failure = 0
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord ?: return
                val raw = record.bytes ?: return
                val advertisement = Advertisement.parse(raw)
                val platformSawService = record.serviceUuids?.contains(SERVICE) == true
                if (!advertisement.looksLikeAirSense && !platformSawService) return
                val sighting = Sighting(
                    result.device, result.rssi, advertisement, Hex.encode(raw), platformSawService, record.deviceName,
                )
                seen.merge(result.device.address, sighting) { old, new -> if (new.rssi > old.rssi) new else old }
            }

            override fun onScanFailed(errorCode: Int) {
                failure = errorCode
            }
        }
        val filters = if (platformFilter) listOf(ScanFilter.Builder().setServiceUuid(SERVICE).build()) else null
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filters, settings, callback)
        try {
            delay(durationMs)
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
        if (failure != 0 && seen.isEmpty()) throw As11TransportException("scan failed, code $failure")
        return seen.values.sortedByDescending { it.rssi }
    }

    private companion object {
        val SERVICE = ParcelUuid(GattLink.SERVICE)
    }
}
