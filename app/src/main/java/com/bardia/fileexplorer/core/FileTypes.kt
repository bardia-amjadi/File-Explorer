package com.bardia.fileexplorer.core

object FileTypes {
    private val documentExt = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "txt", "rtf", "md",
        "csv", "epub", "pages", "numbers", "key", "tex"
    )
    private val archiveExt = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "jar", "zst")
    private val apkExt = setOf("apk", "apks", "xapk", "apkm")
    private val imageExt = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "svg", "ico")
    private val videoExt = setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "flv", "wmv", "ts")
    private val audioExt = setOf("mp3", "wav", "flac", "ogg", "oga", "m4a", "aac", "opus", "amr", "mid", "midi", "wma")
    private val textExt = setOf(
        "txt", "md", "json", "xml", "csv", "log", "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "html",
        "htm", "css", "scss", "yml", "yaml", "ini", "cfg", "conf", "c", "h", "cpp", "hpp", "cs", "go", "rs",
        "rb", "php", "sh", "bat", "gradle", "properties", "sql", "toml", "tex", "srt", "gitignore", "swift", "lua"
    )

    fun categoryOf(extension: String, mime: String?): FileCategory {
        val ext = extension.lowercase()
        val m = mime?.lowercase().orEmpty()
        return when {
            ext in apkExt || m == "application/vnd.android.package-archive" -> FileCategory.APKS
            m.startsWith("image/") || ext in imageExt -> FileCategory.IMAGES
            m.startsWith("video/") || ext in videoExt -> FileCategory.VIDEOS
            m.startsWith("audio/") || ext in audioExt -> FileCategory.AUDIO
            ext in archiveExt || m == "application/zip" || m == "application/x-rar-compressed" -> FileCategory.ARCHIVES
            ext in documentExt || m == "application/pdf" || m.startsWith("application/vnd.openxmlformats") ||
                m.startsWith("application/msword") || m.startsWith("application/vnd.ms-") -> FileCategory.DOCUMENTS
            else -> FileCategory.OTHER
        }
    }

    fun isZip(entry: FileEntry): Boolean =
        !entry.isDirectory && (entry.extension == "zip" || entry.extension == "jar" || entry.mime == "application/zip")

    fun isTextLike(extension: String, mime: String?): Boolean {
        val m = mime?.lowercase().orEmpty()
        return extension.lowercase() in textExt || m.startsWith("text/") || m == "application/json" ||
            m == "application/xml" || m == "application/javascript" || m == "application/x-sh"
    }

    fun typeLabel(entry: FileEntry): String = when {
        entry.isDirectory -> "File folder"
        entry.extension.isNotEmpty() -> "${entry.extension.uppercase()} file"
        else -> "File"
    }

    /** Sort rank of a type group: folders first, then categories in declaration order. */
    fun groupRank(entry: FileEntry): Int = if (entry.isDirectory) -1 else entry.category.ordinal
    fun groupLabel(entry: FileEntry): String = if (entry.isDirectory) "Folders" else entry.category.label

    private val mimeByExt = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
        "webp" to "image/webp", "bmp" to "image/bmp", "heic" to "image/heic", "heif" to "image/heif",
        "avif" to "image/avif", "svg" to "image/svg+xml", "ico" to "image/x-icon",
        "mp4" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm", "avi" to "video/x-msvideo",
        "mov" to "video/quicktime", "3gp" to "video/3gpp", "m4v" to "video/x-m4v",
        "mp3" to "audio/mpeg", "wav" to "audio/x-wav", "flac" to "audio/flac", "ogg" to "audio/ogg",
        "oga" to "audio/ogg", "m4a" to "audio/mp4", "aac" to "audio/aac", "opus" to "audio/opus",
        "pdf" to "application/pdf", "txt" to "text/plain", "md" to "text/markdown", "csv" to "text/csv",
        "html" to "text/html", "htm" to "text/html", "css" to "text/css", "json" to "application/json",
        "xml" to "application/xml", "js" to "application/javascript",
        "zip" to "application/zip", "jar" to "application/java-archive", "rar" to "application/x-rar-compressed",
        "7z" to "application/x-7z-compressed", "tar" to "application/x-tar", "gz" to "application/gzip",
        "apk" to "application/vnd.android.package-archive",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    )

    /** Best-effort MIME type from an extension (pure; Android's MimeTypeMap is consulted separately at open time). */
    fun guessMime(extension: String): String? = mimeByExt[extension.lowercase()]

    /** The provider-supplied MIME type unless it is missing/generic, in which case the extension decides. */
    fun effectiveMime(entry: FileEntry): String? {
        val m = entry.mime
        return if (m.isNullOrBlank() || m == "application/octet-stream") guessMime(entry.extension) ?: m else m
    }
}
