// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.sync

import com.everysoft.paprelay.core.MachineTime
import com.everysoft.paprelay.core.nights.BreathingEvent
import com.everysoft.paprelay.core.nights.BreathingEventKind
import com.everysoft.paprelay.core.nights.Night
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.util.Collections

/**
 * The breathing events of one machine, oldest first, each once.
 *
 * @property resumeFromMs a machine time before which every event is known to have been read, or
 *   null if none has been yet. The next run reads from a day before it.
 * @property kindsVersion the [com.everysoft.paprelay.as11.plan.EventPlan.VERSION] (the kinds passed
 *   on) under which the whole history was last read, or null if it has not been. Under any other,
 *   the history is read again once, so that a kind confirmed since is found in the nights before.
 */
class Events(val machineTag: String?, events: Collection<BreathingEvent>, val resumeFromMs: Long?, val kindsVersion: String? = null) {
    /** Oldest first; one for each [BreathingEvent.identity]. */
    val all: List<BreathingEvent> = Collections.unmodifiableList(
        events.associateBy { it.identity }.values.sortedWith(compareBy<BreathingEvent> { it.machineTime.epochMs }.thenBy { it.kind.key }.thenBy { it.durationMs ?: -1 }),
    )

    /** The events whose stamp falls in [night]'s day, newest first. */
    fun of(night: Night): List<BreathingEvent> = between(night.start.toInstant().toEpochMilli(), night.end.toInstant().toEpochMilli())

    /** The events stamped from [fromMs] up to but not including [untilMs], by the machine's clock, newest first. */
    fun between(fromMs: Long, untilMs: Long): List<BreathingEvent> {
        val first = firstAtOrAfter(fromMs)
        val end = firstAtOrAfter(untilMs)
        return if (end <= first) emptyList() else all.subList(first, end).asReversed()
    }

    private fun firstAtOrAfter(ms: Long): Int {
        var low = 0
        var high = all.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (all[middle].machineTime.epochMs < ms) low = middle + 1 else high = middle
        }
        return low
    }

    companion object {
        val EMPTY = Events(null, emptyList(), null)
    }
}

/**
 * Keeps the breathing events in one file, replaced whole and atomically, like [NightStore].
 *
 * A copy of what the machine holds, which the next download can build again as far back as the
 * machine remembers. A file that cannot be read, or that an earlier version wrote, is therefore
 * dropped and built again, not reported.
 */
class EventStore(private val file: File) {
    /** Never fails for want of a readable file: one that cannot be read is put aside and the list starts empty. */
    fun read(): Events {
        if (!file.exists()) return Events.EMPTY
        return try {
            decode(Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            runCatching { file.renameTo(File(file.absoluteFile.parentFile, "${file.name}.$DAMAGED")) }
            Events.EMPTY
        }
    }

    /** @throws IOException if it could not be stored; what was there before is then still in place. */
    fun write(events: Events) = AtomicFiles.write(file, encode(events).toString().toByteArray(Charsets.UTF_8))

    /** Removes the events and anything put aside: all of it is the user's health data. */
    fun delete() {
        file.delete()
        val directory = file.absoluteFile.parentFile ?: return
        directory.listFiles { candidate -> candidate.name.startsWith(file.name + ".") }?.forEach { it.delete() }
    }

    internal companion object {
        private const val FORMAT = 1L
        private const val DAMAGED = "damaged"
        private const val MAX_KINDS_VERSION = 400

        /**
         * A bound on what is kept, and on what a damaged file can ask to have built. A night with an
         * index of 30 an hour for eight hours has 240 events; this is three years of such nights.
         */
        const val MAX_EVENTS = 250_000

        /** One event as a line of its own: kind, stamp, length, corrected stamp; the last two may be empty. */
        private fun line(event: BreathingEvent): String =
            "${event.kind.key},${event.machineTime.epochMs},${event.durationMs ?: ""},${event.correctedMs ?: ""}"

        private fun event(line: String): BreathingEvent {
            val parts = line.split(',')
            require(parts.size == 4) { "not an event" }
            return BreathingEvent(
                kind = requireNotNull(BreathingEventKind.ofKey(parts[0])) { "not a kind of event" },
                machineTime = MachineTime(parts[1].toLong()),
                durationMs = parts[2].takeIf { it.isNotEmpty() }?.toLong(),
                correctedMs = parts[3].takeIf { it.isNotEmpty() }?.toLong(),
            )
        }

        fun encode(events: Events): JsonObject = buildJsonObject {
            put("format", FORMAT)
            events.machineTag?.let { put("machineTag", it) }
            events.resumeFromMs?.let { put("resumeFromMs", it) }
            events.kindsVersion?.let { put("kinds", it) }
            put("events", buildJsonArray { for (event in events.all) add(JsonPrimitive(line(event))) })
        }

        fun decode(json: JsonObject): Events {
            require(json.getValue("format").jsonPrimitive.long == FORMAT) { "written by another version" }
            val lines = json.getValue("events") as JsonArray
            require(lines.size <= MAX_EVENTS) { "too many events" }
            val events = lines.map { event(it.jsonPrimitive.content) }
            require(events.map { it.identity }.toSet().size == events.size) { "an event is listed twice" }
            val kinds = (json["kinds"] as? JsonPrimitive)?.content?.also { require(it.length <= MAX_KINDS_VERSION) { "not a version" } }
            return Events((json["machineTag"] as? JsonPrimitive)?.content, events, (json["resumeFromMs"] as? JsonPrimitive)?.long, kinds)
        }
    }
}
