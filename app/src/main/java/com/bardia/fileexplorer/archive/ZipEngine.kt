package com.bardia.fileexplorer.archive

import android.net.Uri
import com.bardia.fileexplorer.core.FileEntry
import com.bardia.fileexplorer.core.FileSorter
import com.bardia.fileexplorer.core.NameUtils
import com.bardia.fileexplorer.operations.TransferContext
import com.bardia.fileexplorer.operations.TransferEngine
import com.bardia.fileexplorer.storage.FileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Path validation for archive entries. This is the Zip Slip / path traversal defence. */
object ZipPaths {
    /**
     * Converts an archive entry name into safe path segments, or returns null if the entry must be rejected.
     * Rejected: absolute paths, Windows drive paths, any ".." segment, NUL bytes, and names that normalise to
     * nothing. Backslashes are treated as separators (some Windows zip tools emit them), "." and empty
     * segments are dropped. Because extraction creates one folder/file per validated segment under the chosen
     * destination (never by joining strings into a path), an entry cannot escape the destination.
     */
    fun sanitize(rawName: String): List<String>? {
        if (rawName.isEmpty() || rawName.contains('\u0000')) return null
        val name = rawName.replace('\\', '/')
        if (name.startsWith("/")) return null
        if (name.length >= 2 && name[1] == ':' && name[0].isLetter()) return null
        val segments = ArrayList<String>()
        for (seg in name.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> return null
                else -> segments.add(seg)
            }
        }
        return segments.ifEmpty { null }
    }

    /** True when [normalizedPath] equals one of [selected] or lies inside a selected folder. */
    fun isSelected(normalizedPath: String, selected: Set<String>): Boolean =
        selected.any { normalizedPath == it || normalizedPath.startsWith("$it/") }
}

data class ZipEntryInfo(
    /** Normalised path with '/' separators (no trailing slash); empty if unsafe. */
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val compressedSize: Long,
    val modified: Long,
    val unsafe: Boolean
) {
    val name: String get() = path.substringAfterLast('/')
}

data class ZipListing(val entries: List<ZipEntryInfo>, val truncated: Boolean)

/** One row of the archive browser: a real entry or an implicit folder derived from entry paths. */
data class ZipNode(val name: String, val path: String, val isDirectory: Boolean, val size: Long, val modified: Long, val itemCount: Int)

object ZipTree {
    /** Direct children of [prefix] ("" for the root, otherwise "dir/sub"), folders first then by name. */
    fun children(entries: List<ZipEntryInfo>, prefix: String): List<ZipNode> {
        val base = if (prefix.isEmpty()) "" else "$prefix/"
        val dirs = LinkedHashMap<String, IntArray>() // name -> [count, ...]
        val dirSizes = HashMap<String, Long>()
        val dirModified = HashMap<String, Long>()
        val files = ArrayList<ZipNode>()
        for (e in entries) {
            if (e.unsafe || !e.path.startsWith(base) || e.path == prefix) continue
            val rest = e.path.removePrefix(base)
            if (rest.isEmpty()) continue
            val slash = rest.indexOf('/')
            if (slash < 0) {
                if (e.isDirectory) dirs.getOrPut(rest) { IntArray(1) }
                else files.add(ZipNode(rest, e.path, false, e.size, e.modified, 0))
            } else {
                val dirName = rest.substring(0, slash)
                val counter = dirs.getOrPut(dirName) { IntArray(1) }
                if (!e.isDirectory) {
                    counter[0]++
                    dirSizes[dirName] = (dirSizes[dirName] ?: 0L) + e.size.coerceAtLeast(0)
                }
                dirModified[dirName] = maxOf(dirModified[dirName] ?: 0L, e.modified)
            }
        }
        val folders = dirs.map { (n, c) -> ZipNode(n, base + n, true, dirSizes[n] ?: 0L, dirModified[n] ?: 0L, c[0]) }
        return folders.sortedWith { a, b -> FileSorter.naturalCompare(a.name, b.name) } +
            files.sortedWith { a, b -> FileSorter.naturalCompare(a.name, b.name) }
    }
}

class ZipEngine(private val fs: FileSystem) {

    /** Reads the entry table of [zipUri] without extracting anything. */
    suspend fun list(zipUri: Uri): ZipListing {
        val out = ArrayList<ZipEntryInfo>()
        var truncated = false
        try {
            ZipInputStream(BufferedInputStream(fs.openInput(zipUri))).use { zin ->
                var next: ZipEntry? = zin.nextEntry
                if (next == null) throw ZipException("The file is empty or is not a ZIP archive")
                while (next != null) {
                    val e: ZipEntry = next
                    currentCoroutineContext().ensureActive()
                    if (out.size >= MAX_ENTRIES) {
                        truncated = true
                        break
                    }
                    // Entries written in streaming mode have no size until their data has been read.
                    if (e.size < 0) {
                        val buf = ByteArray(32 * 1024)
                        while (zin.read(buf) >= 0) currentCoroutineContext().ensureActive()
                    }
                    val segs = ZipPaths.sanitize(e.name)
                    out.add(
                        ZipEntryInfo(
                            path = segs?.joinToString("/") ?: e.name.take(200),
                            isDirectory = e.isDirectory,
                            size = e.size, compressedSize = e.compressedSize,
                            modified = e.time.coerceAtLeast(0L), unsafe = segs == null
                        )
                    )
                    next = zin.nextEntry
                }
            }
        } catch (e: ZipException) {
            throw IOException("The archive is corrupted or is not a valid ZIP file (${e.message})", e)
        }
        return ZipListing(out, truncated)
    }

    /**
     * Extracts [zip] into [destDir]. When [selected] is non-null only those entries (and everything under selected
     * folders) are extracted. Unsafe entries are skipped and reported; existing files go through the normal
     * conflict prompt. Progress is based on compressed bytes consumed, which is accurate for streaming.
     */
    suspend fun extract(ctx: TransferContext, zip: FileEntry, destDir: Uri, selected: Set<String>?) {
        if (zip.size > 0) ctx.progress.total(zip.size, 0)
        var extractedBytes = 0L
        var count = 0
        try {
            val counting = CountingInputStream(fs.openInput(Uri.parse(zip.uri))) { ctx.progress.bytes(it) }
            ZipInputStream(BufferedInputStream(counting)).use { zin ->
                var next: ZipEntry? = zin.nextEntry
                if (next == null) throw ZipException("The file is empty or is not a ZIP archive")
                while (next != null) {
                    val e: ZipEntry = next
                    currentCoroutineContext().ensureActive()
                    if (++count > MAX_ENTRIES) {
                        ctx.errors.add("Stopped after $MAX_ENTRIES entries (safety limit)")
                        break
                    }
                    val segs = ZipPaths.sanitize(e.name)
                    if (segs == null) {
                        ctx.errors.add("Blocked unsafe entry: ${e.name.take(120)}")
                    } else if (selected == null || ZipPaths.isSelected(segs.joinToString("/"), selected)) {
                        if (e.isDirectory) {
                            ensureDirectory(ctx, destDir, segs)
                        } else {
                            val parent = ensureDirectory(ctx, destDir, segs.dropLast(1))
                            if (parent != null) {
                                ctx.progress.current(segs.last())
                                extractedBytes += writeEntry(ctx, parent, segs.last(), zin, e)
                                if (extractedBytes > MAX_TOTAL_BYTES) {
                                    throw IOException("Stopped: the archive expands beyond the safety limit of ${MAX_TOTAL_BYTES / (1024 * 1024 * 1024)} GB")
                                }
                            }
                        }
                    }
                    next = zin.nextEntry
                }
            }
        } catch (e: ZipException) {
            throw IOException("The archive is corrupted or is not a valid ZIP file (${e.message})", e)
        }
    }

    /** Walks/creates [segments] below [root] one validated segment at a time. Null if blocked by a file. */
    private suspend fun ensureDirectory(ctx: TransferContext, root: Uri, segments: List<String>): Uri? {
        var cur = root
        for (seg in segments) {
            val existing = ctx.children(cur)[seg.lowercase()]
            cur = when {
                existing != null && existing.isDirectory -> Uri.parse(existing.uri)
                existing != null -> {
                    ctx.errors.add("Cannot create folder \"$seg\": a file with that name already exists")
                    return null
                }
                else -> {
                    val created = ctx.fs.createDirectory(cur, seg)
                    ctx.registerCreated(cur, created, seg, true, ctx.isNewDir(cur))
                    created
                }
            }
        }
        return cur
    }

    private suspend fun writeEntry(ctx: TransferContext, parent: Uri, name: String, zin: ZipInputStream, entry: ZipEntry): Long {
        val finalName = ctx.prepareTarget(parent, name, false, entry.size, entry.time) ?: return 0L
        val dest = ctx.fs.createFile(parent, finalName, com.bardia.fileexplorer.core.FileTypes.guessMime(finalName.substringAfterLast('.', "")))
        ctx.registerCreated(parent, dest, finalName, false, ctx.isNewDir(parent))
        var written = 0L
        try {
            ctx.fs.openOutput(dest).use { out ->
                val buf = ByteArray(TransferEngine.BUFFER)
                while (true) {
                    val n = zin.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    written += n
                    currentCoroutineContext().ensureActive()
                }
                out.flush()
            }
        } catch (e: CancellationException) {
            discard(dest)
            throw e
        } catch (e: IOException) {
            discard(dest)
            throw FileSystem.wrapIo(e)
        }
        ctx.progress.itemDone()
        return written
    }

    /** Creates a ZIP named [archiveName] in [destDir] from [sources]. Returns the created archive's URI string, or null if skipped. */
    suspend fun create(ctx: TransferContext, sources: List<FileEntry>, destDir: Uri, archiveName: String): String? {
        val finalName = ctx.prepareTarget(destDir, archiveName, false, -1, 0) ?: return null
        val archive = ctx.fs.createFile(destDir, finalName, "application/zip")
        ctx.registerCreated(destDir, archive, finalName, false, false)
        try {
            ZipOutputStream(BufferedOutputStream(ctx.fs.openOutput(archive))).use { zos ->
                val usedTop = HashSet<String>()
                for (src in sources) {
                    currentCoroutineContext().ensureActive()
                    val top = NameUtils.uniqueName(src.name, usedTop, hasExtension = !src.isDirectory)
                    usedTop.add(top)
                    addEntry(ctx, zos, src, top, archive.toString(), 0)
                }
            }
        } catch (e: CancellationException) {
            discard(archive)
            throw e
        } catch (e: IOException) {
            discard(archive)
            throw FileSystem.wrapIo(e)
        }
        return archive.toString()
    }

    private suspend fun addEntry(ctx: TransferContext, zos: ZipOutputStream, src: FileEntry, path: String, archiveUri: String, depth: Int) {
        currentCoroutineContext().ensureActive()
        if (src.uri == archiveUri || depth > 64) return // never put the archive inside itself
        ctx.progress.current(src.name)
        if (src.isDirectory) {
            val ze = ZipEntry("$path/")
            if (src.modified > 0) ze.time = src.modified
            zos.putNextEntry(ze)
            zos.closeEntry()
            val children = try {
                ctx.fs.list(Uri.parse(src.uri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ctx.errors.add("${src.name}: folder could not be read (${e.message})")
                emptyList()
            }
            val used = HashSet<String>()
            for (child in children) {
                val childName = NameUtils.uniqueName(child.name, used, hasExtension = !child.isDirectory)
                used.add(childName)
                addEntry(ctx, zos, child, "$path/$childName", archiveUri, depth + 1)
            }
        } else {
            val ze = ZipEntry(path)
            if (src.modified > 0) ze.time = src.modified
            zos.putNextEntry(ze)
            try {
                ctx.fs.openInput(Uri.parse(src.uri)).use { input ->
                    val buf = ByteArray(TransferEngine.BUFFER)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        zos.write(buf, 0, n)
                        ctx.progress.bytes(n.toLong())
                        currentCoroutineContext().ensureActive()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (e is FileSystem.InsufficientStorageException) throw e
                // Unreadable source file: abort the entry cleanly and report; the archive stays valid.
                ctx.errors.add("${src.name}: could not be read (${e.message})")
            }
            zos.closeEntry()
            ctx.progress.itemDone()
        }
    }

    private suspend fun discard(uri: Uri) = withContext(NonCancellable) {
        try {
            fs.delete(uri)
        } catch (e: Exception) {
            // Best effort.
        }
    }

    private class CountingInputStream(input: InputStream, private val onBytes: (Long) -> Unit) : FilterInputStream(input) {
        override fun read(): Int = super.read().also { if (it >= 0) onBytes(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) onBytes(it.toLong()) }
    }

    companion object {
        const val MAX_ENTRIES = 200_000
        const val MAX_TOTAL_BYTES = 64L * 1024 * 1024 * 1024 // zip-bomb guard
    }
}
