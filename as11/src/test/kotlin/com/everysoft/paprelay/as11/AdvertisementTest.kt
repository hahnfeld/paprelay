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
    fun `the report form hides names and addresses but keeps the structure`() {
        val address = field(0x1B, byteArrayOf(0, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66))
        val redacted = Advertisement.redactedHex(flags + service + name + address)
        assertEquals("020106" + "030356FD" + "0E09" + "xx".repeat(13) + "081B" + "xx".repeat(7), redacted)

        // The machine's own trailer runs off the end; what is there of it is hidden too.
        val broken = byteArrayOf(0x08, 0x1B, 0, 0x11, 0x22, 0x33)
        assertEquals("020106" + "081B" + "xx".repeat(4), Advertisement.redactedHex(flags + broken))
        // Nothing that looks like the name or address bytes survives.
        for (secret in listOf("526573", "112233")) assertFalse(redacted.contains(secret))
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
            // Every input byte is accounted for, shown or blanked.
            assertEquals(junk.size * 2, Advertisement.redactedHex(junk).length)
        }
    }
}
