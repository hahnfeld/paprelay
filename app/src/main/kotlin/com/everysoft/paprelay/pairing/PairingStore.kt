// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.pairing

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.everysoft.paprelay.as11.PairingCredentials
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The machine PAPRelay is paired with, and the secret that proves it. */
class Pairing(val credentials: PairingCredentials, val address: String, val serialNumber: String?, val pairedAtMs: Long)

/**
 * Keeps the pairing secret encrypted under a key that never leaves the
 * device's secure hardware.
 *
 * The wrapping key deliberately requires no unlock or user authentication:
 * syncing has to work in the background while the phone is locked. The file
 * lives in the no-backup directory because a restored copy would be useless
 * without the hardware key, and the app excludes all data from backup anyway.
 */
class PairingStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "pairing.bin")

    /** Returns the stored pairing, or null if there is none or it can no longer be decrypted. */
    fun load(): Pairing? {
        if (!file.exists()) return null
        return try {
            val blob = file.readBytes()
            val iv = blob.copyOfRange(1, 1 + IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key() ?: return null, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(AAD)
            val plain = cipher.doFinal(blob, 1 + IV_BYTES, blob.size - 1 - IV_BYTES)
            val input = DataInputStream(ByteArrayInputStream(plain))
            val clientId = input.readUTF()
            val pairingKey = ByteArray(input.readUnsignedByte()).also(input::readFully)
            val address = input.readUTF()
            val serial = input.readUTF().ifEmpty { null }
            val pairedAt = input.readLong()
            plain.fill(0)
            Pairing(PairingCredentials(clientId, pairingKey), address, serial, pairedAt)
        } catch (e: GeneralSecurityException) {
            null // key invalidated or file tampered with: treated as "pairing lost"
        } catch (e: java.io.IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: IndexOutOfBoundsException) {
            null
        }
    }

    fun save(pairing: Pairing) {
        val plainBuffer = ByteArrayOutputStream()
        DataOutputStream(plainBuffer).use { out ->
            out.writeUTF(pairing.credentials.clientId)
            out.writeByte(pairing.credentials.pairingKey.size)
            out.write(pairing.credentials.pairingKey)
            out.writeUTF(pairing.address)
            out.writeUTF(pairing.serialNumber.orEmpty())
            out.writeLong(pairing.pairedAtMs)
        }
        val plain = plainBuffer.toByteArray()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(AAD)
        val sealed = cipher.doFinal(plain)
        plain.fill(0)
        // Write-then-rename so a crash never leaves a half-written file.
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeBytes(byteArrayOf(FORMAT_VERSION) + cipher.iv + sealed)
        check(temp.renameTo(file)) { "could not store pairing" }
    }

    fun clear() {
        file.delete()
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS) }
    }

    private fun key(create: Boolean = false): SecretKey? {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        return try {
            generate(strongBox = true)
        } catch (e: StrongBoxUnavailableException) {
            generate(strongBox = false)
        } catch (e: ProviderException) {
            generate(strongBox = false)
        }
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
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
        const val ALIAS = "paprelay.pairing.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val FORMAT_VERSION: Byte = 1
        val AAD = ALIAS.toByteArray(Charsets.US_ASCII)
    }
}
