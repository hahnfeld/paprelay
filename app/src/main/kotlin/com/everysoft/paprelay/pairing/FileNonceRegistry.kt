// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.pairing

import com.everysoft.paprelay.as11.Hex
import com.everysoft.paprelay.as11.NonceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Remembers recent session nonces across app restarts so a recorded session
 * setup cannot be replayed at this app. Only hashes are stored.
 */
class FileNonceRegistry(private val file: File, private val capacity: Int = 512) : NonceRegistry {
    override suspend fun register(nonce: ByteArray): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            val digest = Hex.encode(MessageDigest.getInstance("SHA-256").digest(nonce))
            val known = if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()
            if (digest in known) return@withContext false
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText((known + digest).takeLast(capacity).joinToString("\n", postfix = "\n"))
            check(temp.renameTo(file)) { "could not record session nonce" }
            true
        }
    }

    private companion object {
        /** One lock for the process: every instance writes the same file. */
        val lock = Mutex()
    }
}
