// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/**
 * Keeps a table of the nights up to date in a file the user has made for it.
 *
 * The file is wherever the system's own "save as" screen put it: the phone's storage, or a
 * cloud drive whose app offers itself there. PAPRelay only ever hands the table to the
 * system; it has no network access of its own, and sends nothing anywhere.
 */
class NightFile(private val context: Context) {
    /** Keeps the right to write to [file] across restarts. Call once, when the user has just made it. */
    fun keep(file: Uri) {
        context.contentResolver.takePersistableUriPermission(file, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    /** Gives the right up again, when the user chooses another file or none. */
    fun release(file: Uri) {
        try {
            context.contentResolver.releasePersistableUriPermission(file, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: SecurityException) {
            // It was not held any more.
        }
    }

    /** The file's name as the user sees it, or null if whoever keeps it will not say. */
    fun name(file: Uri): String? = try {
        context.contentResolver.query(file, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Replaces the file's contents with [table].
     *
     * @return false if it could not be written: the file is gone, the right to it was taken
     *   away, or the app that keeps it failed.
     */
    fun write(file: Uri, table: String): Boolean = try {
        val bytes = table.toByteArray(Charsets.UTF_8)
        val resolver = context.contentResolver
        // "wt" truncates: a shorter table must not leave the tail of a longer one behind. Not every
        // keeper of files knows that mode; those that do not take plain "w" and replace the file whole.
        val stream = try {
            resolver.openOutputStream(file, "wt")
        } catch (e: FileNotFoundException) {
            resolver.openOutputStream(file, "w")
        } catch (e: IllegalArgumentException) {
            resolver.openOutputStream(file, "w")
        } ?: return false
        stream.use { it.write(bytes) }
        true
    } catch (e: Exception) {
        // Providers fail in ways of their own; whatever it was, the table was not written.
        false
    }

    companion object {
        /** The name suggested for the file. The user may give it another. */
        const val SUGGESTED_NAME = "PAPRelay nights.csv"
        const val MIME = "text/csv"
    }
}
