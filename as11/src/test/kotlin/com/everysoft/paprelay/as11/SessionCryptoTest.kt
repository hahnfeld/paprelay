// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionCryptoTest {
    private val session = Vectors.obj("session")
    private val key = session.bytes("sessionKey")

    /** A SecureRandom that returns fixed bytes, so encryption output is reproducible. */
    private class Fixed(private val bytes: ByteArray) : SecureRandom() {
        override fun nextBytes(out: ByteArray) {
            bytes.copyInto(out, 0, 0, out.size)
        }
    }

    @Test
    fun `challenge response and session key match the independent implementation`() {
        val pairingKey = session.bytes("pairingKey")
        assertContentEquals(session.bytes("response"), SessionCrypto.challengeResponse(pairingKey, session.bytes("challenge")))
        assertContentEquals(key, SessionCrypto.deriveSessionKey(pairingKey, session.bytes("nonce")))
    }

    @Test
    fun `encryption matches OpenSSL and round-trips`() {
        for (case in Vectors.list("aes")) {
            val message = case.bytes("message")
            val payload = SessionCrypto.encrypt(key, message, Fixed(case.bytes("iv")))
            assertContentEquals(case.bytes("payload"), payload)
            val back = SessionCrypto.decrypt(key, payload)
            assertContentEquals(message, back.plaintext)
            assertTrue(back.canonicalPadding)
        }
    }

    @Test
    fun `impossible lengths are refused`() {
        assertFailsWith<As11ProtocolException> { SessionCrypto.decrypt(key, ByteArray(16)) }
        assertFailsWith<As11ProtocolException> { SessionCrypto.decrypt(key, ByteArray(40)) }
        // A wrong key makes the length prefix garbage far more often than not.
        val payload = SessionCrypto.encrypt(key, ByteArray(5), SecureRandom())
        val wrongKey = key.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val outcomes = (0 until 50).map {
            runCatching { SessionCrypto.decrypt(wrongKey, SessionCrypto.encrypt(key, ByteArray(5), SecureRandom())) }
        }
        assertTrue(outcomes.any { it.isFailure })
        assertContentEquals(ByteArray(5), SessionCrypto.decrypt(key, payload).plaintext)
    }

    @Test
    fun `non-zero padding is reported`() {
        // Hand-build a frame whose padding is not zero.
        val framed = ByteArray(16) { 0x55 }
        framed[0] = 3
        framed[1] = 0
        val iv = ByteArray(16)
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.IvParameterSpec(iv),
        )
        val decrypted = SessionCrypto.decrypt(key, iv + cipher.doFinal(framed))
        assertFalse(decrypted.canonicalPadding)
        assertContentEquals(ByteArray(3) { 0x55 }, decrypted.plaintext)
    }
}
