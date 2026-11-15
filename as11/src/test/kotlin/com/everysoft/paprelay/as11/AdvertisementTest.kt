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
    fun `arbitrary bytes never throw`() {
        val random = Random(3)
        repeat(5_000) {
            Advertisement.parse(ByteArray(random.nextInt(70)).also(random::nextBytes)).looksLikeAirSense
        }
    }
}
