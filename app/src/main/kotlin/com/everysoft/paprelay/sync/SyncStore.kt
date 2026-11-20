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

    /**
     * The phone's own clock disagrees with the time its network gives it. Every time written
     * is worked out from the phone's clock, and is kept for good, so nothing is written.
     */
    PHONE_CLOCK_WRONG,
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

    /**
     * The machine's clock has been changed since the last synchronisation. Nights recorded
     * in between cannot be placed in time until the user says how to treat them.
     */
    CLOCK_CHANGED,

    /** No machine has been paired. */
    NOT_PAIRED,

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
    /**
     * For an attempt that failed: a label of what went wrong, for the saved report. Made the same
     * way as the device check's, so it holds no health value, serial number or address.
     */
    val code: String? = null,
    /**
     * True when a session was left unwritten because it may still be going on. Not stored: it
     * matters only to whoever ran the attempt, who may want to look again when it has ended.
     */
    val sessionInProgress: Boolean = false,
) {
    companion object {
        const val MAX_CODE = 600
    }
}

/**
 * The machine's clock as last measured: how far it was from true time, and what it read then.
 * The next measurement is compared with it to notice a clock that has been set in between.
 */
class ClockBaseline(val offsetMs: Long, val machineMs: Long)

/**
 * Everything the app remembers between synchronisations, apart from the pairing.
 *
 * @property machineTag the stable, non-identifying name the records of this machine are filed under.
 * @property ledger what Health Connect may hold, by session.
 * @property resumeFromMs a machine time before which every session is known to have been dealt
 *   with, or null if nothing has been yet.
 * @property leakLimitLitresPerMinute the leak above which a minute is left out; null (the
 *   default) for no limit.
 * @property clock the machine's clock at the last synchronisation that measured it.
 * @property rewriteRequested true when every session is to be written again whatever the
 *   ledger says. The next run turns it into a ledger whose every entry is marked as begun.
 * @property vouchedSoftware the machine software, by its full identifier, from which a long
 *   session has been read cleanly. Until there is one, short sessions are not written: a
 *   few minutes can be read "successfully" from a layout that is not the expected one, and
 *   a long session cannot.
 */
class SyncState(
    val machineTag: String?,
    val ledger: Map<String, LedgerEntry>,
    val resumeFromMs: Long?,
    val lastReport: SyncReport?,
    val lastCompleteAtMs: Long?,
    val leakLimitLitresPerMinute: Int?,
    val clock: ClockBaseline?,
    val rewriteRequested: Boolean,
    val vouchedSoftware: String? = null,
    /** The rules ([com.everysoft.paprelay.core.sync.SessionReadings.RULES_VERSION]) the nights behind [resumeFromMs] were last looked at under. */
    val rulesVersion: Int? = null,
    /** The machine software last seen, by its full identifier. */
    val softwareSeen: String? = null,
    /**
     * The machine's time when [softwareSeen] was first seen in place of another. Only a session
     * that began after it was certainly recorded by this software, and can vouch for it. Null
     * when no other software was ever seen: then nothing says which sessions are older.
     */
    val softwareSinceMs: Long? = null,
    /** Whether the machine's per-minute respiratory rate is written to Health Connect. Off unless switched on. */
    val respiratoryRate: Boolean = false,
    /** Whether each night's figures are written to the phone's health records, where the phone has them. */
    val healthRecords: Boolean = true,
    /** The file the user made for the table of nights, as the system names it, or null for none. */
    val exportFile: String? = null,
) {
    init {
        require((exportFile?.length ?: 0) <= MAX_FILE_NAME) { "not a file" }
        require(leakLimitLitresPerMinute == null || leakLimitLitresPerMinute in 0..MAX_LEAK_LIMIT) { "not a leak limit" }
        require((vouchedSoftware?.length ?: 0) <= MAX_IDENTIFIER && (softwareSeen?.length ?: 0) <= MAX_IDENTIFIER) { "not a software identifier" }
    }

    fun with(
        machineTag: String? = this.machineTag,
        ledger: Map<String, LedgerEntry> = this.ledger,
        resumeFromMs: Long? = this.resumeFromMs,
        lastReport: SyncReport? = this.lastReport,
        lastCompleteAtMs: Long? = this.lastCompleteAtMs,
        leakLimitLitresPerMinute: Int? = this.leakLimitLitresPerMinute,
        clock: ClockBaseline? = this.clock,
        rewriteRequested: Boolean = this.rewriteRequested,
        vouchedSoftware: String? = this.vouchedSoftware,
        rulesVersion: Int? = this.rulesVersion,
        softwareSeen: String? = this.softwareSeen,
        softwareSinceMs: Long? = this.softwareSinceMs,
        respiratoryRate: Boolean = this.respiratoryRate,
        healthRecords: Boolean = this.healthRecords,
        exportFile: String? = this.exportFile,
    ) = SyncState(
        machineTag, ledger, resumeFromMs, lastReport, lastCompleteAtMs, leakLimitLitresPerMinute, clock, rewriteRequested, vouchedSoftware,
        rulesVersion, softwareSeen, softwareSinceMs, respiratoryRate, healthRecords, exportFile,
    )

    /** Everything about a machine forgotten; what the user has chosen kept. */
    fun cleared(): SyncState = EMPTY.with(
        leakLimitLitresPerMinute = leakLimitLitresPerMinute,
        respiratoryRate = respiratoryRate,
        healthRecords = healthRecords,
        exportFile = exportFile,
    )

    /**
     * With another leak rule. Every night is then looked at again, not only the recent ones,
     * so that the whole record follows one rule.
     */
    fun withLeakLimit(litresPerMinute: Int?): SyncState = with(leakLimitLitresPerMinute = litresPerMinute).fromTheStart()

    /** With every session to be read again from as far back as the machine holds. */
    fun fromTheStart(): SyncState =
        with(resumeFromMs = null)

    companion object {
        val EMPTY = SyncState(null, emptyMap(), null, null, null, null, null, false)
        const val MAX_LEAK_LIMIT = 1_000
        const val MAX_IDENTIFIER = 128
        const val MAX_FILE_NAME = 2048
    }
}

/** The stored state exists and cannot be read. It is not thrown away: that would write every night again. */
class SyncStateUnreadableException(cause: Throwable) : IOException("the synchronisation record cannot be read", cause)

/**
 * Keeps [SyncState] in one small file, replaced whole and atomically on every
 * change: a crash leaves either the old state or the new one, never a mixture.
 *
 * Losing this file is survivable (record ids come from the machine's own time
 * stamps, so each night is found again, cleared and written again), but a state
 * that cannot be read is reported, not replaced, so that damage is not hidden.
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
        // The new name is on disk only once its directory is. Without this a power cut could bring
        // the previous state back, and with it a ledger that knows nothing of a write already made.
        // Not every file system lets a directory be opened for this; where it does not, nothing more can be done.
        runCatching { FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) } }
    }

    fun delete() {
        file.delete()
        File(file.absoluteFile.parentFile, file.name + ".new").delete()
    }

    internal companion object {
        private const val FORMAT = 2L

        fun encode(state: SyncState): JsonObject = buildJsonObject {
            put("format", FORMAT)
            state.machineTag?.let { put("machineTag", it) }
            state.resumeFromMs?.let { put("resumeFromMs", it) }
            state.lastCompleteAtMs?.let { put("lastCompleteAtMs", it) }
            state.leakLimitLitresPerMinute?.let { put("leakLimit", it) }
            state.clock?.let { clock ->
                put(
                    "clock",
                    buildJsonObject {
                        put("offsetMs", clock.offsetMs)
                        put("machineMs", clock.machineMs)
                    },
                )
            }
            if (state.rewriteRequested) put("rewriteRequested", true)
            state.vouchedSoftware?.let { put("vouchedSoftware", it) }
            state.rulesVersion?.let { put("rulesVersion", it) }
            state.softwareSeen?.let { put("softwareSeen", it) }
            state.softwareSinceMs?.let { put("softwareSinceMs", it) }
            put("respiratoryRate", state.respiratoryRate)
            if (!state.healthRecords) put("healthRecords", false)
            state.exportFile?.let { put("exportFile", it) }
            state.lastReport?.let { report ->
                put(
                    "lastReport",
                    buildJsonObject {
                        put("atMs", report.atMs)
                        put("outcome", report.outcome.name)
                        put("sessions", report.sessions)
                        put("readingsWritten", report.readingsWritten)
                        report.code?.let { put("code", it) }
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
                                put("zone", entry.zone.id)
                                put("version", entry.version)
                                entry.pendingMinutes?.let { put("pending", ranges(it)) }
                            },
                        )
                    }
                },
            )
        }

        fun decode(json: JsonObject): SyncState {
            require(json.getValue("format").jsonPrimitive.long == FORMAT) { "written by another version" }
            val entries = (json.getValue("ledger") as JsonArray).map { element ->
                val entry = element.jsonObject
                LedgerEntry(
                    machineTag = entry.getValue("tag").jsonPrimitive.content,
                    firstMinuteEnd = MachineTime(entry.getValue("first").jsonPrimitive.long),
                    minutes = minutes(entry.getValue("minutes").jsonPrimitive.content),
                    fingerprint = entry.getValue("fingerprint").jsonPrimitive.content,
                    clockOffset = ClockOffset.restored(entry.getValue("offsetMs").jsonPrimitive.long),
                    zone = java.time.ZoneId.of(entry.getValue("zone").jsonPrimitive.content),
                    version = entry.getValue("version").jsonPrimitive.long,
                    pendingMinutes = (entry["pending"] as? JsonPrimitive)?.content?.let(::minutes),
                )
            }
            val machineTag = (json["machineTag"] as? JsonPrimitive)?.content
            val ledger = LinkedHashMap<String, LedgerEntry>()
            for (entry in entries) {
                require(entry.machineTag == machineTag) { "a session of another machine" }
                require(ledger.put(entry.key, entry) == null) { "a session is listed twice" }
            }
            return SyncState(
                machineTag = machineTag,
                ledger = ledger,
                resumeFromMs = (json["resumeFromMs"] as? JsonPrimitive)?.long,
                lastReport = (json["lastReport"] as? JsonObject)?.let { report ->
                    SyncReport(
                        atMs = report.getValue("atMs").jsonPrimitive.long,
                        outcome = SyncOutcome.valueOf(report.getValue("outcome").jsonPrimitive.content),
                        sessions = report.getValue("sessions").jsonPrimitive.long.toInt(),
                        readingsWritten = report.getValue("readingsWritten").jsonPrimitive.long.toInt(),
                        code = (report["code"] as? JsonPrimitive)?.content?.take(SyncReport.MAX_CODE),
                    )
                },
                lastCompleteAtMs = (json["lastCompleteAtMs"] as? JsonPrimitive)?.long,
                leakLimitLitresPerMinute = (json["leakLimit"] as? JsonPrimitive)?.long?.let {
                    require(it in 0..SyncState.MAX_LEAK_LIMIT) { "not a leak limit" }
                    it.toInt()
                },
                clock = (json["clock"] as? JsonObject)?.let { ClockBaseline(it.getValue("offsetMs").jsonPrimitive.long, it.getValue("machineMs").jsonPrimitive.long) },
                rewriteRequested = (json["rewriteRequested"] as? JsonPrimitive)?.content == "true",
                vouchedSoftware = (json["vouchedSoftware"] as? JsonPrimitive)?.content,
                rulesVersion = (json["rulesVersion"] as? JsonPrimitive)?.long?.toInt(),
                softwareSeen = (json["softwareSeen"] as? JsonPrimitive)?.content,
                softwareSinceMs = (json["softwareSinceMs"] as? JsonPrimitive)?.long,
                // A state from before this was a choice wrote respiratory rate always. If it wrote any, it goes on doing so.
                respiratoryRate = (json["respiratoryRate"] as? JsonPrimitive)?.content?.let { it == "true" } ?: ledger.isNotEmpty(),
                healthRecords = (json["healthRecords"] as? JsonPrimitive)?.content != "false",
                exportFile = (json["exportFile"] as? JsonPrimitive)?.content,
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
