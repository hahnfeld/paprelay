// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.secure

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Where a store's key is kept: the phone's secure hardware, or, in tests, memory. */
interface SecretKeys {
    /** The key, or null when there is none. */
    fun existing(): SecretKey?

    /** The key, made first if there is none. */
    fun getOrCreate(): SecretKey

    /** Removes the key. What it protected can never be read again. Never throws. */
    fun delete()
}

/**
 * A non-exportable AES-256 key in the Android Keystore under [alias], for GCM only: in StrongBox where
 * the phone has it, otherwise in the trusted execution environment. It needs no unlock and no user
 * authentication, because what it protects is used by synchronisations in the background while the
 * phone is locked.
 */
class KeystoreKeys(private val alias: String) : SecretKeys {
    override fun existing(): SecretKey? = KeyStore.getInstance(KEYSTORE).apply { load(null) }.getKey(alias, null) as? SecretKey

    override fun getOrCreate(): SecretKey = existing() ?: try {
        generate(strongBox = true)
    } catch (e: StrongBoxUnavailableException) {
        generate(strongBox = false)
    } catch (e: ProviderException) {
        generate(strongBox = false)
    }

    override fun delete() {
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(alias) }
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
    }
}

/**
 * Seals bytes with AES-256-GCM under [keys]: a fresh random 12-byte IV each time, kept in front of the
 * ciphertext, and [aad] (what the bytes are for, and in which format) bound in, so that sealed bytes
 * of one kind cannot be passed off as another.
 */
class Sealer(private val keys: SecretKeys, private val aad: ByteArray) {
    /** The IV, then the ciphertext with its tag. @throws java.security.GeneralSecurityException or [ProviderException]. */
    fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The provider chooses the IV, at random: a key in the Keystore does not accept one from outside.
        cipher.init(Cipher.ENCRYPT_MODE, keys.getOrCreate())
        cipher.updateAAD(aad)
        val sealed = cipher.doFinal(plain)
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "unexpected IV" }
        return iv + sealed
    }

    /**
     * What [blob] holds from [offset] on, opened; or null when there is no key, which does not come back.
     *
     * @throws AEADBadTagException when the bytes, or what they are said to be for, are not what was sealed.
     */
    fun open(blob: ByteArray, offset: Int = 0): ByteArray? {
        require(blob.size - offset >= IV_BYTES + TAG_BITS / 8) { "too short" }
        val key = keys.existing() ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, offset, IV_BYTES))
        cipher.updateAAD(aad)
        return cipher.doFinal(blob, offset + IV_BYTES, blob.size - offset - IV_BYTES)
    }

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128

        /** The smallest sealed blob: an IV and a tag around nothing. */
        const val OVERHEAD = IV_BYTES + TAG_BITS / 8

        /**
         * Whether [e] means the key, or what it protects, is gone for good.
         *
         * Android reports almost every keystore fault while fetching a key as
         * UnrecoverableKeyException, including ones that pass. It counts as permanent
         * only when it carries no cause (the key was invalidated) or the keystore
         * itself says the key is corrupted.
         */
        fun isPermanentKeyLoss(e: Throwable): Boolean = when (e) {
            is KeyPermanentlyInvalidatedException, is AEADBadTagException -> true
            is UnrecoverableKeyException -> {
                val cause = e.cause
                cause == null ||
                    (cause is android.security.KeyStoreException &&
                        cause.numericErrorCode == android.security.KeyStoreException.ERROR_KEY_CORRUPTED)
            }
            else -> false
        }
    }
}
