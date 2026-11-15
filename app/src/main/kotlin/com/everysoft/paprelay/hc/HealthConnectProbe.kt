// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.hc

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import java.time.Instant
import java.time.ZoneId

/**
 * Proves that this build is allowed to write to Health Connect: writes one
 * clearly labelled test value and removes it again.
 */
class HealthConnectProbe(private val context: Context) {
    val available: Boolean
        get() = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    val writePermission: String = HealthPermission.getWritePermission(RespiratoryRateRecord::class)

    suspend fun hasPermission(): Boolean =
        writePermission in HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()

    /** @return a short description of what happened. Throws if Health Connect refuses. */
    suspend fun writeAndRemove(): String {
        val client = HealthConnectClient.getOrCreate(context)
        val now = Instant.now()
        val record = RespiratoryRateRecord(
            time = now,
            zoneOffset = ZoneId.systemDefault().rules.getOffset(now),
            rate = 12.0,
            metadata = Metadata.autoRecorded(
                clientRecordId = PROBE_ID,
                clientRecordVersion = now.toEpochMilli(),
                device = Device(type = Device.TYPE_UNKNOWN, manufacturer = "PAPRelay", model = "write test"),
            ),
        )
        val inserted = client.insertRecords(listOf(record)).recordIdsList.size
        client.deleteRecords(RespiratoryRateRecord::class, recordIdsList = emptyList(), clientRecordIdsList = listOf(PROBE_ID))
        return "wrote $inserted test record and removed it"
    }

    private companion object {
        const val PROBE_ID = "paprelay/probe/write-test"
    }
}
