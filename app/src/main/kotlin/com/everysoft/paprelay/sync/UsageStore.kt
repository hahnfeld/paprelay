// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.sync

import com.everysoft.paprelay.core.MachineTime
import com.everysoft.paprelay.core.nights.Night
import com.everysoft.paprelay.core.nights.NightPreview
import com.everysoft.paprelay.core.nights.UsageGap
import com.everysoft.paprelay.core.nights.UsageMark
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
 * The mask-on and mask-off marks the morning preview is made of: only those of the last night or
 * two, each once.
 *
 * @property resumeFromMs a machine time before which every mark is known to have been read, or null.
 * @property knownUntilMs the machine's clock when the log was last read to its end: a session with
 *   no mask-off yet is counted up to it. [knownUntilCorrectedMs] is the same in true time, where known.
 * @property gaps where the latest run could not read a record of the log.
 */
class Usage(
    val machineTag: String?,
    marks: Collection<UsageMark>,
    val resumeFromMs: Long?,
    val knownUntilMs: Long?,
    val knownUntilCorrectedMs: Long? = null,
    val gaps: List<UsageGap> = emptyList(),
) {
    /** Oldest first, one for each [UsageMark.identity]. */
    val marks: List<UsageMark> = Collections.unmodifiableList(marks.associateBy { it.identity }.values.sortedBy { it.machineTime.epochMs })

    /** The night after the newest of [nights], so far, as the screens show it; null for none. */
    fun preview(nights: Collection<Night>): NightPreview? = NightPreview.of(nights, marks, knownUntilMs, knownUntilCorrectedMs, gaps)

    companion object {
        val EMPTY = Usage(null, emptyList(), null, null)
    }
}

/**
 * Keeps [Usage] in one small file, replaced whole and atomically. It is a convenience for the
 * screen and nothing more: a file that cannot be read is dropped and read again from the machine,
 * and nothing else depends on it.
 */
class UsageStore(private val file: File) {
    /** Never fails for want of a readable file: one that cannot be read is dropped. */
    fun read(): Usage {
        if (!file.exists()) return Usage.EMPTY
        return try {
            decode(Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            file.delete()
            Usage.EMPTY
        }
    }

    /** @throws IOException if it could not be stored; what was there before is then still in place. */
    fun write(usage: Usage) = AtomicFiles.write(file, encode(usage).toString().toByteArray(Charsets.UTF_8))

    fun delete() {
        file.delete()
        File(file.absoluteFile.parentFile, file.name + ".new").delete()
    }

    internal companion object {
        private const val FORMAT = 1L

        /** Far more than two nights hold; a bound on what a damaged file can ask for. */
        const val MAX_MARKS = 2_000

        fun encode(usage: Usage): JsonObject = buildJsonObject {
            put("format", FORMAT)
            usage.machineTag?.let { put("machineTag", it) }
            usage.resumeFromMs?.let { put("resumeFromMs", it) }
            usage.knownUntilMs?.let { put("knownUntilMs", it) }
            usage.knownUntilCorrectedMs?.let { put("knownUntilCorrectedMs", it) }
            put("gaps", buildJsonArray { for (gap in usage.gaps) add(JsonPrimitive("${gap.afterMs ?: ""},${gap.beforeMs ?: ""}")) })
            put(
                "marks",
                buildJsonArray {
                    for (mark in usage.marks) add(JsonPrimitive("${if (mark.on) "on" else "off"},${mark.machineTime.epochMs},${mark.correctedMs ?: ""}"))
                },
            )
        }

        fun decode(json: JsonObject): Usage {
            require(json.getValue("format").jsonPrimitive.long == FORMAT) { "written by another version" }
            val lines = json.getValue("marks") as JsonArray
            require(lines.size <= MAX_MARKS) { "too many marks" }
            val marks = lines.map { element ->
                val parts = element.jsonPrimitive.content.split(',')
                require(parts.size == 3 && parts[0] in setOf("on", "off")) { "not a mark" }
                UsageMark(parts[0] == "on", MachineTime(parts[1].toLong()), parts[2].takeIf { it.isNotEmpty() }?.toLong())
            }
            val gaps = (json["gaps"] as? JsonArray).orEmpty().take(MAX_MARKS).map { element ->
                val parts = element.jsonPrimitive.content.split(',')
                require(parts.size == 2) { "not a gap" }
                UsageGap(parts[0].takeIf { it.isNotEmpty() }?.toLong(), parts[1].takeIf { it.isNotEmpty() }?.toLong())
            }
            return Usage(
                (json["machineTag"] as? JsonPrimitive)?.content,
                marks,
                (json["resumeFromMs"] as? JsonPrimitive)?.long,
                (json["knownUntilMs"] as? JsonPrimitive)?.long,
                (json["knownUntilCorrectedMs"] as? JsonPrimitive)?.long,
                gaps,
            )
        }
    }
}
