package com.bardia.fileexplorer.core

/** Pure sorting/filtering used by the browser, search results and tools. */
object FileSorter {
    /** Natural, case-insensitive name comparison ("file2" < "file10"). */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var si = i
                while (si < a.length && a[si] == '0') si++
                var sj = j
                while (sj < b.length && b[sj] == '0') sj++
                var ei = si
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = sj
                while (ej < b.length && b[ej].isDigit()) ej++
                val lenA = ei - si
                val lenB = ej - sj
                if (lenA != lenB) return lenA.compareTo(lenB)
                val cmp = a.substring(si, ei).compareTo(b.substring(sj, ej))
                if (cmp != 0) return cmp
                i = ei
                j = ej
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }

    fun comparator(field: SortField, ascending: Boolean, foldersFirst: Boolean, groupByType: Boolean): Comparator<FileEntry> {
        val base: Comparator<FileEntry> = when (field) {
            SortField.NAME -> Comparator { a, b -> naturalCompare(a.name, b.name) }
            SortField.SIZE -> Comparator { a, b -> a.size.compareTo(b.size) }
            SortField.MODIFIED -> Comparator { a, b -> a.modified.compareTo(b.modified) }
            SortField.TYPE -> Comparator { a, b ->
                val c = a.extension.compareTo(b.extension)
                if (c != 0) c else naturalCompare(a.name, b.name)
            }
        }
        val directed = if (ascending) base else base.reversed()
        // Ties always fall back to ascending name so the order is stable and predictable.
        val withTie = directed.thenComparator { a, b -> naturalCompare(a.name, b.name) }
        return when {
            groupByType -> compareBy<FileEntry> { FileTypes.groupRank(it) }.then(withTie)
            foldersFirst -> compareBy<FileEntry> { if (it.isDirectory) 0 else 1 }.then(withTie)
            else -> withTie
        }
    }

    fun sort(entries: List<FileEntry>, field: SortField, ascending: Boolean, foldersFirst: Boolean = true, groupByType: Boolean = false): List<FileEntry> =
        entries.sortedWith(comparator(field, ascending, foldersFirst, groupByType))

    fun visible(entries: List<FileEntry>, showHidden: Boolean): List<FileEntry> =
        if (showHidden) entries else entries.filterNot { it.isHidden }
}
