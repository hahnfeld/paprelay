// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.sync

import android.content.Context
import android.health.connect.HealthConnectException
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import com.everysoft.paprelay.core.sync.RecordWrite
import com.everysoft.paprelay.core.sync.SessionChange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Writes respiratory-rate readings to Health Connect and removes them again.
 *
 * PAPRelay holds no permission to read health data. Its own records are found by the ids
 * it gave them, which is all that writing over them and deleting them needs.
 */
class HealthConnectSink(private val context: Context) : HealthSink {
    private val writePermission = HealthPermission.getWritePermission(RespiratoryRateRecord::class)

    override suspend fun notReady(): SyncOutcome? {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) return SyncOutcome.HEALTH_CONNECT_UNAVAILABLE
        val granted = try {
            withTimeoutOrNull(ANSWER_MS) { HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return SyncOutcome.HEALTH_CONNECT_FAILED
        return if (writePermission in granted) null else SyncOutcome.HEALTH_CONNECT_NOT_ALLOWED
    }

    // Not cut short by a stop: a session's change is small, and half of one is only more work for the next run.
    override suspend fun apply(change: SessionChange): Unit = withContext(NonCancellable) {
        try {
            val client = HealthConnectClient.getOrCreate(context)
            for (ids in change.deleteRecordIds.chunked(CHUNK)) {
                withTimeoutOrNull(ANSWER_MS) {
                    client.deleteRecords(RespiratoryRateRecord::class, recordIdsList = emptyList(), clientRecordIdsList = ids)
                } ?: throw HealthSinkException(notAllowed = false)
            }
            for (writes in change.writes.chunked(CHUNK)) {
                withTimeoutOrNull(ANSWER_MS) { client.insertRecords(writes.map(::record)) } ?: throw HealthSinkException(notAllowed = false)
            }
        } catch (e: HealthSinkException) {
            throw e
        } catch (e: SecurityException) {
            throw HealthSinkException(notAllowed = true, cause = e)
        } catch (e: Exception) {
            // Among these: Health Connect's rate limit, which the library reports as an illegal state.
            // The cause is kept for the type only; a record's own text holds a time and a rate, and is never logged.
            val limited = generateSequence<Throwable>(e) { it.cause?.takeIf { cause -> cause !== it } }.take(MAX_CAUSE_DEPTH)
                .any { it is HealthConnectException && it.errorCode == HealthConnectException.ERROR_RATE_LIMIT_EXCEEDED }
            throw HealthSinkException(notAllowed = false, cause = e, rateLimited = limited)
        }
    }

    private fun record(write: RecordWrite): RespiratoryRateRecord {
        val time = write.time
        return RespiratoryRateRecord(
            time = time,
            zoneOffset = write.zone.rules.getOffset(time),
            rate = write.breathsPerMinute,
            metadata = Metadata.autoRecorded(
                clientRecordId = write.recordId,
                clientRecordVersion = write.version,
                device = Device(type = Device.TYPE_UNKNOWN, manufacturer = "ResMed", model = "AirSense 11"),
            ),
        )
    }

    private companion object {
        /** Well inside what Health Connect takes in one call. */
        const val CHUNK = 500
        const val ANSWER_MS = 30_000L
        const val MAX_CAUSE_DEPTH = 8
    }
}
