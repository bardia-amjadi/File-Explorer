package com.bardia.fileexplorer.search

import com.bardia.fileexplorer.core.Crumb
import com.bardia.fileexplorer.core.FileCategory
import com.bardia.fileexplorer.core.FileEntry
import com.bardia.fileexplorer.core.FileTypes
import com.bardia.fileexplorer.storage.TreeWalker
import com.bardia.fileexplorer.storage.WalkStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

data class SearchCriteria(
    val text: String = "",
    /** Lower-case extensions without the dot, e.g. {"jpg","png"}. */
    val extensions: Set<String> = emptySet(),
    /** Exact MIME ("application/pdf"), wildcard (image slash star) or a substring such as "pdf". */
    val mime: String = "",
    val category: FileCategory? = null,
    val minSize: Long? = null,
    val maxSize: Long? = null,
    val modifiedAfter: Long? = null,
    val modifiedBefore: Long? = null,
    val includeHidden: Boolean = false
) {
    /** Filters that only make sense for files; when any is set folders are excluded from the results. */
    val fileOnly: Boolean
        get() = extensions.isNotEmpty() || mime.isNotBlank() || category != null || minSize != null || maxSize != null

    val isEmpty: Boolean
        get() = text.isBlank() && !fileOnly && modifiedAfter == null && modifiedBefore == null
}

data class SearchResult(val entry: FileEntry, val trail: List<Crumb>, val rootName: String)

/** Pure matching logic (unit tested). */
object SearchMatcher {
    fun matches(entry: FileEntry, c: SearchCriteria): Boolean {
        if (!c.includeHidden && entry.isHidden) return false
        if (entry.isDirectory && c.fileOnly) return false
        if (c.text.isNotBlank()) {
            val name = entry.name.lowercase()
            val tokens = c.text.lowercase().split(' ').filter { it.isNotEmpty() }
            if (!tokens.all { name.contains(it) }) return false
        }
        if (c.extensions.isNotEmpty() && entry.extension !in c.extensions) return false
        if (c.mime.isNotBlank()) {
            val m = FileTypes.effectiveMime(entry)?.lowercase() ?: return false
            val q = c.mime.lowercase().trim()
            val ok = when {
                q.endsWith("/*") -> m.startsWith(q.removeSuffix("*"))
                q.contains('/') -> m == q
                else -> m.contains(q)
            }
            if (!ok) return false
        }
        if (c.category != null && entry.category != c.category) return false
        if (c.minSize != null && (entry.size < 0 || entry.size < c.minSize)) return false
        if (c.maxSize != null && (entry.size < 0 || entry.size > c.maxSize)) return false
        if (c.modifiedAfter != null && (entry.modified <= 0 || entry.modified < c.modifiedAfter)) return false
        if (c.modifiedBefore != null && (entry.modified <= 0 || entry.modified > c.modifiedBefore)) return false
        return true
    }

    /** Parses "jpg, .PNG png" into {"jpg","png"}. */
    fun parseExtensions(input: String): Set<String> =
        input.split(',', ' ', ';').map { it.trim().trimStart('.').lowercase() }.filter { it.isNotEmpty() }.toSet()
}

class SearchEngine(private val walker: TreeWalker) {
    /**
     * Searches the given granted folders in parallel-free sequence (one provider at a time keeps IO predictable)
     * and emits matches incrementally. Cancelling the collecting coroutine stops the traversal.
     */
    fun search(criteria: SearchCriteria, scopes: List<Crumb>, stats: WalkStats): Flow<SearchResult> = channelFlow {
        for (scope in scopes) {
            walker.walk(scope, criteria.includeHidden, stats)
                .filter { SearchMatcher.matches(it.entry, criteria) }
                .map { SearchResult(it.entry.copy(displayPath = it.path), it.trail, scope.name) }
                .collect { send(it) }
        }
    }
}
