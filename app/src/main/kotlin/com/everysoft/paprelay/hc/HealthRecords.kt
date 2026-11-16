// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.hc

import android.content.Context
import android.content.pm.PackageManager
import android.health.connect.CreateMedicalDataSourceRequest
import android.health.connect.GetMedicalDataSourcesRequest
import android.health.connect.HealthConnectException
import android.health.connect.HealthConnectManager
import android.health.connect.MedicalResourceId
import android.health.connect.UpsertMedicalResourceRequest
import android.health.connect.datatypes.FhirResource.FHIR_RESOURCE_TYPE_OBSERVATION
import android.health.connect.datatypes.FhirVersion
import android.health.connect.datatypes.MedicalDataSource
import android.net.Uri
import androidx.core.net.toUri
import android.os.Build
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.feature.ExperimentalPersonalHealthRecordApi
import com.everysoft.paprelay.core.nights.FhirObservations
import com.everysoft.paprelay.core.nights.FhirResource
import com.everysoft.paprelay.sync.HealthSinkException
import com.everysoft.paprelay.sync.NightRecords
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The phone's health records: the part of Health Connect, from Android 16, that keeps
 * clinical records in the FHIR format. PAPRelay writes each night's figures there as
 * observations and reads nothing back.
 *
 * Everything it writes sits in one data source of its own, so it can be told apart from
 * records that came from a clinic, and removed as a whole.
 */
class HealthRecords(private val context: Context) : NightRecords {
    /** The permission that lets an app write health records. Asked for like any other Health Connect permission. */
    val writePermission: String = WRITE_PERMISSION

    /** Whether this phone keeps health records at all. */
    @OptIn(ExperimentalPersonalHealthRecordApi::class)
    val supported: Boolean
        get() = Build.VERSION.SDK_INT >= FIRST_VERSION && try {
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE &&
                HealthConnectClient.getOrCreate(context).features.getFeatureStatus(HealthConnectFeatures.FEATURE_PERSONAL_HEALTH_RECORD) ==
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        } catch (e: RuntimeException) {
            false
        }

    val allowed: Boolean get() = context.checkSelfPermission(WRITE_PERMISSION) == PackageManager.PERMISSION_GRANTED

    override suspend fun ready(): Boolean = supported && allowed

    // Not cut short by a stop: one night's records are few, and half of them are only more work for the next run.
    override suspend fun write(resources: List<FhirResource>, removeIds: List<String>): Unit = withContext(NonCancellable) {
        if (Build.VERSION.SDK_INT < FIRST_VERSION) throw HealthSinkException(notAllowed = false)
        try {
            val manager = manager()
            val source = source(manager)
            if (removeIds.isNotEmpty()) answered { remove(manager, source, removeIds) }
            for (chunk in resources.chunked(CHUNK)) answered { upsert(manager, source, chunk) }
        } catch (e: HealthSinkException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            throw HealthSinkException(notAllowed = true, cause = e)
        } catch (e: HealthConnectException) {
            throw HealthSinkException(
                notAllowed = e.errorCode == HealthConnectException.ERROR_SECURITY,
                cause = e,
                rateLimited = e.errorCode == HealthConnectException.ERROR_RATE_LIMIT_EXCEEDED,
            )
        } catch (e: Exception) {
            // The cause is kept for its type only; a record's own text holds a night's figures, and is never logged.
            throw HealthSinkException(notAllowed = false, cause = e)
        }
    }

    /**
     * Proves that this phone accepts PAPRelay's kind of record: writes one clearly labelled test
     * observation of the same shape as a night's figure, and removes it again.
     *
     * @return true if it was stored and removed; false if the phone refused the record itself.
     * @throws Exception for anything else: not allowed, no answer, an error inside Health Connect.
     */
    suspend fun probe(): Boolean = withContext(NonCancellable) {
        if (Build.VERSION.SDK_INT < FIRST_VERSION) return@withContext false
        val manager = manager()
        val source = source(manager)
        val stored = try {
            answered { upsert(manager, source, listOf(FhirResource(PROBE_ID, PROBE_JSON))) } == 1
        } catch (e: HealthConnectException) {
            if (e.errorCode != HealthConnectException.ERROR_INVALID_ARGUMENT) throw e
            false
        } catch (e: IllegalArgumentException) {
            false
        }
        // Removed whether or not the write was confirmed: it may have landed all the same.
        answered { remove(manager, source, listOf(PROBE_ID)) }
        stored
    }

    /** Removes a test record a check that was cut short may have left behind. Does nothing if there is none. */
    suspend fun removeLeftover() {
        if (Build.VERSION.SDK_INT < FIRST_VERSION) return
        try {
            if (!supported || !allowed) return
            val manager = manager()
            val existing = answered { sources(manager) }.firstOrNull { it.displayName == SOURCE_NAME } ?: return
            answered { remove(manager, existing, listOf(PROBE_ID)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing to do: the next check removes it.
        }
    }

    @RequiresApi(FIRST_VERSION)
    private fun manager(): HealthConnectManager =
        context.getSystemService(HealthConnectManager::class.java) ?: throw HealthSinkException(notAllowed = false)

    /** PAPRelay's own data source, made the first time it is needed. */
    @RequiresApi(FIRST_VERSION)
    private suspend fun source(manager: HealthConnectManager): MedicalDataSource =
        answered { sources(manager) }.firstOrNull { it.displayName == SOURCE_NAME } ?: answered {
            call<MedicalDataSource> { receiver ->
                manager.createMedicalDataSource(CreateMedicalDataSourceRequest.Builder(SOURCE_URI, SOURCE_NAME, version()).build(), Runnable::run, receiver)
            }
        }

    @RequiresApi(FIRST_VERSION)
    private suspend fun sources(manager: HealthConnectManager): List<MedicalDataSource> = call { receiver ->
        manager.getMedicalDataSources(GetMedicalDataSourcesRequest.Builder().addPackageName(context.packageName).build(), Runnable::run, receiver)
    }

    @RequiresApi(FIRST_VERSION)
    private suspend fun upsert(manager: HealthConnectManager, source: MedicalDataSource, resources: List<FhirResource>): Int {
        val requests = resources.map { UpsertMedicalResourceRequest.Builder(source.id, version(), it.json).build() }
        return call<List<android.health.connect.datatypes.MedicalResource>> { receiver ->
            manager.upsertMedicalResources(requests, Runnable::run, receiver)
        }.size
    }

    @RequiresApi(FIRST_VERSION)
    private suspend fun remove(manager: HealthConnectManager, source: MedicalDataSource, ids: List<String>) {
        val resourceIds = ids.map { MedicalResourceId(source.id, FHIR_RESOURCE_TYPE_OBSERVATION, it) }
        call<Void?> { receiver -> manager.deleteMedicalResources(resourceIds, Runnable::run, receiver) }
    }

    @RequiresApi(FIRST_VERSION)
    private fun version(): FhirVersion = FhirVersion.parseFhirVersion(FhirObservations.FHIR_VERSION)

    /** Bounds the wait for Health Connect, which now and then does not answer at all. */
    private suspend fun <T> answered(block: suspend () -> T): T {
        var result: Result<T>? = null
        withTimeoutOrNull(ANSWER_MS) { result = runCatching { block() } }
        return (result ?: throw HealthSinkException(notAllowed = false)).getOrThrow()
    }

    @RequiresApi(FIRST_VERSION)
    private suspend fun <T> call(start: (OutcomeReceiver<T, HealthConnectException>) -> Unit): T = suspendCancellableCoroutine { continuation ->
        start(
            object : OutcomeReceiver<T, HealthConnectException> {
                override fun onResult(result: T) {
                    if (continuation.isActive) continuation.resume(result)
                }

                override fun onError(error: HealthConnectException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            },
        )
    }

    private companion object {
        /** Android 16. */
        const val FIRST_VERSION = 36
        /** Spelt out: the constant for it exists only on phones that have health records. */
        const val WRITE_PERMISSION = "android.permission.health.WRITE_MEDICAL_DATA"
        const val SOURCE_NAME = "PAPRelay (CPAP machine)"

        /** A name for where the records come from, as the format requires. Nothing is ever fetched from it. */
        val SOURCE_URI: Uri = "https://github.com/hahnfeld/paprelay/".toUri()
        const val CHUNK = 50
        const val ANSWER_MS = 30_000L
        const val PROBE_ID = "paprelay-write-test"
        const val PROBE_JSON =
            """{"resourceType":"Observation","id":"paprelay-write-test","status":"final",""" +
                """"category":[{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/observation-category","code":"laboratory","display":"Laboratory"}]}],""" +
                """"code":{"coding":[{"system":"https://github.com/hahnfeld/paprelay/fhir/CodeSystem/cpap-night","code":"write-test","display":"PAPRelay write test"}],""" +
                """"text":"PAPRelay write test (not a measurement)"},""" +
                """"effectiveDateTime":"2026-01-01T12:00:00Z",""" +
                """"valueQuantity":{"value":0,"unit":"events/h","system":"http://unitsofmeasure.org","code":"{events}/h"}}"""
    }
}
