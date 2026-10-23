package com.bardia.fileexplorer.operations

import android.net.Uri
import com.bardia.fileexplorer.core.FileEntry
import com.bardia.fileexplorer.core.FileTypes
import com.bardia.fileexplorer.core.NameUtils
import com.bardia.fileexplorer.storage.FileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

enum class ConflictAction { REPLACE, SKIP, KEEP_BOTH, CANCEL }
data class ConflictDecision(val action: ConflictAction, val applyToAll: Boolean = false)
data class ConflictInfo(val name: String, val existing: FileEntry, val incomingIsDirectory: Boolean, val incomingSize: Long, val incomingModified: Long)

/** Thrown when the user picks "Cancel" in a conflict prompt; treated like a normal cancellation. */
class UserCancelledException : CancellationException("Cancelled by user")

interface ProgressSink {
    fun bytes(delta: Long)
    fun current(name: String)
    fun itemDone()
    fun total(bytes: Long, items: Int)
}

object NoProgress : ProgressSink {
    override fun bytes(delta: Long) {}
    override fun current(name: String) {}
    override fun itemDone() {}
    override fun total(bytes: Long, items: Int) {}
}

/**
 * Shared state for one running operation: progress reporting, conflict policy, a cache of destination folder
 * listings (so a batch lists each folder once) and the bookkeeping that makes undo possible.
 */
class TransferContext(
    val fs: FileSystem,
    private val trash: TrashManager?,
    val progress: ProgressSink,
    private val conflictResolver: suspend (ConflictInfo) -> ConflictDecision,
    preset: ConflictAction? = null
) {
    val errors = ArrayList<String>()

    /** Top-most items newly created by this operation (undo deletes exactly these). */
    val created = ArrayList<String>()
    private val newDirs = HashSet<String>()

    /** Items that were moved to the trash because the user chose "Replace"; undo restores them. */
    val replacedTrashIds = ArrayList<String>()

    var applyAll: ConflictAction? = preset

    /** When set, name clashes are resolved by keeping both (used for "duplicate in same folder"). */
    var forceKeepBoth = false

    private val cache = HashMap<String, MutableMap<String, FileEntry>>()

    /** Lower-cased name -> entry for a destination folder, listed once and then kept up to date. */
    suspend fun children(dir: Uri): MutableMap<String, FileEntry> {
        val key = dir.toString()
        cache[key]?.let { return it }
        val map = HashMap<String, FileEntry>()
        for (e in fs.list(dir)) map[e.name.lowercase()] = e
        cache[key] = map
        return map
    }

    fun remember(dir: Uri, entry: FileEntry) {
        cache[dir.toString()]?.put(entry.name.lowercase(), entry)
    }

    fun registerCreated(dir: Uri, uri: Uri, name: String, isDirectory: Boolean, parentIsNew: Boolean) {
        if (!parentIsNew) created.add(uri.toString())
        if (isDirectory) newDirs.add(uri.toString())
        remember(dir, FileEntry(uri = uri.toString(), name = name, isDirectory = isDirectory, parentUri = dir.toString()))
    }

    fun isNewDir(uri: Uri) = uri.toString() in newDirs

    private suspend fun resolve(info: ConflictInfo): ConflictAction {
        if (forceKeepBoth) return ConflictAction.KEEP_BOTH
        applyAll?.let { return it }
        val d = conflictResolver(info)
        if (d.applyToAll && d.action != ConflictAction.CANCEL) applyAll = d.action
        return d.action
    }

    /**
     * Decides the final name for a new item in [destDir]. Returns null when the item must be skipped.
     * Existing data is never overwritten silently: "Replace" first moves the old item to the app trash (so it is
     * recoverable via Undo/Trash); if that is impossible the new item is skipped instead.
     */
    suspend fun prepareTarget(destDir: Uri, name: String, incomingIsDirectory: Boolean, size: Long, modified: Long): String? {
        val siblings = children(destDir)
        val existing = siblings[name.lowercase()] ?: return name
        return when (resolve(ConflictInfo(name, existing, incomingIsDirectory, size, modified))) {
            ConflictAction.SKIP -> null
            ConflictAction.CANCEL -> throw UserCancelledException()
            ConflictAction.KEEP_BOTH ->
                NameUtils.uniqueName(name, siblings.values.map { it.name }.toSet(), hasExtension = !incomingIsDirectory)
            ConflictAction.REPLACE -> {
                val t = trash
                if (t == null) {
                    errors.add("$name: cannot replace here; skipped")
                    null
                } else try {
                    val item = t.moveToTrash(existing)
                    replacedTrashIds.add(item.id)
                    siblings.remove(name.lowercase())
                    name
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors.add("$name: could not replace (${e.message}); skipped to protect the existing item")
                    null
                }
            }
        }
    }
}

/**
 * Copy / move algorithms over [FileSystem]. Works for any combination of SAF and app-private locations.
 * All blocking IO checks for cancellation between chunks; a cancelled file transfer deletes its partial output.
 */
class TransferEngine(private val fs: FileSystem) {
    data class Outcome(val uri: String?, val name: String?, val complete: Boolean)

    /** Total bytes and file count of [sources] (used for progress). Cancellable. */
    suspend fun measure(sources: List<FileEntry>): Pair<Long, Int> {
        var bytes = 0L
        var files = 0
        val stack = ArrayDeque<FileEntry>()
        val visited = HashSet<String>()
        stack.addAll(sources)
        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val e = stack.removeLast()
            if (e.isDirectory) {
                if (!visited.add(e.uri)) continue
                try {
                    stack.addAll(fs.list(Uri.parse(e.uri)))
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    // Unreadable subfolder: counted as nothing; the copy itself reports the error.
                }
            } else {
                files++
                if (e.size > 0) bytes += e.size
            }
        }
        return bytes to files
    }

    suspend fun copy(ctx: TransferContext, src: FileEntry, destDir: Uri, parentIsNew: Boolean = false): Outcome {
        currentCoroutineContext().ensureActive()
        ctx.progress.current(src.name)
        return if (src.isDirectory) copyDirectory(ctx, src, destDir, parentIsNew) else copyFile(ctx, src, destDir, parentIsNew)
    }

    private suspend fun copyDirectory(ctx: TransferContext, src: FileEntry, destDir: Uri, parentIsNew: Boolean): Outcome {
        val existing = ctx.children(destDir)[src.name.lowercase()]
        val targetDir: Uri
        val targetName: String
        val created: Boolean
        if (existing != null && existing.isDirectory && !ctx.forceKeepBoth) {
            // Folder with the same name: merge contents; individual file clashes are resolved one by one.
            targetDir = Uri.parse(existing.uri)
            targetName = existing.name
            created = false
        } else {
            val finalName = ctx.prepareTarget(destDir, src.name, true, src.size, src.modified)
                ?: return Outcome(null, null, false)
            targetDir = fs.createDirectory(destDir, finalName)
            targetName = finalName
            ctx.registerCreated(destDir, targetDir, finalName, true, parentIsNew)
            created = true
        }
        var complete = true
        val children = fs.list(Uri.parse(src.uri))
        for (child in children) {
            // Guard against copying a folder into itself when the provider's ids are not path-like.
            if (child.uri == targetDir.toString()) continue
            val o = try {
                copy(ctx, child, targetDir, parentIsNew || created)
            } catch (e: CancellationException) {
                throw e
            } catch (e: FileSystem.InsufficientStorageException) {
                throw e
            } catch (e: Exception) {
                ctx.errors.add("${child.name}: ${e.message ?: "failed"}")
                Outcome(null, null, false)
            }
            if (!o.complete) complete = false
        }
        return Outcome(targetDir.toString(), targetName, complete)
    }

    private suspend fun copyFile(ctx: TransferContext, src: FileEntry, destDir: Uri, parentIsNew: Boolean): Outcome {
        val finalName = ctx.prepareTarget(destDir, src.name, false, src.size, src.modified)
        if (finalName == null) {
            if (src.size > 0) ctx.progress.bytes(src.size)
            ctx.progress.itemDone()
            return Outcome(null, null, false)
        }
        val dest = fs.createFile(destDir, finalName, FileTypes.effectiveMime(src))
        ctx.registerCreated(destDir, dest, finalName, false, parentIsNew)
        try {
            fs.openInput(Uri.parse(src.uri)).use { input -> fs.openOutput(dest).use { out -> pump(ctx, input, out) } }
        } catch (e: CancellationException) {
            discard(dest)
            throw e
        } catch (e: IOException) {
            discard(dest)
            throw FileSystem.wrapIo(e)
        }
        ctx.progress.itemDone()
        return Outcome(dest.toString(), finalName, true)
    }

    /** Removes a partially written file so a failed/cancelled transfer never leaves a corrupt copy behind. */
    private suspend fun discard(uri: Uri) = withContext(NonCancellable) {
        try {
            fs.delete(uri)
        } catch (e: Exception) {
            // Best effort.
        }
    }

    suspend fun pump(ctx: TransferContext, input: InputStream, output: OutputStream) {
        val buf = ByteArray(BUFFER)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            ctx.progress.bytes(n.toLong())
            currentCoroutineContext().ensureActive()
        }
        output.flush()
    }

    /**
     * Moves [src] into [destDir]. Uses the provider's atomic move when available (instant, no data copied);
     * otherwise copies and deletes the original **only after the copy fully succeeded**.
     */
    suspend fun move(ctx: TransferContext, src: FileEntry, destDir: Uri): Outcome {
        currentCoroutineContext().ensureActive()
        val existing = ctx.children(destDir)[src.name.lowercase()]
        val parent = src.parentUri
        if (existing == null && parent != null && src.supportsMove && !src.isLocal) {
            try {
                val moved = fs.moveWithinProvider(Uri.parse(src.uri), Uri.parse(parent), destDir)
                if (moved != null) {
                    ctx.remember(destDir, FileEntry(moved.toString(), src.name, src.isDirectory, parentUri = destDir.toString()))
                    if (src.size > 0) ctx.progress.bytes(src.size)
                    ctx.progress.itemDone()
                    return Outcome(moved.toString(), src.name, true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Fall through to copy + delete.
            }
        }
        val o = copy(ctx, src, destDir)
        if (o.complete) {
            try {
                fs.delete(Uri.parse(src.uri))
            } catch (e: Exception) {
                ctx.errors.add("${src.name}: copied, but the original could not be removed (${e.message})")
                return o.copy(complete = false)
            }
        }
        return o
    }

    companion object {
        const val BUFFER = 128 * 1024
    }
}
