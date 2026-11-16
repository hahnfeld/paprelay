// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdvertisementTest {
    private fun field(type: Int, data: ByteArray) = byteArrayOf((data.size + 1).toByte(), type.toByte()) + data
    private val flags = field(0x01, byteArrayOf(0x06))
    private val service = field(0x03, byteArrayOf(0x56, 0xFD.toByte()))
    private val name = field(0x09, "ResMed 123456".toByteArray())

    @Test
    fun `a well-formed payload is parsed`() {
        val ad = Advertisement.parse(flags + service + name)
        assertEquals("ResMed 123456", ad.localName)
        assertEquals(listOf(0xFD56), ad.serviceUuids16)
        assertFalse(ad.truncated)
        assertTrue(ad.looksLikeAirSense)
    }

    @Test
    fun `fields before a structure that is one byte short are kept`() {
        // The machine's own payload: a trailing 0x1B field claiming more than is there.
        val broken = byteArrayOf(0x08, 0x1B, 1, 2, 3, 4, 5, 6)
        val ad = Advertisement.parse(flags + service + name + broken)
        assertTrue(ad.truncated)
        assertEquals("ResMed 123456", ad.localName)
        assertTrue(ad.looksLikeAirSense)
    }

    @Test
    fun `a name hidden behind a broken structure is still recognised from the raw bytes`() {
        val broken = byteArrayOf(0x08, 0x1B, 1, 2, 3, 4, 5, 6)
        val ad = Advertisement.parse(flags + broken + name)
        assertNull(ad.localName)
        assertTrue(ad.looksLikeAirSense)
    }

    @Test
    fun `other devices are not mistaken for the machine`() {
        val ad = Advertisement.parse(flags + field(0x03, byteArrayOf(0x0F, 0x18)) + field(0x09, "Headphones".toByteArray()))
        assertFalse(ad.looksLikeAirSense)
        assertFalse(Advertisement.parse(ByteArray(0)).looksLikeAirSense)
        assertFalse(Advertisement.parse(ByteArray(62)).looksLikeAirSense)
    }

    @Test
    fun `the report form shows structure and hides anything identifying`() {
        val manufacturer = field(0xFF, byteArrayOf(0x3D, 0x02, 9, 8, 7, 6))
        val serviceData = field(0x16, byteArrayOf(0x56, 0xFD.toByte(), 1, 2, 3))
        val redacted = Advertisement.redactedHex(flags + service + name + manufacturer + serviceData)
        assertEquals(
            "020106" + "030356FD" + "0E09" + "xx".repeat(13) + "07FF" + "xx".repeat(6) + "0616" + "xx".repeat(5),
            redacted,
        )
    }

    @Test
    fun `nothing after the machine's short address structure is shown, even when it is not at the end`() {
        // As Android delivers it: the advertisement, whose trailing address structure is
        // one byte short, followed directly by the scan response carrying the name.
        val shortAddress = byteArrayOf(0x08, 0x1B, 0, 0x11, 0x22, 0x33, 0x44, 0x55)
        val redacted = Advertisement.redactedHex(flags + service + shortAddress + name)
        assertEquals("020106" + "030356FD" + "081B" + "xx".repeat(6 + name.size), redacted)
        for (secret in listOf("526573", "4D6564", "112233", "3132")) assertFalse(redacted.contains(secret), secret)

        // A well-formed address structure is blanked the same way.
        val address = field(0x1B, byteArrayOf(0, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66))
        assertEquals("020106" + "081B" + "xx".repeat(7 + name.size), Advertisement.redactedHex(flags + address + name))

        // A structure of a shown type but the wrong size is not what it claims to be, and is blanked too.
        assertEquals("020106" + "0401" + "xxxxxx", Advertisement.redactedHex(flags + field(0x01, byteArrayOf(0x52, 0x65, 0x73))))
        assertEquals("020106" + "040A" + "xxxxxx", Advertisement.redactedHex(flags + field(0x0A, byteArrayOf(0x52, 0x65, 0x73))))
        assertEquals("020106" + "0403" + "xxxxxx", Advertisement.redactedHex(flags + field(0x03, byteArrayOf(0x52, 0x65, 0x73))))
        assertEquals("020106" + "0B03" + "xx".repeat(10), Advertisement.redactedHex(flags + field(0x03, ByteArray(10) { 0x41 })))
        assertEquals("020106" + "050356FD0A18", Advertisement.redactedHex(flags + field(0x03, byteArrayOf(0x56, 0xFD.toByte(), 0x0A, 0x18))))

        // A name whose structure claims to be shorter than it is: the walk lands inside the name, and
        // what it finds there is not shown as if it were a header.
        val lying = byteArrayOf(0x05, 0x09) + "ResMed 123456".toByteArray()
        val shown = Advertisement.redactedHex(flags + lying)
        assertEquals("020106" + "0509" + "xx".repeat(13), shown)
        for (digits in listOf("6564", "3132", "3334", "3536")) assertFalse(shown.contains(digits), digits)

        // The size rule is per type: two bytes are too many for flags and for power, though fine for a service list.
        assertEquals("020106" + "0301" + "xxxx", Advertisement.redactedHex(flags + field(0x01, byteArrayOf(0x52, 0x65))))
        assertEquals("020106" + "030A" + "xxxx", Advertisement.redactedHex(flags + field(0x0A, byteArrayOf(0x52, 0x65))))

        // Each kind of content is shown once, so a payload cannot spell a name one small structure at a time.
        val spelt = flags + field(0x0A, byteArrayOf(0x52)) + field(0x0A, byteArrayOf(0x65)) + field(0x01, byteArrayOf(0x73)) +
            field(0x03, byteArrayOf(0x56, 0xFD.toByte())) + field(0x02, byteArrayOf(0x4D, 0x65)) + field(0x03, byteArrayOf(0x64, 0x20))
        assertEquals("020106" + "020A52" + "020Axx" + "0201xx" + "030356FD" + "0302xxxx" + "0303xxxx", Advertisement.redactedHex(spelt))

        // A long (extended) advertisement is described up to the length of an ordinary one, and no further.
        val long = flags + field(0xFF, ByteArray(200) { 0x41 })
        val described = Advertisement.redactedHex(long)
        assertEquals("020106" + "C9FF" + "xx".repeat(62 - 5) + "(+${long.size - 62} bytes)", described)
        assertFalse(described.contains("41"))

        // And so is a structure that runs off the end.
        assertEquals("020106" + "0909" + "xx".repeat(3), Advertisement.redactedHex(flags + byteArrayOf(0x09, 0x09, 0x52, 0x65, 0x73)))
    }

    @Test
    fun `no input can make the report form reveal a name`() {
        // Slide a name through every position after a short address structure of every plausible length.
        val needle = "5265734D6564" // "ResMed"
        for (claimed in 2..12) {
            for (present in 0 until claimed) {
                val trailer = byteArrayOf(claimed.toByte(), 0x1B) + ByteArray(present) { 0x41 }
                assertFalse(Advertisement.redactedHex(flags + trailer + name).contains(needle), "claimed=$claimed present=$present")
            }
        }
    }

    @Test
    fun `padding ends parsing and is flagged, as in the reference`() {
        assertTrue(Advertisement.parse(flags + ByteArray(8)).truncated)
        assertEquals("020106" + "00" + "xx".repeat(7), Advertisement.redactedHex(flags + ByteArray(8)))
    }

    @Test
    fun `arbitrary bytes never throw`() {
        val random = Random(3)
        repeat(5_000) {
            val junk = ByteArray(random.nextInt(70)).also(random::nextBytes)
            Advertisement.parse(junk).looksLikeAirSense
            // Every input byte is accounted for: shown, blanked, or counted past the first 62.
            val described = Advertisement.redactedHex(junk)
            if (junk.size <= 62) {
                assertEquals(junk.size * 2, described.length)
            } else {
                assertEquals("(+${junk.size - 62} bytes)", described.substring(124))
            }
        }
    }
}
