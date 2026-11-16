// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay

import android.app.Application
import com.everysoft.paprelay.ble.MachineClaim
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
    private val claim = MachineClaim()
    val deviceCheck: DeviceCheck by lazy {
        DeviceCheck(this, scope, pairingStore, scanner, healthConnect, claim) { sync.forget() }
    }
    val sync: SyncRunner by lazy {
        SyncRunner(this, scope, GattMachineConnector(this, scope, pairingStore, scanner), HealthConnectSink(this), claim, pairingStore) {
            DeviceCheck.code(it, messages = false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Read once, off the main thread; screens observe the result.
        scope.launch {
            val pairing = pairingStore.load()
            sync.load()
            // Once the user has synchronised by hand, the system is asked to do it about once an
            // hour and when the machine comes into range. Asked again at every start, in case
            // the system has dropped the request.
            if (pairing is PairingState.Paired && sync.status.value.lastReport != null) sync.keepWatch()
        }
        // A check that was cut short may have left its test value in Health Connect.
        scope.launch { healthConnect.removeLeftover() }
    }
}
