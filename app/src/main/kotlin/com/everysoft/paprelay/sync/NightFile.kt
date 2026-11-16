// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.everysoft.paprelay.core.nights.Night
import com.everysoft.paprelay.core.nights.NightReport

/**
 * Keeps a table of the nights up to date in a folder the user has chosen.
 *
 * The folder is whatever the system's own picker offered: the phone's storage, or a folder
 * that another app keeps in step with a cloud drive. PAPRelay only ever hands the file to
 * the system; it has no network access of its own, and sends nothing anywhere.
 */
class NightFile(private val context: Context) {
    /** Keeps the right to write to [folder] across restarts. Call once, when the user has just chosen it. */
    fun keep(folder: Uri) {
        context.contentResolver.takePersistableUriPermission(folder, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    /** Gives the right up again, when the user chooses another folder or none. */
    fun release(folder: Uri) {
        try {
            context.contentResolver.releasePersistableUriPermission(folder, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: SecurityException) {
            // It was not held any more.
        }
    }

    /**
     * Writes the table, replacing the file if it is there.
     *
     * @return false if it could not be written: the folder is gone, the right to it was taken
     *   away, or the app that provides it failed.
     */
    fun write(folder: Uri, nights: Collection<Night>): Boolean = try {
        val resolver = context.contentResolver
        val folderId = DocumentsContract.getTreeDocumentId(folder)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, folderId)
        var existing: Uri? = null
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == FILE_NAME) existing = DocumentsContract.buildDocumentUriUsingTree(folder, cursor.getString(0))
            }
        }
        val target = existing
            ?: DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(folder, folderId), MIME, FILE_NAME)
            ?: return false
        // "wt" truncates: a shorter table must not leave the tail of a longer one behind.
        val stream = resolver.openOutputStream(target, "wt") ?: return false
        stream.use { it.write(NightReport.csv(nights).toByteArray(Charsets.UTF_8)) }
        true
    } catch (e: Exception) {
        // Providers fail in ways of their own; whatever it was, the table was not written.
        false
    }

    private companion object {
        const val FILE_NAME = "PAPRelay nights.csv"
        const val MIME = "text/csv"
    }
}
