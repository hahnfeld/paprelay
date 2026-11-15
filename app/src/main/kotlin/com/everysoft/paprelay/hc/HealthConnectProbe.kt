// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.hc

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/** Outcome of the Health Connect write test. */
class ProbeResult(val written: Int, val removed: Boolean)

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

    /**
     * Writes the test value and removes it. The removal is attempted even if
     * the check is cancelled, and twice if need be: a made-up reading must not
     * be left in the user's health data. Throws if Health Connect refuses the write.
     */
    suspend fun writeAndRemove(): ProbeResult {
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
        var written = 0
        var removed = false
        try {
            written = client.insertRecords(listOf(record)).recordIdsList.size
        } finally {
            withContext(NonCancellable) {
                repeat(2) {
                    if (!removed) {
                        removed = try {
                            client.deleteRecords(
                                RespiratoryRateRecord::class,
                                recordIdsList = emptyList(),
                                clientRecordIdsList = listOf(PROBE_ID),
                            )
                            true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            false
                        }
                    }
                }
            }
        }
        return ProbeResult(written, removed)
    }

    private companion object {
        const val PROBE_ID = "paprelay/probe/write-test"
    }
}
