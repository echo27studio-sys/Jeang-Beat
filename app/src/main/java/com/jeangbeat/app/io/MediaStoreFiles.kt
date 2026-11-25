package com.jeangbeat.app.io

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

/**
 * Creates user-visible files without a file picker (Android 10+):
 *   audio  -> Music/Jeang Beat
 *   zips   -> Download/Jeang Beat
 *   .jbz   -> Documents/Jeang Beat/Projects
 * On Android 8–9 these return null and the caller falls back to the system picker.
 */
object MediaStoreFiles {
    const val MUSIC_DIR = "Music/Jeang Beat"
    const val DOWNLOAD_DIR = "Download/Jeang Beat"
    const val PROJECTS_DIR = "Documents/Jeang Beat/Projects"

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** A pending entry; call [publish] when the file is fully written, or [discard] on failure. */
    fun create(context: Context, displayName: String, mime: String, relativeDir: String): Uri? {
        if (!supported) return null
        val collection = when {
            relativeDir.startsWith(Environment.DIRECTORY_MUSIC) -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            relativeDir.startsWith(Environment.DIRECTORY_DOWNLOADS) -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else -> MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return runCatching { context.contentResolver.insert(collection, values) }.getOrNull()
    }

    fun publish(context: Context, uri: Uri) {
        if (!supported) return
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        runCatching { context.contentResolver.update(uri, values, null, null) }
    }

    fun discard(context: Context, uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    /** Folder for an export format. */
    fun dirFor(format: ExportFormat): String = if (format == ExportFormat.STEMS) DOWNLOAD_DIR else MUSIC_DIR
}
