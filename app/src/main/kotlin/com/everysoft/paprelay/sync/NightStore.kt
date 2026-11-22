// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.sync

import com.everysoft.paprelay.core.MachineTime
import com.everysoft.paprelay.core.nights.Night
import com.everysoft.paprelay.core.nights.NightFigure
import com.everysoft.paprelay.core.nights.NightSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate

/**
 * A night as kept on the phone.
 *
 * @property recorded what was last written to the phone's health records for this night: the
 *   format of the records and the night's [Night.fingerprint], or null if nothing has been.
 *   A night for which either differs is written again.
 */
class StoredNight(val night: Night, val recorded: String?)

/**
 * The nights of one machine, by the date each began.
 *
 * @property planVersion the [com.everysoft.paprelay.as11.plan.NightPlan.VERSION] under which the
 *   machine's whole history was last read for these nights, or null if it has not been. Under any
 *   other than the current one (a value was confirmed, say), the history is read again once, so
 *   that older nights gain the figures they lacked.
 */
class Nights(val machineTag: String?, val byDate: Map<LocalDate, StoredNight>, val planVersion: String? = null) {
    /** Newest first. */
    val all: List<Night> get() = byDate.values.map { it.night }.sortedByDescending { it.date }

    companion object {
        val EMPTY = Nights(null, emptyMap())
    }
}

/** The file of nights exists and is not one this version can read. */
class NightsUnreadableException(cause: Throwable) : IOException("the nights cannot be read", cause)

/**
 * Keeps the nights in one small file, replaced whole and atomically.
 *
 * This is a copy of what the machine holds, and can be built again from the next download
 * as far back as the machine remembers. A file that cannot be read is therefore not a
 * disaster, but it is not the same as no file: it is reported, and [setAside] keeps it
 * under another name instead of writing over it.
 */
class NightStore(private val file: File) {
    /** @throws NightsUnreadableException if the file exists and is not one this version wrote. */
    fun read(): Nights {
        if (!file.exists()) return Nights.EMPTY
        return try {
            val json = Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
            if (olderFormat(json)) {
                // Kept by a version before 0.3.0. Nothing in it is needed: the nights are built again
                // from the machine, and their records written again in the new form. Not damage.
                file.delete()
                return Nights.EMPTY
            }
            decode(json)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw NightsUnreadableException(e)
        }
    }

    /**
     * Moves the file out of the way under a name ending in [suffix], replacing an older one set
     * aside the same way: for a file that cannot be read, or the nights of a machine that has
     * been replaced. They are no longer shown, and are not thrown away.
     */
    fun setAside(suffix: String) {
        if (!file.exists()) return
        runCatching {
            Files.move(file.toPath(), File(file.absoluteFile.parentFile, "${file.name}.$suffix").toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** @throws IOException if it could not be stored; what was there before is then still in place. */
    fun write(nights: Nights) = AtomicFiles.write(file, encode(nights).toString().toByteArray(Charsets.UTF_8))

    /** Removes the nights and everything that was ever put aside: all of it is the user's health data. */
    fun delete() {
        file.delete()
        val directory = file.absoluteFile.parentFile ?: return
        directory.listFiles { candidate -> candidate.name.startsWith(file.name + ".") }?.forEach { it.delete() }
    }

    internal companion object {
        /**
         * 3 (0.3.0): the sessions one by one, the plan version, flows in litres a minute and tidal
         * volume in millilitres. A file of an earlier format is treated as absent (see [read]): its
         * hundredths may be of other units, and the nights are built again from the machine's history.
         */
        private const val FORMAT = 3L

        /** Far above any version identifier [com.everysoft.paprelay.as11.plan.NightPlan.VERSION] gives. */
        private const val MAX_PLAN_VERSION = 64

        /** More than three years of days: a bound on what a damaged file can ask to have built. */
        private const val MAX_NIGHTS = 1200

        fun encode(nights: Nights): JsonObject = buildJsonObject {
            put("format", FORMAT)
            nights.machineTag?.let { put("machineTag", it) }
            nights.planVersion?.let { put("planVersion", it) }
            put(
                "nights",
                buildJsonArray {
                    for (stored in nights.byDate.values.sortedBy { it.night.date }) {
                        val night = stored.night
                        add(
                            buildJsonObject {
                                put("date", night.date.toString())
                                put("zone", night.zoneOffsetMinutes)
                                put("usage", night.usageMinutes)
                                put("sessions", night.sessions)
                                if (night.hours != 24) put("hours", night.hours)
                                if (!night.complete) put("running", true)
                                put("figures", buildJsonObject { for ((figure, value) in night.hundredths) put(figure.key, value) })
                                night.sessionList?.let { list ->
                                    put(
                                        "sessionList",
                                        buildJsonArray {
                                            for (session in list) {
                                                add(
                                                    buildJsonArray {
                                                        add(JsonPrimitive(session.start.epochMs))
                                                        add(JsonPrimitive(session.minutes))
                                                        session.correctedMs?.let { add(JsonPrimitive(it)) }
                                                    },
                                                )
                                            }
                                        },
                                    )
                                }
                                stored.recorded?.let { put("recorded", it) }
                            },
                        )
                    }
                },
            )
        }

        /** A file a version before this one wrote, in a format of its own (which is a number below [FORMAT]). */
        fun olderFormat(json: JsonObject): Boolean = (json["format"] as? JsonPrimitive)?.content?.toLongOrNull()?.let { it in 1 until FORMAT } == true

        fun decode(json: JsonObject): Nights {
            require(json.getValue("format").jsonPrimitive.long == FORMAT) { "written by another version" }
            val entries = json.getValue("nights") as JsonArray
            require(entries.size <= MAX_NIGHTS) { "too many nights" }
            val byDate = LinkedHashMap<LocalDate, StoredNight>()
            for (element in entries) {
                val entry = element.jsonObject
                val figures = LinkedHashMap<NightFigure, Long>()
                for ((key, value) in entry.getValue("figures").jsonObject) {
                    // A figure a later version added and this one does not know is left out, not an error.
                    NightFigure.ofKey(key)?.let { figures[it] = value.jsonPrimitive.long }
                }
                val night = Night(
                    date = LocalDate.parse(entry.getValue("date").jsonPrimitive.content),
                    zoneOffsetMinutes = entry.getValue("zone").jsonPrimitive.int,
                    usageMinutes = entry.getValue("usage").jsonPrimitive.int,
                    sessions = entry.getValue("sessions").jsonPrimitive.int,
                    hundredths = figures,
                    complete = (entry["running"] as? JsonPrimitive)?.content != "true",
                    hours = (entry["hours"] as? JsonPrimitive)?.int ?: 24,
                    sessionList = (entry["sessionList"] as? JsonArray)?.let { list ->
                        require(list.size <= Night.MAX_SESSIONS) { "too many sessions" }
                        list.map { pair ->
                            val parts = (pair as JsonArray).also { require(it.size in 2..3) { "not a session" } }
                            NightSession(MachineTime(parts[0].jsonPrimitive.long), parts[1].jsonPrimitive.int, parts.getOrNull(2)?.jsonPrimitive?.long)
                        }
                    },
                )
                val recorded = (entry["recorded"] as? JsonPrimitive)?.content?.also { require(FINGERPRINT.matches(it)) { "not a fingerprint" } }
                require(byDate.put(night.date, StoredNight(night, recorded)) == null) { "a night is listed twice" }
            }
            val planVersion = (json["planVersion"] as? JsonPrimitive)?.content?.also { require(it.length <= MAX_PLAN_VERSION) { "not a plan version" } }
            return Nights((json["machineTag"] as? JsonPrimitive)?.content, byDate, planVersion)
        }

        /**
         * The format of the records it was written in, then the night's fingerprint, then the mark of the
         * person's details, if any: 32 digits, or 16 as 0.4.1-test wrote them (which no longer match, and are written again).
         */
        private val FINGERPRINT = Regex("[0-9]{1,6}:[0-9a-f]{64}(:([0-9a-f]{16}){1,2})?")
    }
}
