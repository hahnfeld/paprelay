// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.sync

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Replacing one of the app's own files whole and atomically: a crash leaves the old content or the new, never a mixture. */
internal object AtomicFiles {
    /** @throws IOException if it could not be stored; what was there before is then still in place. */
    fun write(file: File, bytes: ByteArray) {
        val directory = file.absoluteFile.parentFile ?: throw IOException("no directory for ${file.name}")
        directory.mkdirs()
        val temporary = File(directory, file.name + ".new")
        FileChannel.open(temporary.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
            val buffer = java.nio.ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            // On disk before it replaces the old one, or a power cut could leave an empty file in its place.
            channel.force(true)
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        // The new name is on disk only once its directory is. Not every file system lets a directory
        // be opened for this; where it does not, nothing more can be done.
        runCatching { FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) } }
    }
}
