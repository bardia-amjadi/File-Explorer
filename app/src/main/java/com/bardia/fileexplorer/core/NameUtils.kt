package com.bardia.fileexplorer.core

object NameUtils {
    /** Characters that providers commonly reject or that would break path semantics. */
    private val illegal = charArrayOf('/', '\\', '\u0000')

    /** Returns an error message for an invalid file name, or null when it is acceptable. */
    fun validate(name: String): String? = when {
        name.isBlank() -> "Name cannot be empty"
        name == "." || name == ".." -> "Name is reserved"
        name.any { it in illegal } -> "Name cannot contain / or \\"
        name.length > 255 -> "Name is too long"
        else -> null
    }

    /**
     * Produces "name (1).ext", "name (2).ext", ... that does not collide (case-insensitively) with [existing].
     * Folders pass hasExtension = false so a dot in the name is not treated as an extension.
     */
    fun uniqueName(name: String, existing: Set<String>, hasExtension: Boolean = true): String {
        val lower = existing.map { it.lowercase() }.toHashSet()
        if (name.lowercase() !in lower) return name
        val dot = if (hasExtension) name.lastIndexOf('.') else -1
        val (stem, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        var n = 1
        while (true) {
            val candidate = "$stem ($n)$ext"
            if (candidate.lowercase() !in lower) return candidate
            n++
        }
    }
}
