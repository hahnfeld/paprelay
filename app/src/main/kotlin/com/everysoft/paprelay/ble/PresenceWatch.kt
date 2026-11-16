// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.ble

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.everysoft.paprelay.PapRelayApp

/**
 * Has the phone itself notice when the paired machine comes into range, with the app not
 * running: the phone's Bluetooth chip listens for that one address at low power and tells
 * the app once when it is first heard. That is when a synchronisation is worth trying, and
 * it is what brings a night across soon after the phone comes home.
 *
 * This is a help, not the mechanism: not every phone can do it, and the request is lost
 * when Bluetooth is switched off or the phone restarts. The hourly run works without it and
 * asks again each time.
 */
class PresenceWatch(private val context: Context) {
    private val scanner: BluetoothLeScanner?
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner

    private val allowed: Boolean
        get() = context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    /**
     * Asks to be told when [address] is first heard. Safe to call again and again: a request
     * already in place is left as it is. Returns false if the phone would not take it.
     */
    fun start(address: String): Boolean {
        if (!allowed) return false
        val scanner = scanner ?: return false
        return try {
            val filter = ScanFilter.Builder().setDeviceAddress(address).build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                // Once when it appears and once when it has gone; not for every announcement in between.
                .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH or ScanSettings.CALLBACK_TYPE_MATCH_LOST)
                // The same kinds of announcement the device check listens for.
                .setLegacy(false)
                .setPhy(BluetoothDevice.PHY_LE_1M)
                .build()
            scanner.startScan(listOf(filter), settings, intent()) == 0
        } catch (e: SecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        } catch (e: IllegalStateException) {
            false
        }
    }

    /** Withdraws the request, when the machine is forgotten. */
    fun stop() {
        if (!allowed) return
        try {
            scanner?.stopScan(intent())
        } catch (e: SecurityException) {
            // Nothing to withdraw with.
        } catch (e: IllegalStateException) {
            // Bluetooth went off in between, and took the request with it.
        }
    }

    // Mutable because the system adds what it heard; it names this app's own receiver and nothing else.
    private fun intent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, PresenceReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )
}

/** Told by the system that the paired machine has been heard, or has gone. Nothing it is given is kept or logged. */
class PresenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0) != 0) return
        // Gone again is not news: being out of range is the ordinary state of a phone away from home.
        if (intent.getIntExtra(BluetoothLeScanner.EXTRA_CALLBACK_TYPE, 0) == ScanSettings.CALLBACK_TYPE_MATCH_LOST) return
        (context.applicationContext as? PapRelayApp)?.sync?.onMachineHeard()
    }
}
