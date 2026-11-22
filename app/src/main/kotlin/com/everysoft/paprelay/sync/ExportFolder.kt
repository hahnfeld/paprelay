// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.everysoft.paprelay.as11.plan.NightPlan
import com.everysoft.paprelay.core.nights.BreathingEvent
import com.everysoft.paprelay.core.nights.EventReport
import com.everysoft.paprelay.core.nights.Night
import com.everysoft.paprelay.core.nights.NightReport
import java.io.FileNotFoundException
import java.io.FileOutputStream

/** The files kept in the chosen folder, by name, and what each holds. Made without Android, so it can be tested. */
object ExportTables {
    const val NIGHTS = "paprelay_nights.csv"
    const val EVENTS = "paprelay_events.csv"

    /**
     * Both tables, or null while there is no finished night: an empty table must never replace a
     * full one. The events table is written beside the nights even when it has no events yet.
     */
    fun of(nights: List<Night>, events: List<BreathingEvent>): Map<String, String>? {
        if (nights.none { it.complete }) return null
        return linkedMapOf(NIGHTS to NightReport.csv(nights, NightPlan.FIGURES), EVENTS to EventReport.csv(events, nights))
    }
}

/**
 * The decisions of writing a file in the chosen folder, made without Android so that they can be
 * tested: which file to write, or whether to make it, and in which modes to open it.
 */
internal object FolderWrites {
    /**
     * What a listing of the folder found: each name with its document, the first if a name is there
     * twice; and whether the provider said it was still loading, so that the list may be a part.
     */
    class Listing(val names: Map<String, String>, val loading: Boolean)

    sealed interface Target {
        /** The file is there: write it. */
        class Existing(val documentId: String) : Target

        /** The folder was listed whole and the file is not there: make it. */
        object Create : Target

        /** Not now: the folder could not be listed. Making the file could make a second one beside it. */
        object Later : Target

        /**
         * Not yet: the provider is still loading the folder (a cloud drive, just chosen, usually is), and
         * the file is not among what it has listed so far. Asked again shortly; making it could make a
         * second one beside it.
         */
        object Wait : Target
    }

    fun target(listing: Listing?, name: String): Target {
        if (listing == null) return Target.Later
        listing.names[name]?.let { return Target.Existing(it) }
        return if (listing.loading) Target.Wait else Target.Create
    }

    /** How often, and how long apart, a folder still loading is listed again before the write waits for the next run. */
    const val LOADING_TRIES: Int = 4
    const val LOADING_PAUSE_MS: Long = 500

    /** How the writing of the files went. */
    enum class Outcome {
        /** Every file was written. */
        WRITTEN,

        /** The provider was still loading the folder: nothing is wrong, and the next run writes. */
        NOT_YET,

        /** A file could not be written. */
        FAILED,
    }

    /** The writing as a whole, from each file's: a failure is said; a folder still loading is not one. */
    fun outcome(files: Collection<Outcome>): Outcome = when {
        Outcome.FAILED in files -> Outcome.FAILED
        Outcome.NOT_YET in files -> Outcome.NOT_YET
        else -> Outcome.WRITTEN
    }

    /**
     * The modes to open a file in, in order. "rwt" and "wt" truncate; not every provider knows them.
     * "w" is the last: on a file kept on the phone it does not cut a longer file short, so the file
     * is then cut to the new length ([cutAfter]); a provider that streams the file takes what was
     * written as the whole of it.
     */
    val MODES: List<String> = listOf("rwt", "wt", "w")

    /** Whether a file opened in [mode] is to be cut to its new length after it was written. */
    fun cutAfter(mode: String): Boolean = mode == "w"
}

/**
 * Keeps the tables up to date in a folder the user has chosen once, through the system's own
 * folder screen.
 *
 * The folder is wherever that screen led: the phone's storage, or a cloud drive whose app offers
 * itself there. PAPRelay only ever hands the files to the system; it has no network access of its
 * own, and sends nothing anywhere. It sees and touches only the files it names in that folder.
 */
class ExportFolder(private val context: Context) {
    /** Keeps the right to write in [folder] across restarts. Call once, when the user has just chosen it. */
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

    /** The folder's name as the user sees it, or null if whoever keeps it will not say. */
    fun name(folder: Uri): String? = try {
        val document = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        context.contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Replaces the contents of each file in [folder] named by [files], making the file if it is not
     * there. Each file is written on its own: one that fails does not keep the other from being
     * written.
     *
     * The whole file is written in one go. A folder chosen this way offers no way to replace a file
     * atomically: a file the provider truncates first and then fails to fill is written whole again
     * by the next run.
     *
     * @return [FolderWrites.Outcome.FAILED] if any of them could not be written (the folder is gone,
     *   the right to it was taken away, or the app that keeps it failed); NOT_YET if the provider was
     *   still loading the folder after a few tries, which is no failure, and the next run writes.
     */
    internal fun write(folder: Uri, files: Map<String, String>): FolderWrites.Outcome =
        FolderWrites.outcome(files.map { (name, content) -> writeOne(folder, name, content) })

    /** Blocks for up to about two seconds while the provider is still loading the folder: call it off the main thread. */
    private fun writeOne(folder: Uri, name: String, content: String): FolderWrites.Outcome = try {
        val resolver = context.contentResolver
        val folderId = DocumentsContract.getTreeDocumentId(folder)
        var target = FolderWrites.target(list(folder, folderId), name)
        var tries = 1
        while (target == FolderWrites.Target.Wait && tries < FolderWrites.LOADING_TRIES) {
            Thread.sleep(FolderWrites.LOADING_PAUSE_MS)
            target = FolderWrites.target(list(folder, folderId), name)
            tries++
        }
        val file = when (target) {
            FolderWrites.Target.Later -> null
            FolderWrites.Target.Wait -> null
            FolderWrites.Target.Create -> DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(folder, folderId), MIME, name)
            is FolderWrites.Target.Existing -> DocumentsContract.buildDocumentUriUsingTree(folder, target.documentId)
        }
        when {
            target == FolderWrites.Target.Wait -> FolderWrites.Outcome.NOT_YET
            file != null && replace(file, content.toByteArray(Charsets.UTF_8)) -> FolderWrites.Outcome.WRITTEN
            else -> FolderWrites.Outcome.FAILED
        }
    } catch (e: Exception) {
        // Providers fail in ways of their own; whatever it was, the file was not written.
        FolderWrites.Outcome.FAILED
    }

    /** Writes [bytes] as the whole of [file], in the first mode the provider takes ([FolderWrites.MODES]). */
    private fun replace(file: Uri, bytes: ByteArray): Boolean {
        val resolver = context.contentResolver
        for (mode in FolderWrites.MODES) {
            val descriptor = try {
                resolver.openFileDescriptor(file, mode)
            } catch (e: FileNotFoundException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            } catch (e: UnsupportedOperationException) {
                null
            } ?: continue
            descriptor.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { out ->
                    out.write(bytes)
                    // "w" on a file kept on the phone does not cut a longer file short: it is cut here. On
                    // anything else (a pipe to a provider) cutting is not possible, and the provider takes
                    // what was written as the whole file.
                    if (FolderWrites.cutAfter(mode)) runCatching { out.channel.truncate(bytes.size.toLong()) }
                }
            }
            return true
        }
        return false
    }

    /** The names directly in the folder, or null if it could not be listed. Only names are read, never contents. */
    private fun list(folder: Uri, folderId: String): FolderWrites.Listing? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, folderId)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val cursor = try {
            context.contentResolver.query(children, columns, null, null, null)
        } catch (e: Exception) {
            null
        } ?: return null
        return cursor.use {
            val names = LinkedHashMap<String, String>()
            while (it.moveToNext()) {
                val name = it.getString(1) ?: continue
                names.putIfAbsent(name, it.getString(0) ?: continue)
            }
            // A provider still fetching the folder's contents says so; what it has listed may be a part.
            FolderWrites.Listing(names, loading = it.extras?.getBoolean(DocumentsContract.EXTRA_LOADING) == true)
        }
    }

    companion object {
        const val MIME = "text/csv"
    }
}
