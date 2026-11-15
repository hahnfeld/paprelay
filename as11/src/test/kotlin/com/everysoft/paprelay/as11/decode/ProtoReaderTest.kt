// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11.decode

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ProtoReaderTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `the documented timezone example decodes to minus 300 minutes`() {
        // From the format notes: field 4 of a Summary record for UTC-5 is `20 D7 04`.
        val field = ProtoReader.read(bytes(0x20, 0xD7, 0x04)).single()
        assertEquals(4, field.number)
        assertEquals(ProtoReader.VARINT, field.wireType)
        assertEquals(599, field.value)
        assertEquals(-300, field.signed)
    }

    @Test
    fun `each wire type is read`() {
        val message = bytes(
            0x08, 0x96, 0x01, // field 1 varint 150
            0x12, 0x03, 0x41, 0x42, 0x43, // field 2 bytes "ABC"
            0x1D, 0x78, 0x56, 0x34, 0x12, // field 3 fixed32
            0x21, 1, 2, 3, 4, 5, 6, 7, 8, // field 4 fixed64
            0x2A, 0x00, // field 5 empty bytes
        )
        val fields = ProtoReader.read(message)
        assertEquals(listOf(1, 2, 3, 4, 5), fields.map { it.number })
        assertEquals(150, fields[0].value)
        assertNull(fields[0].bytes)
        assertContentEquals("ABC".toByteArray(), fields[1].bytes)
        assertEquals(0x12345678, fields[2].value)
        assertEquals(0x0807060504030201, fields[3].value)
        assertEquals(0, fields[4].bytes!!.size)
    }

    @Test
    fun `nested messages are read by reading the bytes again`() {
        val inner = bytes(0x08, 0x01, 0x10, 0x02)
        val outer = bytes(0x12, inner.size) + inner + bytes(0x12, inner.size) + inner
        val records = ProtoReader.read(outer).map { ProtoReader.read(it.bytes!!) }
        assertEquals(2, records.size)
        assertEquals(listOf(1L, 2L), records[1].map { it.value })
    }

    @Test
    fun `an empty message has no fields`() {
        assertEquals(0, ProtoReader.read(ByteArray(0)).size)
    }

    @Test
    fun `truncated and malformed input is refused, never read past its end`() {
        for (bad in listOf(
            bytes(0x08), // varint missing
            bytes(0x08, 0x80), // varint unterminated
            bytes(0x12, 0x05, 0x41), // length longer than what is there
            bytes(0x1D, 1, 2), // fixed32 cut short
            bytes(0x21, 1, 2, 3), // fixed64 cut short
            bytes(0x0B), // group start: unsupported
            bytes(0x00, 0x01), // field number zero
            bytes(0x12, 0xFF, 0xFF, 0xFF, 0xFF, 0x0F, 0x41), // huge length
            bytes(0x08) + ByteArray(11) { 0x80.toByte() }, // varint too long
        )) {
            assertFailsWith<SpoolDecodeException> { ProtoReader.read(bad) }
        }
        assertFailsWith<SpoolDecodeException> { ProtoReader.read(bytes(0x08, 0x01, 0x08, 0x01, 0x08, 0x01), maxFields = 2) }
    }

    @Test
    fun `arbitrary bytes either decode or fail cleanly`() {
        val random = Random(11)
        repeat(20_000) {
            val junk = ByteArray(random.nextInt(48)).also(random::nextBytes)
            try {
                ProtoReader.read(junk)
            } catch (e: SpoolDecodeException) {
                // expected for most inputs
            }
        }
    }
}
