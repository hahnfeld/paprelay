// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.sync

import com.everysoft.paprelay.check.DeviceCheck
import com.everysoft.paprelay.core.nights.PatientDetails
import com.everysoft.paprelay.secure.MemoryKeys
import com.everysoft.paprelay.secure.Sealer
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The name and date of birth: encrypted at rest, and never in a message. All of it made up. */
class PatientStoreTest {
    private val testy = PatientDetails(listOf("Testy", "Example"), "McTestface", LocalDate.of(1970, 1, 2))
    private val directory: File = Files.createTempDirectory("paprelay-patient").toFile()
    private val file = File(directory, "patient.bin")
    private val keys = MemoryKeys()
    private val store = PatientStore(file, keys)

    private fun leaks(text: String) = "Testy" in text || "Example" in text || "McTestface" in text || "1970" in text

    @Test
    fun `details stored are read back as they were, and the file holds none of them in the clear`() {
        assertEquals(PatientRecord.Absent, store.read())
        store.write(testy)
        val read = store.read()
        assertIs<PatientRecord.Loaded>(read)
        assertEquals(testy, read.details)
        val bytes = file.readBytes()
        assertEquals(PatientStore.FORMAT, bytes[0])
        assertFalse(leaks(bytes.toString(Charsets.ISO_8859_1)))
        // A new IV each time: the same details never give the same bytes.
        store.write(testy)
        val again = file.readBytes()
        assertFalse(bytes.copyOfRange(1, 1 + Sealer.IV_BYTES).contentEquals(again.copyOfRange(1, 1 + Sealer.IV_BYTES)))
        assertFalse(File(directory, "patient.bin.new").exists())
    }

    @Test
    fun `changed bytes are unreadable, not wrong details`() {
        store.write(testy)
        val bytes = file.readBytes()
        for (at in listOf(1, 1 + Sealer.IV_BYTES, bytes.size - 1)) {
            val changed = bytes.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }
            file.writeBytes(changed)
            assertEquals(PatientRecord.Unreadable, store.read(), "byte $at")
        }
        file.writeBytes(bytes.copyOf(bytes.size - 1))
        assertEquals(PatientRecord.Unreadable, store.read())
        file.writeBytes(bytes.copyOf(1 + Sealer.OVERHEAD - 1))
        assertEquals(PatientRecord.Unreadable, store.read())
        // The format before (0.4.1-test), or any other.
        for (format in listOf<Byte>(1, 3)) {
            file.writeBytes(byteArrayOf(format) + bytes.copyOfRange(1, bytes.size))
            assertEquals(PatientRecord.Unreadable, store.read())
        }
        file.writeBytes(bytes)
        assertIs<PatientRecord.Loaded>(store.read())
    }

    @Test
    fun `bytes sealed for another purpose are not taken for the details`() {
        store.write(testy)
        val plain = """{"given":["Testy"],"family":"McTestface","birthDate":"1970-01-02","mark":"${"0".repeat(32)}"}""".toByteArray()
        val other = Sealer(keys, "paprelay.pairing.v1".toByteArray(Charsets.US_ASCII)).seal(plain)
        file.writeBytes(byteArrayOf(PatientStore.FORMAT) + other)
        assertEquals(PatientRecord.Unreadable, store.read())
        // The same purpose sealed the same way is read: it is the binding that refused, not the sealing.
        file.writeBytes(byteArrayOf(PatientStore.FORMAT) + Sealer(keys, PatientStore.AAD).seal(plain))
        assertIs<PatientRecord.Loaded>(store.read())
    }

    @Test
    fun `a lost key makes the details unreadable, and they can be entered again`() {
        store.write(testy)
        keys.key = null
        assertEquals(PatientRecord.Unreadable, store.read())
        store.write(testy)
        assertEquals(testy, (store.read() as PatientRecord.Loaded).details)
    }

    @Test
    fun `a keystore that fails for a moment loses nothing`() {
        store.write(testy)
        keys.failing = KeyStoreException("busy")
        assertEquals(PatientRecord.Unavailable, store.read())
        keys.failing = null
        assertIs<PatientRecord.Loaded>(store.read())
    }

    @Test
    fun `content that is sealed but not details is unreadable`() {
        val sealer = Sealer(keys, PatientStore.AAD)
        for (content in listOf(
            """{"given":[],"family":"","birthDate":"x","mark":"${"0".repeat(32)}"}""",
            // Without a mark, as 0.4.1-test wrote them, or with one that is not a mark.
            """{"given":["Testy"],"family":"McTestface","birthDate":"1970-01-02"}""",
            """{"given":["Testy"],"family":"McTestface","birthDate":"1970-01-02","mark":"Testy"}""",
        )) {
            file.writeBytes(byteArrayOf(PatientStore.FORMAT) + sealer.seal(content.toByteArray()))
            assertEquals(PatientRecord.Unreadable, store.read(), content)
        }
    }

    @Test
    fun `each save gets a new random mark, which says nothing of the details`() {
        store.write(testy)
        val first = (store.read() as PatientRecord.Loaded).mark
        store.write(testy)
        val second = (store.read() as PatientRecord.Loaded).mark
        assertTrue(PatientStore.MARK.matches(first) && PatientStore.MARK.matches(second))
        assertNotEquals(first, second)
        assertFalse(leaks(first))
        // Sealed with the details, never in the clear.
        assertFalse(second in file.readBytes().toString(Charsets.ISO_8859_1))
    }

    @Test
    fun `a key that fails for a moment is kept, and what was stored stays readable`() {
        store.write(testy)
        val before = file.readBytes()
        keys.failing = KeyStoreException("busy")
        assertFailsWith<IOException> { store.write(PatientDetails(listOf("Other"), "Name", LocalDate.of(1980, 5, 6))) }
        assertEquals(0, keys.deletes)
        assertContentEquals(before, file.readBytes())
        keys.failing = null
        assertEquals(testy, (store.read() as PatientRecord.Loaded).details)
    }

    @Test
    fun `a key that is gone for good is replaced, and the details are stored under the new one`() {
        store.write(testy)
        // Invalidated: what it protected could not be read any more anyway.
        keys.failing = UnrecoverableKeyException("invalidated")
        assertEquals(PatientRecord.Unreadable, store.read())
        val other = PatientDetails(listOf("Other"), "Name", LocalDate.of(1980, 5, 6))
        store.write(other)
        assertEquals(1, keys.deletes)
        assertEquals(other, (store.read() as PatientRecord.Loaded).details)
    }

    @Test
    fun `remove deletes the file and the key`() {
        store.write(testy)
        store.delete()
        assertFalse(file.exists())
        assertNull(keys.key)
        assertEquals(1, keys.deletes)
        assertEquals(PatientRecord.Absent, store.read())
        // Removing what is not there is no failure.
        store.delete()
        // Unreadable details can be removed too.
        store.write(testy)
        keys.key = null
        store.delete()
        assertFalse(file.exists())
    }

    @Test
    fun `a removal that leaves the file says so`() {
        file.mkdirs()
        File(file, "inside").writeText("x")
        assertFailsWith<IOException> { store.delete() }
    }

    @Test
    fun `nothing said about the details holds them`() {
        store.write(testy)
        val loaded = store.read()
        assertFalse(leaks(loaded.toString()))
        assertFalse(leaks(testy.toString()))
        assertFalse(leaks(com.everysoft.paprelay.core.nights.FhirObservations.patient(testy).toString()))
        // A failed write: what the report would say of it.
        val blocked = PatientStore(File(File(directory, "not-a-folder").apply { writeText("x") }, "patient.bin"), keys)
        val failure = assertFailsWith<IOException> { blocked.write(testy) }
        assertFalse(leaks(failure.toString()))
        for (messages in listOf(true, false)) {
            assertFalse(leaks(DeviceCheck.code(failure, messages)))
            // Even a failure whose own words name them is reported without them.
            val named = HealthSinkException(notAllowed = false, cause = IllegalStateException("Testy Example McTestface 1970-01-02"))
            assertFalse(leaks(DeviceCheck.code(named, messages)))
        }
        assertTrue(DeviceCheck.code(failure, messages = false).isNotEmpty())
    }
}
