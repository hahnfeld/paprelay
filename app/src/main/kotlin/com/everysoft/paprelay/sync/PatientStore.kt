// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.sync

import com.everysoft.paprelay.core.nights.PatientDetails
import com.everysoft.paprelay.secure.SecretKeys
import com.everysoft.paprelay.secure.Sealer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.time.LocalDate

/** What is known about the stored name and date of birth. Never says what they are, even in [toString]. */
sealed class PatientRecord {
    /** None were given, or they were removed. */
    data object Absent : PatientRecord()

    /**
     * The details, and [mark]: a random value made at each Save and kept with them, which the health
     * records are marked with, so that a change writes them again. Being random, it tells nothing of the details.
     */
    class Loaded(val details: PatientDetails, val mark: String) : PatientRecord() {
        override fun toString() = "Loaded"
    }

    /** They were stored and can no longer be read: the key was lost, or the file is damaged. They are to be entered again. */
    data object Unreadable : PatientRecord()

    /** Storage could not be read just now. Nothing is known to be lost. */
    data object Unavailable : PatientRecord()
}

/**
 * Keeps the person's name and date of birth, when they have given them, encrypted with AES-256-GCM
 * under a key of the phone's secure hardware ([keys]), in one file of the app's own that is not backed
 * up, replaced whole and atomically. They belong to the person, not to the machine: forgetting the
 * machine leaves them, and only the person's own Remove takes them, and the key, away.
 *
 * The key needs no user authentication, as the pairing's does not: the Patient record is written by
 * synchronisations in the background, while the phone is locked. Nothing here is ever logged or put
 * in a message.
 */
class PatientStore(private val file: File, private val keys: SecretKeys) {
    private val sealer = Sealer(keys, AAD)

    /** What is stored. Never throws. */
    fun read(): PatientRecord {
        if (!file.exists()) return PatientRecord.Absent
        val blob = try {
            file.readBytes()
        } catch (e: IOException) {
            return if (file.exists()) PatientRecord.Unavailable else PatientRecord.Absent
        }
        if (blob.size < 1 + Sealer.OVERHEAD || blob[0] != FORMAT) return PatientRecord.Unreadable
        val plain = try {
            // A file whose key is gone does not come back.
            sealer.open(blob, 1) ?: return PatientRecord.Unreadable
        } catch (e: Exception) {
            // Only a key that is gone for good, or bytes that fail their own check, mean the details are lost.
            // Anything else may be the keystore having a bad moment.
            return if (Sealer.isPermanentKeyLoss(e) || e is IllegalArgumentException) PatientRecord.Unreadable else PatientRecord.Unavailable
        }
        return try {
            val json = Json.parseToJsonElement(plain.toString(Charsets.UTF_8)).jsonObject
            val mark = json.getValue("mark").jsonPrimitive.content
            require(MARK.matches(mark)) { "not a mark" }
            PatientRecord.Loaded(
                PatientDetails(
                    (json.getValue("given") as JsonArray).map { it.jsonPrimitive.content },
                    json.getValue("family").jsonPrimitive.content,
                    LocalDate.parse(json.getValue("birthDate").jsonPrimitive.content),
                ),
                mark,
            )
        } catch (e: Exception) {
            // Whatever the content is, it stays out of every message.
            PatientRecord.Unreadable
        } finally {
            plain.fill(0)
        }
    }

    /**
     * Stores [details] with a new random mark, so that even the same details saved again write the
     * health records again.
     *
     * @throws IOException if they could not be stored. The details stored before are then still in
     * place and readable, with one exception: a key found to be gone for good (which had already made
     * them unreadable) has been replaced.
     */
    fun write(details: PatientDetails) {
        val mark = ByteArray(MARK_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val plain = buildJsonObject {
            put("given", buildJsonArray { for (name in details.given) add(JsonPrimitive(name)) })
            put("family", details.family)
            put("birthDate", details.birthDate.toString())
            put("mark", mark)
        }.toString().toByteArray(Charsets.UTF_8)
        try {
            val sealed = try {
                sealer.seal(plain)
            } catch (e: Exception) {
                // A key that may only be having a bad moment is kept: it may still open what is stored.
                if (!Sealer.isPermanentKeyLoss(e)) throw IOException("the details could not be encrypted", e)
                keys.delete()
                try {
                    sealer.seal(plain)
                } catch (again: Exception) {
                    throw IOException("the details could not be encrypted", again)
                }
            }
            AtomicFiles.write(file, byteArrayOf(FORMAT) + sealed)
        } finally {
            plain.fill(0)
        }
    }

    /** Removes the file and its key. @throws IOException if the file is still there. */
    fun delete() {
        File(file.absoluteFile.parentFile, file.name + ".new").delete()
        val gone = file.delete() || !file.exists()
        // Without the key, any copy of the file that may linger can never be read.
        keys.delete()
        if (!gone) throw IOException("not removed")
    }

    internal companion object {
        /** 2 (0.4.1): with the mark. 1 was written by 0.4.1-test only; it is unreadable, and asked for again. */
        const val FORMAT: Byte = 2
        const val MARK_BYTES = 16
        val MARK = Regex("[0-9a-f]{32}")
        private val random = SecureRandom()
        const val KEY_ALIAS = "paprelay.patient.v1"

        /** What the bytes are for and in which format: sealed bytes of anything else are not taken for them. */
        val AAD = "$KEY_ALIAS format $FORMAT".toByteArray(Charsets.US_ASCII)
    }
}
