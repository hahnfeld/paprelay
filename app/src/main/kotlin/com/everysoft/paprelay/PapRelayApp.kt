// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay

import android.app.Application
import com.everysoft.paprelay.ble.MachineScanner
import com.everysoft.paprelay.check.DeviceCheck
import com.everysoft.paprelay.hc.HealthConnectProbe
import com.everysoft.paprelay.pairing.PairingStore
import com.everysoft.paprelay.pairing.PairingState
import com.everysoft.paprelay.sync.GattMachineConnector
import com.everysoft.paprelay.sync.HealthConnectSink
import com.everysoft.paprelay.sync.SyncRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Process-wide objects. Deliberately plain: there is no dependency-injection framework. */
class PapRelayApp : Application() {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val pairingStore: PairingStore by lazy { PairingStore(this) }
    val healthConnect: HealthConnectProbe by lazy { HealthConnectProbe(this) }
    private val scanner: MachineScanner by lazy { MachineScanner(this) }
    val deviceCheck: DeviceCheck by lazy {
        DeviceCheck(this, scope, pairingStore, scanner, healthConnect)
    }
    val sync: SyncRunner by lazy {
        SyncRunner(this, scope, GattMachineConnector(this, scope, pairingStore, scanner), HealthConnectSink(this))
    }

    override fun onCreate() {
        super.onCreate()
        // Read once, off the main thread; screens observe the result.
        scope.launch {
            // With a machine paired, the system is asked to run a synchronisation about once an hour.
            if (pairingStore.load() is PairingState.Paired) sync.schedule()
            sync.load()
        }
        // A check that was cut short may have left its test value in Health Connect.
        scope.launch { healthConnect.removeLeftover() }
    }
}
