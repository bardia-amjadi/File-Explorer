package com.bardia.fileexplorer.storage

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import com.bardia.fileexplorer.core.BrowseError
import com.bardia.fileexplorer.core.BrowseErrorKind
import com.bardia.fileexplorer.core.FileEntry
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Single gateway to all file access. Two kinds of locations exist:
 *
 *  * `content://` document URIs handed out by the Storage Access Framework (user-selected folders, SD cards,
 *    USB OTG, any DocumentsProvider). Every call goes through [DocumentsContract]; providers may support
 *    only a subset of operations, which is reflected in [FileEntry.flags].
 *  * `file://` URIs, used **only** for the app's own private storage (trash, caches). They are never
 *    exposed to other apps; sharing goes through FileProvider.
 *
 * Nothing here touches the main thread rules itself: callers invoke these from Dispatchers.IO.
 */
class FileSystem(private val context: Context) {
    private val resolver: ContentResolver get() = context.contentResolver

    class ProviderUnavailableException(message: String) : IOException(message)
    class InsufficientStorageException(message: String) : IOException(message)

    private val projection = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED,
        Document.COLUMN_FLAGS
    )

    fun isLocal(uri: Uri) = uri.scheme == ContentResolver.SCHEME_FILE

    // ---------------------------------------------------------------- listing

    /**
     * Lists a directory. [onFirstChunk] is invoked once, after roughly [firstChunkSize] rows, so large folders
     * show up immediately while the remainder is still being read from the provider.
     */
    suspend fun list(dir: Uri, onFirstChunk: ((List<FileEntry>) -> Unit)? = null, firstChunkSize: Int = 300): List<FileEntry> {
        if (isLocal(dir)) return listLocal(dir)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(dir, DocumentsContract.getDocumentId(dir))
        val cursor = resolver.query(childrenUri, projection, null, null, null)
            ?: throw ProviderUnavailableException("The storage provider returned no data. The storage may be disconnected.")
        val result = ArrayList<FileEntry>()
        var notified = false
        cursor.use { c ->
            while (c.moveToNext()) {
                if (result.size % 256 == 0) currentCoroutineContext().ensureActive()
                val id = c.getString(0) ?: continue
                result.add(rowToEntry(c, DocumentsContract.buildDocumentUriUsingTree(dir, id), dir))
                if (!notified && onFirstChunk != null && result.size >= firstChunkSize) {
                    notified = true
                    onFirstChunk(result.toList())
                }
            }
        }
        return result
    }

    private fun listLocal(dir: Uri): List<FileEntry> {
        val folder = File(dir.path ?: throw FileNotFoundException())
        val children = folder.listFiles() ?: throw FileNotFoundException("Folder is not readable")
        return children.map { localEntry(it, dir.toString()) }
    }

    private fun localEntry(f: File, parent: String?) = FileEntry(
        uri = Uri.fromFile(f).toString(), name = f.name, isDirectory = f.isDirectory,
        size = if (f.isDirectory) -1L else f.length(), modified = f.lastModified(),
        mime = null, flags = 0, parentUri = parent, isLocal = true
    )

    private fun rowToEntry(c: Cursor, uri: Uri, parent: Uri?): FileEntry {
        val mime = c.getString(2)
        return FileEntry(
            uri = uri.toString(),
            name = c.getString(1) ?: "(unnamed)",
            isDirectory = mime == Document.MIME_TYPE_DIR,
            size = if (c.isNull(3)) -1L else c.getLong(3),
            modified = if (c.isNull(4)) 0L else c.getLong(4),
            mime = mime,
            flags = if (c.isNull(5)) 0 else c.getInt(5),
            parentUri = parent?.toString()
        )
    }

    /** Reads metadata for one document, or null if it does not exist / is not accessible. */
    fun stat(uri: Uri, parent: Uri? = null): FileEntry? {
        if (isLocal(uri)) {
            val f = File(uri.path ?: return null)
            return if (f.exists()) localEntry(f, parent?.toString() ?: f.parentFile?.let { Uri.fromFile(it).toString() }) else null
        }
        return try {
            resolver.query(uri, projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) rowToEntry(c, uri, parent) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Like [stat] but also understands non-document content URIs (MediaStore etc.) via OpenableColumns. */
    fun statAny(uri: Uri): FileEntry? {
        if (isLocal(uri) || DocumentsContract.isDocumentUri(context, uri)) return stat(uri)
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null
                FileEntry(
                    uri = uri.toString(),
                    name = c.getString(0) ?: uri.lastPathSegment ?: "file",
                    isDirectory = false,
                    size = if (c.isNull(1)) -1L else c.getLong(1),
                    mime = resolver.getType(uri)
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    fun exists(uri: Uri): Boolean = stat(uri) != null

    // ---------------------------------------------------------------- mutations

    fun createDirectory(parent: Uri, name: String): Uri {
        if (isLocal(parent)) {
            val f = File(File(parent.path!!), name)
            if (!f.mkdir() && !f.isDirectory) throw IOException("Could not create folder \"$name\"")
            return Uri.fromFile(f)
        }
        return DocumentsContract.createDocument(resolver, parent, Document.MIME_TYPE_DIR, name)
            ?: throw IOException("This location does not allow creating folders")
    }

    fun createFile(parent: Uri, name: String, mime: String?): Uri {
        if (isLocal(parent)) {
            val f = File(File(parent.path!!), name)
            if (!f.createNewFile() && !f.exists()) throw IOException("Could not create \"$name\"")
            return Uri.fromFile(f)
        }
        return DocumentsContract.createDocument(resolver, parent, mime ?: "application/octet-stream", name)
            ?: throw IOException("This location does not allow creating files")
    }

    /** Deletes a document (recursively for folders; providers delete the whole subtree). */
    fun delete(uri: Uri) {
        if (isLocal(uri)) {
            if (!File(uri.path!!).deleteRecursively()) throw IOException("Could not delete ${uri.lastPathSegment}")
            return
        }
        if (!DocumentsContract.deleteDocument(resolver, uri)) throw IOException("The provider refused to delete this item")
    }

    fun rename(uri: Uri, newName: String): Uri {
        if (isLocal(uri)) {
            val f = File(uri.path!!)
            val target = File(f.parentFile, newName)
            if (!f.renameTo(target)) throw IOException("Could not rename")
            return Uri.fromFile(target)
        }
        return DocumentsContract.renameDocument(resolver, uri, newName)
            ?: throw IOException("The provider refused to rename this item")
    }

    /** Fast server-side move; only valid inside one provider and when the document advertises FLAG_SUPPORTS_MOVE. */
    fun moveWithinProvider(src: Uri, srcParent: Uri, dstParent: Uri): Uri? {
        if (isLocal(src) || src.authority != dstParent.authority) return null
        return DocumentsContract.moveDocument(resolver, src, srcParent, dstParent)
    }

    // ---------------------------------------------------------------- streams

    fun openInput(uri: Uri): InputStream {
        if (isLocal(uri)) return File(uri.path!!).inputStream()
        return resolver.openInputStream(uri) ?: throw FileNotFoundException("Cannot open ${uri.lastPathSegment}")
    }

    fun openOutput(uri: Uri): OutputStream {
        if (isLocal(uri)) return File(uri.path!!).outputStream()
        return resolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException("Cannot write ${uri.lastPathSegment}")
    }

    fun openDescriptor(uri: Uri): ParcelFileDescriptor {
        if (isLocal(uri)) return ParcelFileDescriptor.open(File(uri.path!!), ParcelFileDescriptor.MODE_READ_ONLY)
        return resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException("Cannot open ${uri.lastPathSegment}")
    }

    // ---------------------------------------------------------------- helpers

    /**
     * True when [dest] is [src] itself or lies inside it. Document ids of hierarchical providers (e.g. the
     * external storage provider) are path-like, which makes this check reliable there. For other providers the
     * copy engine adds a second runtime guard.
     */
    fun isSameOrInside(src: Uri, dest: Uri): Boolean {
        if (src == dest) return true
        if (isLocal(src) || isLocal(dest)) return false
        if (src.authority != dest.authority) return false
        return try {
            val s = DocumentsContract.getDocumentId(src)
            val d = DocumentsContract.getDocumentId(dest)
            d == s || d.startsWith(if (s.endsWith("/")) s else "$s/")
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        /** Translates a provider failure into something the UI can explain and offer recovery for. */
        fun classify(e: Throwable): BrowseError = when (e) {
            is SecurityException -> BrowseError(
                BrowseErrorKind.PERMISSION_DENIED,
                "Access to this location was denied or revoked. Grant access to the folder again."
            )
            is FileNotFoundException, is IllegalArgumentException -> BrowseError(
                BrowseErrorKind.NOT_FOUND, "This folder no longer exists or was moved."
            )
            is ProviderUnavailableException -> BrowseError(BrowseErrorKind.UNAVAILABLE, e.message ?: "Storage is unavailable.")
            is IllegalStateException, is UnsupportedOperationException -> BrowseError(
                BrowseErrorKind.UNAVAILABLE, "The storage provider is not available right now."
            )
            else -> BrowseError(BrowseErrorKind.OTHER, e.message ?: "Something went wrong while reading this folder.")
        }

        /** Maps ENOSPC-style IOExceptions to a dedicated type so operations can stop with a clear message. */
        fun wrapIo(e: IOException): IOException {
            val m = e.message.orEmpty()
            return if (m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true)) {
                InsufficientStorageException("Not enough free storage space")
            } else e
        }
    }
}
