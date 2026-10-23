package com.bardia.fileexplorer.storage

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.bardia.fileexplorer.core.FileCategory
import com.bardia.fileexplorer.core.FileEntry
import com.bardia.fileexplorer.core.SortField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Media browsing through MediaStore (Images / Videos / Audio). This is the supported way to list media on API 31+
 * without broad storage access; it needs the granular READ_MEDIA_* permission (or READ_EXTERNAL_STORAGE on
 * Android 12/12L), which is requested only when the user opens such a category.
 *
 * Results are read-only for this app: they have no parent folder, so move/delete are intentionally not offered.
 */
class MediaStoreRepository(private val context: Context) {

    fun supports(category: FileCategory) = category == FileCategory.IMAGES || category == FileCategory.VIDEOS || category == FileCategory.AUDIO

    private fun baseUri(category: FileCategory): Uri = when (category) {
        FileCategory.IMAGES -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        FileCategory.VIDEOS -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }

    fun requiredPermissions(category: FileCategory): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                when (category) {
                    FileCategory.IMAGES -> Manifest.permission.READ_MEDIA_IMAGES
                    FileCategory.VIDEOS -> Manifest.permission.READ_MEDIA_VIDEO
                    else -> Manifest.permission.READ_MEDIA_AUDIO
                }
            )
        } else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    fun hasPermission(category: FileCategory): Boolean =
        requiredPermissions(category).all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    suspend fun count(category: FileCategory): Int? = withContext(Dispatchers.IO) {
        if (!hasPermission(category)) return@withContext null
        try {
            context.contentResolver.query(baseUri(category), arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count }
        } catch (e: Exception) {
            null
        }
    }

    /** One page of media, newest/sorted first. Throws SecurityException if the permission was revoked meanwhile. */
    suspend fun page(category: FileCategory, sort: SortField, ascending: Boolean, limit: Int, offset: Int): List<FileEntry> =
        withContext(Dispatchers.IO) {
            val column = when (sort) {
                SortField.NAME -> MediaStore.MediaColumns.DISPLAY_NAME
                SortField.SIZE -> MediaStore.MediaColumns.SIZE
                SortField.MODIFIED -> MediaStore.MediaColumns.DATE_MODIFIED
                SortField.TYPE -> MediaStore.MediaColumns.MIME_TYPE
            }
            val args = Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "$column ${if (ascending) "ASC" else "DESC"}")
                putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit)
                putInt(android.content.ContentResolver.QUERY_ARG_OFFSET, offset)
            }
            val projection = arrayOf(
                MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.RELATIVE_PATH
            )
            val base = baseUri(category)
            val out = ArrayList<FileEntry>()
            context.contentResolver.query(base, projection, args, null)?.use { c ->
                while (c.moveToNext()) {
                    out.add(
                        FileEntry(
                            uri = ContentUris.withAppendedId(base, c.getLong(0)).toString(),
                            name = c.getString(1) ?: "(unnamed)",
                            isDirectory = false,
                            size = if (c.isNull(2)) -1L else c.getLong(2),
                            modified = c.getLong(3) * 1000L,
                            mime = c.getString(4),
                            parentUri = null,
                            displayPath = c.getString(5)?.trimEnd('/')
                        )
                    )
                }
            }
            out
        }
}
