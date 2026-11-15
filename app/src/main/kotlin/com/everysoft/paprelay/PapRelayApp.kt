// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay

import android.app.Application
import com.everysoft.paprelay.ble.MachineScanner
import com.everysoft.paprelay.check.DeviceCheck
import com.everysoft.paprelay.hc.HealthConnectProbe
import com.everysoft.paprelay.pairing.PairingStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Process-wide objects. Deliberately plain: there is no dependency-injection framework. */
class PapRelayApp : Application() {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val pairingStore: PairingStore by lazy { PairingStore(this) }
    val healthConnect: HealthConnectProbe by lazy { HealthConnectProbe(this) }
    val deviceCheck: DeviceCheck by lazy {
        DeviceCheck(this, scope, pairingStore, MachineScanner(this), healthConnect)
    }

    override fun onCreate() {
        super.onCreate()
        // Read once, off the main thread; screens observe the result.
        scope.launch { pairingStore.load() }
        // A check that was cut short may have left its test value in Health Connect.
        scope.launch { healthConnect.removeLeftover() }
    }
}
