package com.bardia.fileexplorer.tools

import android.net.Uri
import com.bardia.fileexplorer.core.FileEntry
import com.bardia.fileexplorer.storage.FileSystem

/**
 * Result of comparing two files. [contentVerified] is true only when both files were fully hashed; otherwise the
 * verdict is based on metadata alone and the UI says so explicitly.
 */
data class CompareResult(
    val sameName: Boolean,
    val sameSize: Boolean,
    val sameModified: Boolean,
    val contentVerified: Boolean,
    /** null = cannot tell from metadata alone. */
    val identicalContent: Boolean?,
    val hashA: String?,
    val hashB: String?
) {
    val verdict: String
        get() = when {
            contentVerified && identicalContent == true -> "Identical content (verified with SHA-256)"
            contentVerified -> "Different content (verified with SHA-256)"
            !sameSize -> "Different (sizes differ) - metadata only"
            sameSize && sameModified -> "Probably the same (same size and modified time) - metadata only, not verified"
            else -> "Same size; content not verified - metadata only"
        }
}

class FileComparer(private val fs: FileSystem) {
    fun metadata(a: FileEntry, b: FileEntry) = CompareResult(
        sameName = a.name == b.name,
        sameSize = a.size >= 0 && a.size == b.size,
        sameModified = a.modified > 0 && a.modified == b.modified,
        contentVerified = false,
        identicalContent = if (a.size >= 0 && b.size >= 0 && a.size != b.size) false else null,
        hashA = null, hashB = null
    )

    /** Streams both files through SHA-256 (unless sizes already differ, which proves they are different). */
    suspend fun verifyContent(a: FileEntry, b: FileEntry, onBytes: (Long) -> Unit): CompareResult {
        val base = metadata(a, b)
        if (a.size >= 0 && b.size >= 0 && a.size != b.size) {
            return base.copy(contentVerified = true, identicalContent = false)
        }
        val ha = fs.openInput(Uri.parse(a.uri)).use { Hashing.sha256(it, onBytes) }
        val hb = fs.openInput(Uri.parse(b.uri)).use { Hashing.sha256(it, onBytes) }
        return base.copy(contentVerified = true, identicalContent = ha == hb, hashA = ha, hashB = hb)
    }
}
