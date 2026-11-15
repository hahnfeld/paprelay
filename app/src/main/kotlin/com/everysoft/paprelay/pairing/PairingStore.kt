// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.pairing

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.AtomicFile
import com.everysoft.paprelay.as11.PairingCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The machine PAPRelay is paired with, and the secret that proves it. */
class Pairing(val credentials: PairingCredentials, val address: String, val serialNumber: String?, val pairedAtMs: Long)

/** What is known about the stored pairing. */
sealed class PairingState {
    /** Not read from storage yet. */
    data object Loading : PairingState()

    /** No machine has been paired. */
    data object Absent : PairingState()

    class Paired(val pairing: Pairing) : PairingState()

    /**
     * A pairing was stored but can no longer be read: the hardware key was lost
     * or the file is damaged. The machine must be paired again; this is not the
     * same as never having paired.
     */
    data object Unreadable : PairingState()

    /**
     * Storage could not be read just now. Nothing is known to be lost: the
     * pairing may well be intact, and is read again the next time it is needed.
     */
    data object Unavailable : PairingState()
}

/**
 * Keeps the pairing secret encrypted under a key that never leaves the
 * device's secure hardware.
 *
 * The wrapping key deliberately requires no unlock or user authentication:
 * syncing has to work in the background while the phone is locked. The file
 * lives in the no-backup directory because a restored copy would be useless
 * without the hardware key, and the app excludes all data from backup anyway.
 *
 * All access is serialised and done off the main thread.
 */
class PairingStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "pairing.bin"))
    private val lock = Mutex()

    private val _state = MutableStateFlow<PairingState>(PairingState.Loading)
    val state: StateFlow<PairingState> = _state

    /** Reads the stored pairing and publishes it. Never throws. */
    suspend fun load(): PairingState = lock.withLock {
        withContext(Dispatchers.IO) { read() }.also { _state.value = it }
    }

    /**
     * The current pairing. Anything short of a definite answer is read again,
     * so a passing storage fault is never remembered as a lost pairing.
     */
    suspend fun current(): PairingState = when (val known = _state.value) {
        is PairingState.Paired, PairingState.Absent -> known
        else -> load()
    }

    /**
     * Proves, before a pairing is spent on the machine, that a pairing can be
     * stored: the hardware key exists and round-trips data, and the directory
     * accepts a synced write.
     *
     * A key is replaced only when it is known to be permanently unusable. A
     * fault that might pass is reported instead, because the key may still be
     * protecting a pairing that will be readable again.
     *
     * @throws IOException if a pairing could not be stored right now.
     */
    suspend fun selfTest(): Unit = lock.withLock {
        withContext(Dispatchers.IO) {
            try {
                roundTrip()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (!isPermanentKeyLoss(e)) throw IOException("secure storage is not available right now", e)
                deleteKey()
                try {
                    roundTrip()
                } catch (again: Exception) {
                    if (again is kotlinx.coroutines.CancellationException) throw again
                    throw IOException("secure storage is not usable on this phone", again)
                }
            }
            val probe = AtomicFile(File(file.baseFile.parentFile, "pairing.probe"))
            val stream = probe.startWrite()
            try {
                stream.write(ByteArray(PROBE_BYTES))
                probe.finishWrite(stream)
            } catch (e: IOException) {
                probe.failWrite(stream)
                throw e
            } finally {
                probe.delete()
            }
        }
    }

    /**
     * Stores the pairing durably. Runs to completion even if the caller is
     * cancelled: by the time this is called the machine has already replaced
     * its previous pairing with this one, so abandoning the write would lose it.
     *
     * @throws IOException if the pairing could not be stored.
     */
    suspend fun save(pairing: Pairing): Unit = withContext(NonCancellable) {
        lock.withLock { withContext(Dispatchers.IO) { write(pairing) } }
    }

    private fun write(pairing: Pairing) {
        run {
            val plain = encode(pairing)
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
                cipher.updateAAD(AAD)
                val sealed = cipher.doFinal(plain)
                // AtomicFile writes to a side file, syncs it to disk, then renames.
                val stream = file.startWrite()
                try {
                    stream.write(byteArrayOf(FORMAT_VERSION))
                    stream.write(cipher.iv)
                    stream.write(sealed)
                    file.finishWrite(stream)
                } catch (e: IOException) {
                    file.failWrite(stream)
                    throw e
                }
            } catch (e: java.security.GeneralSecurityException) {
                throw IOException("the pairing could not be encrypted", e)
            } catch (e: ProviderException) {
                throw IOException("the pairing could not be encrypted", e)
            } finally {
                plain.fill(0)
            }
        }
        // Published here, inside the same uninterruptible step as the write.
        _state.value = PairingState.Paired(pairing)
    }

    suspend fun clear(): Unit = lock.withLock {
        withContext(Dispatchers.IO) {
            file.delete()
            deleteKey()
        }
        _state.value = PairingState.Absent
    }

    private fun read(): PairingState {
        val blob = try {
            file.readFully()
        } catch (e: java.io.FileNotFoundException) {
            return PairingState.Absent
        } catch (e: IOException) {
            return PairingState.Unavailable
        }
        if (blob.size < 1 + IV_BYTES + TAG_BITS / 8 || blob[0] != FORMAT_VERSION) return PairingState.Unreadable
        return try {
            // The file is there but its key is not: that does not come back.
            val key = key() ?: return PairingState.Unreadable
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 1, IV_BYTES))
            cipher.updateAAD(AAD)
            val plain = cipher.doFinal(blob, 1 + IV_BYTES, blob.size - 1 - IV_BYTES)
            try {
                PairingState.Paired(decode(plain))
            } finally {
                plain.fill(0)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Only a key that is gone for good, or content that fails its own checks,
            // means the pairing is lost. Anything else may be the keystore having a bad moment.
            if (isPermanentKeyLoss(e) || e is IOException || e is IllegalArgumentException) {
                PairingState.Unreadable
            } else {
                PairingState.Unavailable
            }
        }
    }

    private fun isPermanentKeyLoss(e: Throwable): Boolean =
        e is KeyPermanentlyInvalidatedException || e is UnrecoverableKeyException || e is AEADBadTagException

    private fun encode(pairing: Pairing): ByteArray {
        val buffer = WipingBuffer()
        DataOutputStream(buffer).use { out ->
            out.writeUTF(pairing.credentials.clientId)
            out.writeByte(pairing.credentials.pairingKey.size)
            out.write(pairing.credentials.pairingKey)
            out.writeUTF(pairing.address)
            out.writeUTF(pairing.serialNumber.orEmpty())
            out.writeLong(pairing.pairedAtMs)
        }
        return buffer.takeAndWipe()
    }

    private fun decode(plain: ByteArray): Pairing {
        val input = DataInputStream(ByteArrayInputStream(plain))
        val clientId = input.readUTF()
        val pairingKey = ByteArray(input.readUnsignedByte()).also(input::readFully)
        val address = input.readUTF()
        val serial = input.readUTF().ifEmpty { null }
        return Pairing(PairingCredentials(clientId, pairingKey), address, serial, input.readLong())
    }

    private fun roundTrip() {
        val probe = ByteArray(32) { it.toByte() }
        val encrypt = Cipher.getInstance(TRANSFORMATION)
        encrypt.init(Cipher.ENCRYPT_MODE, key(create = true))
        val sealed = encrypt.doFinal(probe)
        val decrypt = Cipher.getInstance(TRANSFORMATION)
        decrypt.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, encrypt.iv))
        if (!decrypt.doFinal(sealed).contentEquals(probe)) throw IOException("secure storage returned different data")
    }

    private fun deleteKey() {
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

    /** A byte buffer whose backing array is blanked once its contents are taken. */
    private class WipingBuffer : java.io.ByteArrayOutputStream() {
        fun takeAndWipe(): ByteArray = toByteArray().also { buf.fill(0) }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "paprelay.pairing.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val FORMAT_VERSION: Byte = 1
        const val PROBE_BYTES = 256
        val AAD = ALIAS.toByteArray(Charsets.US_ASCII)
    }
}
