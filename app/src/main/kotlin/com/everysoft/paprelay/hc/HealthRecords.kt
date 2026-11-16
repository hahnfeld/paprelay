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
import android.os.Build
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.feature.ExperimentalPersonalHealthRecordApi
import com.everysoft.paprelay.core.nights.FhirObservations
import com.everysoft.paprelay.core.nights.FhirResource
import com.everysoft.paprelay.core.nights.Night
import com.everysoft.paprelay.core.nights.NightFigure
import com.everysoft.paprelay.sync.HealthSinkException
import com.everysoft.paprelay.sync.NightRecords
import com.everysoft.paprelay.sync.RecordsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What the trial write of the device check found. [reason] is Health Connect's own word when it refused. */
class RecordsProbe(val accepted: Boolean, val removed: Boolean, val reason: String?)

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

    /** The id of PAPRelay's data source, once found or made. Forgotten when a call fails, in case it was removed. */
    @Volatile
    private var sourceId: String? = null

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

    override suspend fun state(): RecordsState = when {
        !supported -> RecordsState.NOT_SUPPORTED
        !allowed -> RecordsState.NOT_ALLOWED
        else -> RecordsState.READY
    }

    override suspend fun open(): Boolean = guarded {
        if (Build.VERSION.SDK_INT < FIRST_VERSION) throw HealthSinkException(notAllowed = false)
        val manager = manager()
        val existing = answered { sources(manager) }.firstOrNull { it.displayName == SOURCE_NAME }
        val source = existing ?: answered { create(manager) }
        sourceId = source.id
        // A made-up night that a device check could not confirm it had removed goes now, before anything real is written beside it.
        if (existing != null) answered { remove(manager, source.id, FhirObservations.idsFor(PROBE_NIGHT, PROBE_TAG)) }
        existing == null
    }

    // Not cut short by a stop: one night's records are few, and half of them are only more work for the next run.
    override suspend fun write(resources: List<FhirResource>, removeIds: List<String>): Unit = guarded {
        if (Build.VERSION.SDK_INT < FIRST_VERSION) throw HealthSinkException(notAllowed = false)
        val manager = manager()
        val source = sourceId ?: answered { sources(manager) }.firstOrNull { it.displayName == SOURCE_NAME }?.id?.also { sourceId = it }
            // It was there when the run began and is gone: the user removed it meanwhile. The next run starts it afresh.
            ?: throw HealthSinkException(notAllowed = false)
        if (removeIds.isNotEmpty()) answered { remove(manager, source, removeIds) }
        for (chunk in resources.chunked(CHUNK)) {
            // Health Connect says how many it stored. Fewer than were sent is not a success.
            if (answered { upsert(manager, source, chunk) } != chunk.size) throw HealthSinkException(notAllowed = false)
        }
    }

    /**
     * Proves that this phone accepts PAPRelay's records: writes one made-up night, of exactly
     * the shape a real one has and with every figure a night can have, under a name no real
     * machine has, and removes it again.
     *
     * @throws HealthSinkException if Health Connect could not be asked at all.
     */
    suspend fun probe(): RecordsProbe = withContext(NonCancellable) {
        if (Build.VERSION.SDK_INT < FIRST_VERSION) throw HealthSinkException(notAllowed = false)
        val manager = manager()
        val source = try {
            (answered { sources(manager) }.firstOrNull { it.displayName == SOURCE_NAME } ?: answered { create(manager) }).id
        } catch (e: SecurityException) {
            throw HealthSinkException(notAllowed = true, cause = e)
        } catch (e: HealthConnectException) {
            throw HealthSinkException(notAllowed = e.errorCode == HealthConnectException.ERROR_SECURITY, cause = e)
        }
        val resources = FhirObservations.forNight(PROBE_NIGHT, PROBE_TAG)
        var accepted = false
        var reason: String? = null
        var removed = false
        try {
            accepted = answered { upsert(manager, source, resources) } == resources.size
            if (!accepted) reason = "fewer stored than sent"
        } catch (e: HealthConnectException) {
            // Its own word for why. This is about made-up data, so it holds nothing of the user's.
            reason = "code ${e.errorCode}: ${e.message.orEmpty().take(MAX_REASON)}"
        } catch (e: IllegalArgumentException) {
            reason = e.message.orEmpty().take(MAX_REASON)
        } finally {
            // Removed whatever became of the write: it may have landed even when its answer did not.
            removed = try {
                answered { remove(manager, source, FhirObservations.idsFor(PROBE_NIGHT, PROBE_TAG)) }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
        }
        RecordsProbe(accepted, removed, reason)
    }

    /** Removes the made-up night a check that was cut short may have left behind. Does nothing if there is none. */
    suspend fun removeLeftover() {
        if (Build.VERSION.SDK_INT < FIRST_VERSION) return
        try {
            if (!supported || !allowed) return
            val manager = manager()
            val existing = answered { sources(manager) }.firstOrNull { it.displayName == SOURCE_NAME } ?: return
            answered { remove(manager, existing.id, FhirObservations.idsFor(PROBE_NIGHT, PROBE_TAG)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing to do: the next check removes it.
        }
    }

    /** Runs [block] to its end whatever is stopped around it, and turns what Health Connect throws into the one failure the engine knows. */
    private suspend fun <T> guarded(block: suspend () -> T): T = withContext(NonCancellable) {
        try {
            block()
        } catch (e: HealthSinkException) {
            sourceId = null
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            throw HealthSinkException(notAllowed = true, cause = e)
        } catch (e: HealthConnectException) {
            sourceId = null
            throw HealthSinkException(
                notAllowed = e.errorCode == HealthConnectException.ERROR_SECURITY,
                cause = e,
                rateLimited = e.errorCode == HealthConnectException.ERROR_RATE_LIMIT_EXCEEDED,
            )
        } catch (e: Exception) {
            sourceId = null
            // The cause is kept for its type only; a record's own text holds a night's figures, and is never logged.
            throw HealthSinkException(notAllowed = false, cause = e)
        }
    }

    @RequiresApi(FIRST_VERSION)
    private fun manager(): HealthConnectManager =
        context.getSystemService(HealthConnectManager::class.java) ?: throw HealthSinkException(notAllowed = false)

    @RequiresApi(FIRST_VERSION)
    private suspend fun create(manager: HealthConnectManager): MedicalDataSource = call { receiver ->
        manager.createMedicalDataSource(CreateMedicalDataSourceRequest.Builder(SOURCE_URI, SOURCE_NAME, version()).build(), Runnable::run, receiver)
    }

    @RequiresApi(FIRST_VERSION)
    private suspend fun sources(manager: HealthConnectManager): List<MedicalDataSource> = call { receiver ->
        manager.getMedicalDataSources(GetMedicalDataSourcesRequest.Builder().addPackageName(context.packageName).build(), Runnable::run, receiver)
    }

    @RequiresApi(FIRST_VERSION)
    private suspend fun upsert(manager: HealthConnectManager, source: String, resources: List<FhirResource>): Int {
        val requests = resources.map { UpsertMedicalResourceRequest.Builder(source, version(), it.json).build() }
        return call<List<android.health.connect.datatypes.MedicalResource>> { receiver ->
            manager.upsertMedicalResources(requests, Runnable::run, receiver)
        }.size
    }

    @RequiresApi(FIRST_VERSION)
    private suspend fun remove(manager: HealthConnectManager, source: String, ids: List<String>) {
        val resourceIds = ids.map { MedicalResourceId(source, FHIR_RESOURCE_TYPE_OBSERVATION, it) }
        call<Void?> { receiver -> manager.deleteMedicalResources(resourceIds, Runnable::run, receiver) }
    }

    @RequiresApi(FIRST_VERSION)
    private fun version(): FhirVersion = FhirVersion.parseFhirVersion(FhirObservations.FHIR_VERSION)

    /**
     * Bounds the wait for Health Connect, which now and then does not answer at all. Giving up
     * is a failure of Health Connect's, and is reported as one: not as this run having been stopped.
     */
    private suspend fun <T> answered(block: suspend () -> T): T {
        // Kept apart from what the timeout itself returns: an answer that arrives in the very instant the
        // wait is given up is still an answer, and the absence of one is not mistaken for a null.
        var answer: Result<T>? = null
        withTimeoutOrNull(ANSWER_MS) { answer = Result.success(block()) }
        return (answer ?: throw HealthSinkException(notAllowed = false, cause = HealthConnectUnresponsiveException())).getOrThrow()
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
        const val MAX_REASON = 300

        /** No machine's name: a real one is "m-" and sixteen hexadecimal digits. */
        const val PROBE_TAG = "paprelay-write-test"

        /** A night long past with one of everything, every value plainly not a measurement. */
        val PROBE_NIGHT = Night(LocalDate.of(2000, 1, 1), 0, 1, 1, NightFigure.entries.associateWith { 1L }, complete = true)
    }
}
