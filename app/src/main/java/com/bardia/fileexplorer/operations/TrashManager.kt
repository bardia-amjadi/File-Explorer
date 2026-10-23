package com.bardia.fileexplorer.operations

import android.content.Context
import android.net.Uri
import com.bardia.fileexplorer.core.FileEntry
import com.bardia.fileexplorer.storage.FileSystem
import com.bardia.fileexplorer.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

data class TrashItem(
    val id: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val originalParentUri: String?,
    val originalPath: String?,
    val deletedAt: Long
)

/**
 * App-managed trash. Storage-provider documents cannot be "un-deleted", so a delete is implemented as
 * "copy into the app's private trash folder, then delete the original" - and the original is only removed after
 * the copy succeeded. Restore copies back. This keeps Undo honest: it is available exactly when the data really
 * exists in the trash. Items older than the retention period are purged automatically.
 *
 * Limitation (also shown to the user): the trash lives in the app's private storage, so it consumes internal
 * space and is cleared if app data is cleared or the app is uninstalled.
 */
class TrashManager(context: Context, private val fs: FileSystem) {
    private val root = File(context.filesDir, "trash").apply { mkdirs() }
    private val indexFile = File(root, "index.json")
    private val lock = Mutex()
    private val engine = TransferEngine(fs)

    private val _items = MutableStateFlow<List<TrashItem>>(emptyList())
    val items: StateFlow<List<TrashItem>> = _items

    suspend fun load() = withContext(Dispatchers.IO) {
        lock.withLock {
            _items.value = try {
                if (!indexFile.exists()) emptyList() else {
                    val arr = JSONArray(indexFile.readText())
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val item = TrashItem(
                            o.optString("id"), o.optString("name"), o.optBoolean("dir"), o.optLong("size", -1),
                            o.optString("parent").ifEmpty { null }, o.optString("path").ifEmpty { null }, o.optLong("at")
                        )
                        // Drop index rows whose data vanished (e.g. partially cleared storage).
                        if (item.id.isNotEmpty() && File(File(root, item.id), item.name).exists()) item else null
                    }
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    private fun saveLocked() {
        val arr = JSONArray()
        _items.value.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name).put("dir", it.isDirectory).put("size", it.size)
                    .put("parent", it.originalParentUri ?: "").put("path", it.originalPath ?: "").put("at", it.deletedAt)
            )
        }
        val tmp = File(root, "index.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(indexFile)) {
            indexFile.writeText(arr.toString())
            tmp.delete()
        }
    }

    fun totalSize(): Long = _items.value.sumOf { it.size.coerceAtLeast(0) }

    fun localEntry(item: TrashItem): FileEntry? = fs.stat(Uri.fromFile(File(File(root, item.id), item.name)))

    /**
     * Copies [entry] into the trash, then removes the original. On any failure the trash copy is discarded and
     * the original is left untouched.
     */
    suspend fun moveToTrash(entry: FileEntry, onBytes: (Long) -> Unit = {}): TrashItem = withContext(Dispatchers.IO) {
        val (bytes, _) = engine.measure(listOf(entry))
        if (bytes > root.usableSpace - RESERVE_BYTES) {
            throw FileSystem.InsufficientStorageException(
                "Not enough app storage to keep an undo copy (${Format.size(bytes)} needed). Delete permanently instead."
            )
        }
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        val sink = object : ProgressSink {
            override fun bytes(delta: Long) = onBytes(delta)
            override fun current(name: String) {}
            override fun itemDone() {}
            override fun total(bytes: Long, items: Int) {}
        }
        val ctx = TransferContext(fs, null, sink, { ConflictDecision(ConflictAction.CANCEL) })
        try {
            val o = engine.copy(ctx, entry, Uri.fromFile(dir))
            if (!o.complete) throw IOException(ctx.errors.firstOrNull() ?: "Could not copy the item into the trash")
            // Only now is it safe to remove the original.
            fs.delete(Uri.parse(entry.uri))
        } catch (e: Throwable) {
            withContext(kotlinx.coroutines.NonCancellable) { dir.deleteRecursively() }
            throw e
        }
        val item = TrashItem(
            id, entry.name, entry.isDirectory, if (entry.isDirectory) bytes else entry.size,
            entry.parentUri, entry.displayPath, System.currentTimeMillis()
        )
        lock.withLock {
            _items.value = _items.value + item
            saveLocked()
        }
        item
    }

    fun find(id: String): TrashItem? = _items.value.firstOrNull { it.id == id }

    /** Removes the item from the trash index and deletes its data (after a successful restore or on purge). */
    suspend fun purge(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            File(root, id).deleteRecursively()
            _items.value = _items.value.filterNot { it.id == id }
            saveLocked()
        }
    }

    suspend fun purgeAll() = withContext(Dispatchers.IO) {
        lock.withLock {
            _items.value.forEach { File(root, it.id).deleteRecursively() }
            _items.value = emptyList()
            saveLocked()
        }
    }

    suspend fun purgeExpired(retentionDays: Int) {
        if (retentionDays <= 0) return
        val limit = System.currentTimeMillis() - retentionDays * 86_400_000L
        _items.value.filter { it.deletedAt < limit }.forEach { purge(it.id) }
    }

    companion object {
        private const val RESERVE_BYTES = 64L * 1024 * 1024
    }
}
