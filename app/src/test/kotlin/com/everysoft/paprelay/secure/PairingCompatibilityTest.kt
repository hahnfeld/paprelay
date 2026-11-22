// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.secure

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** pairing.bin as 0.4.0's PairingStore wrote and read it, against the Sealer that replaced its cipher code. */
class PairingCompatibilityTest {
    private val aad = "paprelay.pairing.v1".toByteArray(Charsets.US_ASCII)
    private val format: Byte = 1

    /** Exactly the old write: the format, then cipher.iv, then doFinal(plain) with the associated data. */
    private fun oldWrite(keys: MemoryKeys, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keys.getOrCreate())
        cipher.updateAAD(aad)
        val sealed = cipher.doFinal(plain)
        return byteArrayOf(format) + cipher.iv + sealed
    }

    /** Exactly the old read. */
    private fun oldRead(keys: MemoryKeys, blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keys.existing()!!, GCMParameterSpec(128, blob, 1, 12))
        cipher.updateAAD(aad)
        return cipher.doFinal(blob, 1 + 12, blob.size - 1 - 12)
    }

    @Test
    fun `a pairing written by 0_4_0 is read by the Sealer, and the other way round`() {
        val keys = MemoryKeys()
        val plain = ByteArray(300) { (it * 7).toByte() }
        val old = oldWrite(keys, plain)
        assertContentEquals(plain, Sealer(keys, aad).open(old, 1))
        val new = byteArrayOf(format) + Sealer(keys, aad).seal(plain)
        assertContentEquals(plain, oldRead(keys, new))
        assertEquals(old.size, new.size)
    }
}
