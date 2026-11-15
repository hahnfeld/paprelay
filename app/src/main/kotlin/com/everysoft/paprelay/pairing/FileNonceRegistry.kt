// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.pairing

import android.util.AtomicFile
import com.everysoft.paprelay.as11.Hex
import com.everysoft.paprelay.as11.NonceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/**
 * Remembers the nonce of every proven session across app restarts, so a
 * recorded session setup cannot be replayed at this app. Only hashes are
 * stored, and writes are synced to disk before they count.
 *
 * Entries are never evicted in normal use: at one session an hour the record
 * grows by about half a megabyte a year. The ceiling exists only to bound a
 * pathological case, and is far beyond what an attacker could fill, because a
 * nonce is recorded only for a peer that proved it holds the pairing key.
 *
 * Storage errors propagate; the protocol client reports them as their own type.
 */
class FileNonceRegistry(file: File, private val ceiling: Int = 200_000) : NonceRegistry {
    private val file = AtomicFile(file)

    override suspend fun isKnown(nonce: ByteArray): Boolean = lock.withLock {
        withContext(Dispatchers.IO) { digest(nonce) in read() }
    }

    override suspend fun remember(nonce: ByteArray): Unit = lock.withLock {
        withContext(Dispatchers.IO) {
            val known = read()
            val entry = digest(nonce)
            if (entry in known) return@withContext
            val updated = (known + entry).toList().takeLast(ceiling)
            val stream = file.startWrite()
            try {
                stream.write(updated.joinToString("\n", postfix = "\n").toByteArray(Charsets.US_ASCII))
                file.finishWrite(stream)
            } catch (e: java.io.IOException) {
                file.failWrite(stream)
                throw e
            }
        }
    }

    private fun read(): LinkedHashSet<String> = try {
        file.readFully().toString(Charsets.US_ASCII).lineSequence().filter { it.length == 64 }.toCollection(LinkedHashSet())
    } catch (e: FileNotFoundException) {
        LinkedHashSet()
    }

    private fun digest(nonce: ByteArray): String = Hex.encode(MessageDigest.getInstance("SHA-256").digest(nonce))

    private companion object {
        /** One lock for the process: every instance writes the same file. */
        val lock = Mutex()
    }
}
