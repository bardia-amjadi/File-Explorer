package com.bardia.fileexplorer.tools

import android.net.Uri
import com.bardia.fileexplorer.core.FileEntry
import com.bardia.fileexplorer.storage.FileSystem
import com.bardia.fileexplorer.storage.TreeWalker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class SizeResult(val bytes: Long, val files: Int, val folders: Int, val inaccessible: Int)

/**
 * Recursive folder size with progress and cancellation. Each folder is visited once (cycle guard) and depth is
 * capped, so symlink loops or malicious providers cannot make it run forever. Unreadable sub-folders are counted
 * and reported rather than silently ignored.
 */
class FolderSizer(private val fs: FileSystem) {
    suspend fun compute(root: FileEntry, onProgress: (SizeResult) -> Unit): SizeResult {
        if (!root.isDirectory) return SizeResult(root.size.coerceAtLeast(0), 1, 0, 0)
        var bytes = 0L
        var files = 0
        var folders = 0
        var bad = 0
        var lastReport = 0L
        val visited = HashSet<String>()
        val stack = ArrayDeque<Pair<String, Int>>()
        stack.addLast(root.uri to 0)
        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (uri, depth) = stack.removeLast()
            if (depth > TreeWalker.MAX_DEPTH || !visited.add(uri)) continue
            val children = try {
                fs.list(Uri.parse(uri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                bad++
                continue
            }
            folders++
            for (c in children) {
                if (c.isDirectory) stack.addLast(c.uri to depth + 1) else {
                    files++
                    if (c.size > 0) bytes += c.size
                }
            }
            val now = System.nanoTime()
            if (now - lastReport > 150_000_000L) {
                lastReport = now
                onProgress(SizeResult(bytes, files, folders, bad))
            }
        }
        return SizeResult(bytes, files, folders - 1, bad).also(onProgress)
    }
}
