package com.bardia.fileexplorer.storage

import android.net.Uri
import com.bardia.fileexplorer.core.Crumb
import com.bardia.fileexplorer.core.FileEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.atomic.AtomicInteger

/** An entry found while walking, with the trail of folders (root ... parent) that leads to it. */
data class WalkItem(val entry: FileEntry, val trail: List<Crumb>) {
    val path: String get() = trail.joinToString("/") { it.name }
}

/** Counters shared with the caller so the UI can honestly report what could not be scanned. */
class WalkStats {
    val skippedFolders = AtomicInteger(0)
    val scannedFolders = AtomicInteger(0)
}

/**
 * Iterative, cancellable depth-first traversal of a granted folder. It only ever lists what the provider
 * returns, so it never goes beyond the user's grants. Cycle protection: every folder URI is visited once and the
 * depth is capped (document providers can expose symlink loops).
 */
class TreeWalker(private val fs: FileSystem) {
    fun walk(root: Crumb, includeHidden: Boolean, stats: WalkStats = WalkStats()): Flow<WalkItem> = flow {
        val visited = HashSet<String>()
        val stack = ArrayDeque<List<Crumb>>()
        stack.addLast(listOf(root))
        while (stack.isNotEmpty()) {
            val trail = stack.removeLast()
            val dir = trail.last()
            if (trail.size > MAX_DEPTH || !visited.add(dir.uri)) continue
            val children = try {
                fs.list(Uri.parse(dir.uri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                stats.skippedFolders.incrementAndGet()
                continue
            }
            stats.scannedFolders.incrementAndGet()
            for (c in children) {
                if (!includeHidden && c.isHidden) continue
                emit(WalkItem(c, trail))
                if (c.isDirectory) stack.addLast(trail + Crumb(c.uri, c.name))
            }
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        const val MAX_DEPTH = 64
    }
}
