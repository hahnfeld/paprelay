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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId

/** Outcome of the Health Connect write test. */
class ProbeResult(val written: Int, val removed: Boolean)

/** Health Connect did not answer within the time allowed. */
class HealthConnectUnresponsiveException : java.io.IOException("Health Connect did not answer in time")

/**
 * Proves that this build is allowed to write to Health Connect: writes one
 * clearly labelled test value and removes it again.
 */
class HealthConnectProbe(private val context: Context) {
    private val exclusive = Mutex()

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
        // One at a time: the clean-up at app start must not remove a value a check has just written.
        exclusive.withLock { writeAndRemoveLocked() }
    }

    /**
     * True if the last [writeAndRemove] could not confirm that its test value is
     * gone. It is then removed by [removeLeftover] at the next app start, or by
     * the next check. Cleared when a new attempt begins.
     */
    @Volatile
    var testValueMayRemain: Boolean = false
        private set

    private suspend fun writeAndRemoveLocked(): ProbeResult {
        testValueMayRemain = false
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
        if (finished == null) {
            // The write may still land after being given up on, so try once more to remove it.
            // If this fails too, the next successful check removes it: the record id is fixed.
            val retried = withTimeoutOrNull(RETRY_LIMIT_MS) {
                runCatching {
                    client.deleteRecords(
                        RespiratoryRateRecord::class,
                        recordIdsList = emptyList(),
                        clientRecordIdsList = listOf(PROBE_ID),
                    )
                }.isSuccess
            }
            testValueMayRemain = retried != true
            // If the write had already been refused, that is the more useful thing to report.
            throw refusal ?: HealthConnectUnresponsiveException()
        }
        testValueMayRemain = !removed
        refusal?.let { throw it }
        return ProbeResult(written, removed)
    }

    /**
     * Removes a test value left behind by a run that was cut short. Safe to
     * call at any time; does nothing if there is none or permission is missing.
     */
    suspend fun removeLeftover() {
        try {
            if (!available || !hasPermission()) return
            exclusive.withLock {
                withTimeoutOrNull(RETRY_LIMIT_MS) {
                HealthConnectClient.getOrCreate(context).deleteRecords(
                    RespiratoryRateRecord::class,
                    recordIdsList = emptyList(),
                    clientRecordIdsList = listOf(PROBE_ID),
                )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing to do: the next check removes it.
        }
    }

    private companion object {
        const val PROBE_ID = "paprelay/probe/write-test"
        const val LIMIT_MS = 15_000L
        const val RETRY_PAUSE_MS = 500L
        const val RETRY_LIMIT_MS = 5_000L
    }
}
