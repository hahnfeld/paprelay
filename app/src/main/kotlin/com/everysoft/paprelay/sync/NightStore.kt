// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.sync

import com.everysoft.paprelay.core.nights.Night
import com.everysoft.paprelay.core.nights.NightFigure
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
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDate

/**
 * A night as kept on the phone.
 *
 * @property recorded the [Night.fingerprint] of what was last written to the phone's health
 *   records for this night, or null if nothing has been. A night whose fingerprint differs
 *   is written again.
 */
class StoredNight(val night: Night, val recorded: String?)

/** The nights of one machine, by the date each began. */
class Nights(val machineTag: String?, val byDate: Map<LocalDate, StoredNight>) {
    /** Newest first. */
    val all: List<Night> get() = byDate.values.map { it.night }.sortedByDescending { it.date }

    companion object {
        val EMPTY = Nights(null, emptyMap())
    }
}

/**
 * Keeps the nights in one small file, replaced whole and atomically.
 *
 * Unlike the record of what was written to Health Connect, this is only a copy of what the
 * machine holds: if the file is lost or cannot be read it is simply built again from the
 * next download, so a damaged file is treated as an empty one.
 */
class NightStore(private val file: File) {
    fun read(): Nights {
        if (!file.exists()) return Nights.EMPTY
        return try {
            decode(Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            Nights.EMPTY
        }
    }

    /** @throws IOException if it could not be stored; what was there before is then still in place. */
    fun write(nights: Nights) {
        val directory = file.absoluteFile.parentFile ?: throw IOException("no directory for the nights")
        directory.mkdirs()
        val temporary = File(directory, file.name + ".new")
        val bytes = encode(nights).toString().toByteArray(Charsets.UTF_8)
        FileChannel.open(temporary.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
            val buffer = java.nio.ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        runCatching { FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) } }
    }

    fun delete() {
        file.delete()
        File(file.absoluteFile.parentFile, file.name + ".new").delete()
    }

    internal companion object {
        private const val FORMAT = 1L

        /** More than three years of days: a bound on what a damaged file can ask to have built. */
        private const val MAX_NIGHTS = 1200

        fun encode(nights: Nights): JsonObject = buildJsonObject {
            put("format", FORMAT)
            nights.machineTag?.let { put("machineTag", it) }
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
                                if (!night.complete) put("running", true)
                                put("figures", buildJsonObject { for ((figure, value) in night.hundredths) put(figure.key, value) })
                                stored.recorded?.let { put("recorded", it) }
                            },
                        )
                    }
                },
            )
        }

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
                )
                val recorded = (entry["recorded"] as? JsonPrimitive)?.content?.also { require(FINGERPRINT.matches(it)) { "not a fingerprint" } }
                require(byDate.put(night.date, StoredNight(night, recorded)) == null) { "a night is listed twice" }
            }
            return Nights((json["machineTag"] as? JsonPrimitive)?.content, byDate)
        }

        private val FINGERPRINT = Regex("[0-9a-f]{64}")
    }
}
