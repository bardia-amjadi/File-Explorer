package com.bardia.fileexplorer.core

import android.provider.DocumentsContract

enum class SortField(val label: String) { NAME("Name"), MODIFIED("Date modified"), TYPE("Type"), SIZE("Size") }
enum class ViewMode(val label: String) { LIST("List"), GRID("Grid"), DETAILS("Details") }
enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }
enum class IconSize(val label: String, val listDp: Int, val gridDp: Int) {
    SMALL("Small", 32, 72),
    MEDIUM("Medium", 40, 104),
    LARGE("Large", 52, 136)
}
enum class StartDestination(val label: String) { HOME("Home"), BROWSE("Browse (last location)") }

enum class FileCategory(val label: String) {
    IMAGES("Images"), VIDEOS("Videos"), AUDIO("Audio"), DOCUMENTS("Documents"),
    ARCHIVES("Archives"), APKS("APK files"), OTHER("Other")
}

/** One step of a navigation trail: a document URI and its display name. */
data class Crumb(val uri: String, val name: String)

/**
 * A file or folder as exposed by a storage provider. [uri] is a string so the model is trivially
 * serialisable and usable in plain JVM unit tests (android.net.Uri is not available there).
 *
 * [flags] are raw [DocumentsContract.Document] flags; the capability helpers below translate them so the
 * UI only offers operations the provider actually supports.
 */
data class FileEntry(
    val uri: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long = -1L,
    val modified: Long = 0L,
    val mime: String? = null,
    val flags: Int = 0,
    val parentUri: String? = null,
    val isLocal: Boolean = false,
    /** Human readable location (e.g. "Documents/Work") shown in search results and recents. */
    val displayPath: String? = null
) {
    val extension: String
        get() {
            if (isDirectory) return ""
            val i = name.lastIndexOf('.')
            return if (i <= 0 || i == name.length - 1) "" else name.substring(i + 1).lowercase()
        }
    val isHidden: Boolean get() = name.startsWith(".")
    val category: FileCategory get() = if (isDirectory) FileCategory.OTHER else FileTypes.categoryOf(extension, mime)

    val canDelete: Boolean get() = isLocal || flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0
    val canRename: Boolean get() = isLocal || flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0
    val supportsMove: Boolean get() = flags and DocumentsContract.Document.FLAG_SUPPORTS_MOVE != 0
    val canWriteInto: Boolean
        get() = isDirectory && (isLocal || flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0)
    val canWriteContent: Boolean get() = !isDirectory && (isLocal || flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0)
    /** Entries without a known parent (media-store results, ...) can be opened/shared but not moved or deleted. */
    val isManageable: Boolean get() = parentUri != null
}

data class Settings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val viewMode: ViewMode = ViewMode.LIST,
    val iconSize: IconSize = IconSize.MEDIUM,
    val sortField: SortField = SortField.NAME,
    val sortAscending: Boolean = true,
    val foldersFirst: Boolean = true,
    val groupByType: Boolean = false,
    val showHidden: Boolean = false,
    val startDestination: StartDestination = StartDestination.HOME,
    val searchIncludeHidden: Boolean = false,
    val recentLimit: Int = 50,
    val trashRetentionDays: Int = 30
)

/** Why a folder could not be shown; drives the recovery actions in the UI. */
enum class BrowseErrorKind { PERMISSION_DENIED, NOT_FOUND, UNAVAILABLE, OTHER }
data class BrowseError(val kind: BrowseErrorKind, val message: String)
