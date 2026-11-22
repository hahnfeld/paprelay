// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.pairing

import android.content.Context
import android.util.AtomicFile
import com.everysoft.paprelay.as11.PairingCredentials
import com.everysoft.paprelay.secure.KeystoreKeys
import com.everysoft.paprelay.secure.Sealer
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
import java.security.ProviderException

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
    private val keys = KeystoreKeys(ALIAS)
    private val sealer = Sealer(keys, AAD)

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
                // With no stored pairing the key protects nothing, so a faulty one is simply
                // replaced. Otherwise it is kept unless it is known to be gone for good.
                val protectsAPairing = file.baseFile.exists()
                if (protectsAPairing && !Sealer.isPermanentKeyLoss(e)) throw IOException("secure storage is not available right now", e)
                keys.delete()
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
                // finishWrite only logs a failed rename.
                if (probe.baseFile.length() != PROBE_BYTES.toLong()) throw IOException("storage did not keep a test file")
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
                // The IV, then the ciphertext.
                val sealed = sealer.seal(plain)
                // AtomicFile writes to a side file, syncs it to disk, then renames.
                val stream = file.startWrite()
                try {
                    stream.write(byteArrayOf(FORMAT_VERSION))
                    stream.write(sealed)
                    file.finishWrite(stream)
                } catch (e: IOException) {
                    file.failWrite(stream)
                    throw e
                }
                // finishWrite only logs a failed rename, so that much is checked here. A failed
                // sync to disk cannot be seen from this side: the read comes from memory.
                val stored = try {
                    file.readFully()
                } catch (e: IOException) {
                    throw IOException("the pairing was not stored", e)
                }
                if (stored.size != 1 + sealed.size || !stored.copyOfRange(1, stored.size).contentEquals(sealed)) {
                    throw IOException("the pairing was not stored intact")
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

    /** Runs to completion once started, so what is shown never disagrees with what is stored. */
    suspend fun clear(): Unit = withContext(NonCancellable) {
        lock.withLock {
            withContext(Dispatchers.IO) {
                file.delete()
                keys.delete()
                _state.value = PairingState.Absent
            }
        }
    }

    private fun read(): PairingState {
        val blob = try {
            file.readFully()
        } catch (e: java.io.FileNotFoundException) {
            // Also thrown when the file is there but cannot be opened just now.
            return if (file.baseFile.exists()) PairingState.Unavailable else PairingState.Absent
        } catch (e: IOException) {
            return PairingState.Unavailable
        }
        if (blob.size < 1 + Sealer.OVERHEAD || blob[0] != FORMAT_VERSION) return PairingState.Unreadable
        return try {
            // The file is there but its key is not: that does not come back.
            val plain = sealer.open(blob, 1) ?: return PairingState.Unreadable
            try {
                PairingState.Paired(decode(plain))
            } finally {
                plain.fill(0)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Only a key that is gone for good, or content that fails its own checks,
            // means the pairing is lost. Anything else may be the keystore having a bad moment.
            if (Sealer.isPermanentKeyLoss(e) || e is IOException || e is IllegalArgumentException) {
                PairingState.Unreadable
            } else {
                PairingState.Unavailable
            }
        }
    }

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
        if (!sealer.open(sealer.seal(probe)).contentEquals(probe)) throw IOException("secure storage returned different data")
    }

    /** A byte buffer whose backing array is blanked once its contents are taken. */
    private class WipingBuffer : java.io.ByteArrayOutputStream() {
        fun takeAndWipe(): ByteArray = toByteArray().also { buf.fill(0) }
    }

    private companion object {
        const val ALIAS = "paprelay.pairing.v1"
        const val FORMAT_VERSION: Byte = 1
        const val PROBE_BYTES = 256
        val AAD = ALIAS.toByteArray(Charsets.US_ASCII)
    }
}
