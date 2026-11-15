// SPDX-License-Identifier: GPL-3.0-or-later
//
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
// Based on SomnoTrace, originally created by Ilya Kruchinin (https://github.com/ilyakruchinin).
//
// Derived from SomnoTrace main/as11_ble.c (aes_cbc_encrypt, aes_cbc_decrypt,
// do_setup_encrypted_session), Copyright (C) 2026 Ilya Kruchinin,
// GPL-3.0-or-later with the Section 7(b) attribution term reproduced in NOTICE.
// Ported to Kotlin and modified 2026-11-15: decryption validates the declared
// length and reports whether the padding was canonical.
package com.everysoft.paprelay.as11

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A decrypted message plus what its framing looked like. */
public class DecryptedMessage internal constructor(
    public val plaintext: ByteArray,
    /** True when the padding was at most 15 bytes and all zero, as this client sends it. */
    public val canonicalPadding: Boolean,
)

/**
 * Per-connection cryptography dictated by the machine.
 *
 * Wire format of an encrypted payload: a random 16-byte IV, then AES-256-CBC
 * over `[u16 LE length][message][zero padding to a 16-byte boundary]`.
 *
 * The scheme has no message authentication, so a party able to modify traffic
 * can alter bytes without detection. This client limits what that can achieve:
 * see [As11Request.encode] for the request layout, and [As11Client] for the
 * checks applied to everything received.
 */
public object SessionCrypto {
    public const val KEY_BYTES: Int = 32
    private const val BLOCK = 16
    private const val LENGTH_PREFIX = 2

    /** `HMAC-SHA256(K, challenge)`, sent as `CheckSessionIntegrity.response`. */
    public fun challengeResponse(pairingKey: ByteArray, challenge: ByteArray): ByteArray {
        require(pairingKey.size == KEY_BYTES) { "pairing key must be $KEY_BYTES bytes" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pairingKey, "HmacSHA256"))
        return mac.doFinal(challenge)
    }

    /** `SHA256(K | nonce)`. */
    public fun deriveSessionKey(pairingKey: ByteArray, nonce: ByteArray): ByteArray {
        require(pairingKey.size == KEY_BYTES) { "pairing key must be $KEY_BYTES bytes" }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(pairingKey)
        digest.update(nonce)
        return digest.digest()
    }

    public fun encrypt(sessionKey: ByteArray, message: ByteArray, random: SecureRandom): ByteArray {
        require(sessionKey.size == KEY_BYTES) { "session key must be $KEY_BYTES bytes" }
        require(message.size <= 0xFFFF) { "message too large" }
        val framedLength = LENGTH_PREFIX + message.size
        val padded = ByteArray((framedLength + BLOCK - 1) / BLOCK * BLOCK)
        Fig.putU16(padded, 0, message.size)
        message.copyInto(padded, LENGTH_PREFIX)

        val iv = ByteArray(BLOCK).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sessionKey, "AES"), IvParameterSpec(iv))
        val out = ByteArray(BLOCK + padded.size)
        iv.copyInto(out, 0)
        cipher.doFinal(padded, 0, padded.size, out, BLOCK)
        return out
    }

    /** @throws As11ProtocolException if the payload cannot be a valid encrypted message. */
    public fun decrypt(sessionKey: ByteArray, payload: ByteArray): DecryptedMessage {
        require(sessionKey.size == KEY_BYTES) { "session key must be $KEY_BYTES bytes" }
        val cipherLength = payload.size - BLOCK
        if (cipherLength < BLOCK || cipherLength % BLOCK != 0) {
            throw As11ProtocolException("encrypted payload has invalid length ${payload.size}")
        }
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sessionKey, "AES"), IvParameterSpec(payload, 0, BLOCK))
        val plain = cipher.doFinal(payload, BLOCK, cipherLength)

        val declared = Fig.getU16(plain, 0)
        val padding = cipherLength - LENGTH_PREFIX - declared
        if (padding < 0) {
            throw As11ProtocolException("encrypted payload declares an impossible length")
        }
        var canonical = padding < BLOCK
        for (i in LENGTH_PREFIX + declared until plain.size) {
            if (plain[i].toInt() != 0) canonical = false
        }
        return DecryptedMessage(plain.copyOfRange(LENGTH_PREFIX, LENGTH_PREFIX + declared), canonical)
    }
}
