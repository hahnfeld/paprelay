// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.sync

import com.everysoft.paprelay.core.ClockOffset
import com.everysoft.paprelay.core.MachineTime
import com.everysoft.paprelay.core.sync.LedgerEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** How the last attempt to synchronise ended. */
enum class SyncOutcome {
    /** Everything the machine had was written. */
    COMPLETE,

    /** Some sessions were written; something stopped the rest, which the next attempt takes up. */
    PARTIAL,

    /** The machine did not answer; it is probably out of range. Ordinary, and not an error. */
    MACHINE_AWAY,

    BLUETOOTH_OFF,
    BLUETOOTH_NOT_ALLOWED,
    HEALTH_CONNECT_NOT_ALLOWED,
    HEALTH_CONNECT_UNAVAILABLE,

    /** The machine no longer accepts the stored pairing. */
    PAIRING_REJECTED,

    /** The machine that answered is not the one that was paired. */
    WRONG_MACHINE,

    /** The machine's software is not one whose data this version can read with confidence. */
    SOFTWARE_NOT_CONFIRMED,

    /** The machine's clock could not be read consistently, so no time can be trusted. */
    CLOCK_UNRELIABLE,

    /** The connection failed part-way. */
    CONNECTION_LOST,

    /** What the machine sent could not be understood. */
    DATA_NOT_UNDERSTOOD,

    /** Health Connect refused or failed a write. */
    HEALTH_CONNECT_FAILED,

    /** Something in PAPRelay itself went wrong. */
    INTERNAL,
}

/** What is remembered about the last attempt, for the screen. Counts only: no health values. */
class SyncReport(
    val atMs: Long,
    val outcome: SyncOutcome,
    /** Sessions looked at, and readings written or rewritten, in this attempt. */
    val sessions: Int,
    val readingsWritten: Int,
)

/**
 * Everything the app remembers between synchronisations, apart from the pairing.
 *
 * @property machineTag the stable, non-identifying name the records of this machine are filed under.
 * @property ledger what Health Connect is known to hold, by session.
 * @property resumeFromMs a machine time before which every session is known to have been dealt
 *   with, or null if nothing has been yet.
 * @property leakLimitLitresPerMinute the leak above which a minute is left out; null for no limit.
 */
class SyncState(
    val machineTag: String?,
    val ledger: Map<String, LedgerEntry>,
    val resumeFromMs: Long?,
    val lastReport: SyncReport?,
    val lastCompleteAtMs: Long?,
    val leakLimitLitresPerMinute: Int?,
) {
    fun with(
        machineTag: String? = this.machineTag,
        ledger: Map<String, LedgerEntry> = this.ledger,
        resumeFromMs: Long? = this.resumeFromMs,
        lastReport: SyncReport? = this.lastReport,
        lastCompleteAtMs: Long? = this.lastCompleteAtMs,
        leakLimitLitresPerMinute: Int? = this.leakLimitLitresPerMinute,
    ) = SyncState(machineTag, ledger, resumeFromMs, lastReport, lastCompleteAtMs, leakLimitLitresPerMinute)

    companion object {
        const val DEFAULT_LEAK_LIMIT = 24
        val EMPTY = SyncState(null, emptyMap(), null, null, null, DEFAULT_LEAK_LIMIT)
    }
}

/** The stored state exists and cannot be read. It is not thrown away: that would write every night again. */
class SyncStateUnreadableException(cause: Throwable) : IOException("the synchronisation record cannot be read", cause)

/**
 * Keeps [SyncState] in one small file, replaced whole and atomically on every
 * change: a crash leaves either the old state or the new one, never a mixture.
 *
 * Losing this file is survivable (record ids come from the machine's own time
 * stamps, so everything is simply written again over itself), but a state that
 * cannot be read is reported, not replaced, so that damage is not papered over.
 */
class SyncStore(private val file: File) {
    /** @throws SyncStateUnreadableException if the file exists and is not a state this version wrote. */
    fun read(): SyncState {
        if (!file.exists()) return SyncState.EMPTY
        return try {
            decode(Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw SyncStateUnreadableException(e)
        }
    }

    /** @throws IOException if the state could not be stored; the previous state is then still in place. */
    fun write(state: SyncState) {
        val directory = file.absoluteFile.parentFile ?: throw IOException("no directory for the synchronisation record")
        directory.mkdirs()
        val temporary = File(directory, file.name + ".new")
        val bytes = encode(state).toString().toByteArray(Charsets.UTF_8)
        FileChannel.open(temporary.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
            val buffer = java.nio.ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            // On disk before it replaces the old one, or a power cut could leave an empty file in its place.
            channel.force(true)
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    fun delete() {
        file.delete()
        File(file.absoluteFile.parentFile, file.name + ".new").delete()
    }

    internal companion object {
        private const val FORMAT = 1L

        fun encode(state: SyncState): JsonObject = buildJsonObject {
            put("format", FORMAT)
            state.machineTag?.let { put("machineTag", it) }
            state.resumeFromMs?.let { put("resumeFromMs", it) }
            state.lastCompleteAtMs?.let { put("lastCompleteAtMs", it) }
            // An explicit "none" so that an absent field can mean "the default", for files written before the setting existed.
            put("leakLimit", state.leakLimitLitresPerMinute?.toLong() ?: -1L)
            state.lastReport?.let { report ->
                put(
                    "lastReport",
                    buildJsonObject {
                        put("atMs", report.atMs)
                        put("outcome", report.outcome.name)
                        put("sessions", report.sessions)
                        put("readingsWritten", report.readingsWritten)
                    },
                )
            }
            put(
                "ledger",
                buildJsonArray {
                    for (entry in state.ledger.values.sortedBy { it.firstMinuteEnd }) {
                        add(
                            buildJsonObject {
                                put("tag", entry.machineTag)
                                put("first", entry.firstMinuteEnd.epochMs)
                                put("minutes", ranges(entry.minutes))
                                put("fingerprint", entry.fingerprint)
                                put("offsetMs", entry.clockOffset.millis)
                                put("version", entry.version)
                            },
                        )
                    }
                },
            )
        }

        fun decode(json: JsonObject): SyncState {
            require(json.getValue("format").jsonPrimitive.long == FORMAT) { "written by another version" }
            val entries = (json["ledger"] as? JsonArray).orEmpty().map { element ->
                val entry = element.jsonObject
                LedgerEntry(
                    machineTag = entry.getValue("tag").jsonPrimitive.content,
                    firstMinuteEnd = MachineTime(entry.getValue("first").jsonPrimitive.long),
                    minutes = minutes(entry.getValue("minutes").jsonPrimitive.content),
                    fingerprint = entry.getValue("fingerprint").jsonPrimitive.content,
                    clockOffset = ClockOffset.restored(entry.getValue("offsetMs").jsonPrimitive.long),
                    version = entry.getValue("version").jsonPrimitive.long,
                )
            }
            val ledger = LinkedHashMap<String, LedgerEntry>()
            for (entry in entries) require(ledger.put(entry.key, entry) == null) { "a session is listed twice" }
            val leak = (json["leakLimit"] as? JsonPrimitive)?.longOrNull
            return SyncState(
                machineTag = (json["machineTag"] as? JsonPrimitive)?.content,
                ledger = ledger,
                resumeFromMs = (json["resumeFromMs"] as? JsonPrimitive)?.long,
                lastReport = (json["lastReport"] as? JsonObject)?.let { report ->
                    SyncReport(
                        atMs = report.getValue("atMs").jsonPrimitive.long,
                        outcome = SyncOutcome.valueOf(report.getValue("outcome").jsonPrimitive.content),
                        sessions = report.getValue("sessions").jsonPrimitive.long.toInt(),
                        readingsWritten = report.getValue("readingsWritten").jsonPrimitive.long.toInt(),
                    )
                },
                lastCompleteAtMs = (json["lastCompleteAtMs"] as? JsonPrimitive)?.long,
                leakLimitLitresPerMinute = when {
                    leak == null -> SyncState.DEFAULT_LEAK_LIMIT
                    leak < 0 -> null
                    else -> leak.toInt()
                },
            )
        }

        /** Minute numbers as runs, "0-122,125-130": a night is one or two runs, not four hundred numbers. */
        fun ranges(minutes: List<Int>): String {
            val out = StringBuilder()
            var index = 0
            while (index < minutes.size) {
                var end = index
                while (end + 1 < minutes.size && minutes[end + 1] == minutes[end] + 1) end++
                if (out.isNotEmpty()) out.append(',')
                out.append(minutes[index])
                if (end > index) out.append('-').append(minutes[end])
                index = end + 1
            }
            return out.toString()
        }

        fun minutes(ranges: String): List<Int> {
            if (ranges.isEmpty()) return emptyList()
            val out = ArrayList<Int>()
            for (run in ranges.split(',')) {
                val ends = run.split('-')
                require(ends.size in 1..2) { "not a run of minutes" }
                val first = ends[0].toInt()
                val last = ends.last().toInt()
                require(first in 0..last && last - first < MAX_MINUTES) { "not a run of minutes" }
                for (minute in first..last) out += minute
                require(out.size <= MAX_MINUTES) { "too many minutes for one session" }
            }
            return out
        }

        /** A week of minutes: far more than a session can hold, and a bound on what a damaged file can ask for. */
        private const val MAX_MINUTES = 7 * 24 * 60
    }
}
