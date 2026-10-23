package com.bardia.fileexplorer.tools

import android.net.Uri
import com.bardia.fileexplorer.core.Crumb
import com.bardia.fileexplorer.core.FileCategory
import com.bardia.fileexplorer.storage.FileSystem
import com.bardia.fileexplorer.storage.TreeWalker
import com.bardia.fileexplorer.storage.WalkItem
import com.bardia.fileexplorer.storage.WalkStats
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect

data class CategoryStats(val bytes: Long, val count: Int)
data class AnalysisResult(
    val byCategory: Map<FileCategory, CategoryStats>,
    val totalBytes: Long,
    val files: Int,
    val folders: Int,
    val skippedFolders: Int
)

data class DuplicateGroup(val size: Long, val items: List<WalkItem>, val verified: Boolean, val hash: String?)

/**
 * Whole-tree scans used by the Tools section. They only cover folders the user granted access to; results always
 * report how many folders could not be read so the UI never implies full-device coverage.
 */
class StorageScanner(private val walker: TreeWalker, private val fs: FileSystem) {

    suspend fun analyze(roots: List<Crumb>, includeHidden: Boolean, onProgress: (AnalysisResult) -> Unit): AnalysisResult {
        val bytes = HashMap<FileCategory, Long>()
        val counts = HashMap<FileCategory, Int>()
        var files = 0
        var folders = 0
        var last = 0L
        val stats = WalkStats()
        fun snapshot() = AnalysisResult(
            FileCategory.values().associateWith { CategoryStats(bytes[it] ?: 0L, counts[it] ?: 0) },
            bytes.values.sum(), files, folders, stats.skippedFolders.get()
        )
        for (root in roots) {
            walker.walk(root, includeHidden, stats).collect { item ->
                if (item.entry.isDirectory) folders++ else {
                    files++
                    val cat = item.entry.category
                    bytes[cat] = (bytes[cat] ?: 0L) + item.entry.size.coerceAtLeast(0)
                    counts[cat] = (counts[cat] ?: 0) + 1
                }
                val now = System.nanoTime()
                if (now - last > 200_000_000L) {
                    last = now
                    onProgress(snapshot())
                }
            }
        }
        return snapshot().also(onProgress)
    }

    /** Largest files at or above [minBytes]; keeps only the [limit] biggest in memory. */
    suspend fun largeFiles(
        roots: List<Crumb>, includeHidden: Boolean, minBytes: Long, limit: Int,
        onProgress: (List<WalkItem>, WalkStats) -> Unit
    ): List<WalkItem> {
        val top = ArrayList<WalkItem>()
        val stats = WalkStats()
        var last = 0L
        for (root in roots) {
            walker.walk(root, includeHidden, stats).collect { item ->
                val e = item.entry
                if (!e.isDirectory && e.size >= minBytes) {
                    top.add(item)
                    if (top.size > limit * 2) {
                        top.sortByDescending { it.entry.size }
                        while (top.size > limit) top.removeAt(top.size - 1)
                    }
                }
                val now = System.nanoTime()
                if (now - last > 300_000_000L) {
                    last = now
                    onProgress(top.sortedByDescending { it.entry.size }.take(limit), stats)
                }
            }
        }
        return top.sortedByDescending { it.entry.size }.take(limit).also { onProgress(it, stats) }
    }

    /**
     * Finds duplicate candidates: files with equal size (>= [minBytes]). With [verify] each candidate is hashed
     * with streaming SHA-256 and only identical hashes are reported as verified duplicates; without it, groups
     * are marked unverified and must not be deleted on that evidence alone.
     */
    suspend fun duplicates(
        roots: List<Crumb>, includeHidden: Boolean, minBytes: Long, verify: Boolean,
        onPhase: (String) -> Unit, onBytes: (Long) -> Unit
    ): List<DuplicateGroup> {
        val bySize = HashMap<Long, MutableList<WalkItem>>()
        val stats = WalkStats()
        var seen = 0
        onPhase("Scanning folders...")
        for (root in roots) {
            walker.walk(root, includeHidden, stats).collect { item ->
                val e = item.entry
                if (!e.isDirectory && e.size >= minBytes && seen < MAX_FILES) {
                    seen++
                    bySize.getOrPut(e.size) { ArrayList() }.add(item)
                }
            }
        }
        val candidates = bySize.filter { it.value.size > 1 }
        if (!verify) {
            return candidates.map { (size, items) -> DuplicateGroup(size, items, false, null) }.sortedByDescending { it.size * (it.items.size - 1) }
        }
        val result = ArrayList<DuplicateGroup>()
        var done = 0
        for ((size, items) in candidates) {
            currentCoroutineContext().ensureActive()
            onPhase("Verifying ${++done} / ${candidates.size} groups...")
            val byHash = HashMap<String, MutableList<WalkItem>>()
            for (item in items) {
                val h = try {
                    fs.openInput(Uri.parse(item.entry.uri)).use { Hashing.sha256(it, onBytes) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    continue // unreadable: cannot be verified, so it is not reported
                }
                byHash.getOrPut(h) { ArrayList() }.add(item)
            }
            byHash.forEach { (hash, group) -> if (group.size > 1) result.add(DuplicateGroup(size, group, true, hash)) }
        }
        return result.sortedByDescending { it.size * (it.items.size - 1) }
    }

    companion object {
        private const val MAX_FILES = 500_000
    }
}
