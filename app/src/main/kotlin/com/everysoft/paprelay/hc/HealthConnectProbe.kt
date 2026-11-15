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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
     * Writes the test value and removes it.
     *
     * The whole exchange runs to completion even if the check is stopped: a write
     * abandoned half-way could still land after a removal that ran too early,
     * leaving a made-up reading in the user's health data. It is bounded by a
     * time limit instead, so a Health Connect that never answers cannot hang the
     * check. Throws if the write is refused or nothing answers in time.
     */
    suspend fun writeAndRemove(): ProbeResult = withContext(NonCancellable) {
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
        var refusal: Exception? = null
        val finished = withTimeoutOrNull(LIMIT_MS) {
            try {
                written = client.insertRecords(listOf(record)).recordIdsList.size
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                refusal = e
            }
            // Removed even after a failed write: the failure may have been in the reply, not the write.
            for (attempt in 1..2) {
                try {
                    client.deleteRecords(
                        RespiratoryRateRecord::class,
                        recordIdsList = emptyList(),
                        clientRecordIdsList = listOf(PROBE_ID),
                    )
                    removed = true
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    delay(RETRY_PAUSE_MS)
                }
            }
        }
        if (finished == null) throw java.io.IOException("Health Connect did not answer in time")
        refusal?.let { throw it }
        ProbeResult(written, removed)
    }

    private companion object {
        const val PROBE_ID = "paprelay/probe/write-test"
        const val LIMIT_MS = 15_000L
        const val RETRY_PAUSE_MS = 500L
    }
}
