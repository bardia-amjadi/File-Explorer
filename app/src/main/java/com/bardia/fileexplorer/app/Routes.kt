package com.bardia.fileexplorer.app

import android.net.Uri
import com.bardia.fileexplorer.core.FileCategory
import com.bardia.fileexplorer.core.FileEntry
import com.bardia.fileexplorer.core.FileTypes

object Routes {
    const val HOME = "home"
    const val BROWSE = "browse"
    const val RECENT = "recent"
    const val FAVORITES = "favorites"
    const val CATEGORIES = "categories"
    const val STORAGE = "storage"
    const val TOOLS = "tools"
    const val SETTINGS = "settings"

    const val SEARCH = "search"
    const val COMPARE = "compare"
    const val ANALYSIS = "analysis"
    const val LARGE_FILES = "large_files"
    const val DUPLICATES = "duplicates"
    const val TRASH = "trash"

    const val ARG = "arg"
    const val VIEWER = "viewer/{$ARG}"
    const val ARCHIVE = "archive/{$ARG}"
    const val APK = "apk/{$ARG}"
    const val CATEGORY = "category/{$ARG}"

    /** Top-level destinations in navigation order. */
    val topLevel = listOf(HOME, BROWSE, RECENT, FAVORITES, CATEGORIES, STORAGE, TOOLS, SETTINGS)

    fun viewer(uri: String) = "viewer/${Uri.encode(uri)}"
    fun archive(uri: String) = "archive/${Uri.encode(uri)}"
    fun apk(uri: String) = "apk/${Uri.encode(uri)}"
    fun category(c: FileCategory) = "category/${c.name}"

    /** Which in-app screen opens a file, or null when it should be handed to another app. */
    fun routeFor(entry: FileEntry): String? {
        if (entry.isDirectory) return null
        return when {
            FileTypes.isZip(entry) && entry.extension != "jar" -> archive(entry.uri)
            entry.category == FileCategory.APKS && entry.extension == "apk" -> apk(entry.uri)
            entry.category == FileCategory.IMAGES && entry.extension != "svg" -> viewer(entry.uri)
            entry.category == FileCategory.VIDEOS || entry.category == FileCategory.AUDIO -> viewer(entry.uri)
            entry.extension == "pdf" || entry.mime == "application/pdf" -> viewer(entry.uri)
            FileTypes.isTextLike(entry.extension, entry.mime) -> viewer(entry.uri)
            else -> null
        }
    }
}
