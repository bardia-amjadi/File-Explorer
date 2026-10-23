@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.bardia.fileexplorer.ui.common

import android.content.Context
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bardia.fileexplorer.app.AppContainer
import com.bardia.fileexplorer.core.Crumb
import com.bardia.fileexplorer.core.FileEntry
import com.bardia.fileexplorer.core.FileTypes
import com.bardia.fileexplorer.core.NameUtils
import com.bardia.fileexplorer.design.ConfirmDialog
import com.bardia.fileexplorer.design.MenuAction
import com.bardia.fileexplorer.design.TextInputDialog
import com.bardia.fileexplorer.operations.OperationRequest
import com.bardia.fileexplorer.persistence.FavoriteItem
import com.bardia.fileexplorer.persistence.RecentItem
import com.bardia.fileexplorer.util.IntentUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.bardia.fileexplorer.app.Routes

/**
 * Central place for everything a user can do with files: it builds context menus, owns the dialogs those
 * actions need (rename, delete confirmation, destination picker, properties...) and submits the work to the
 * [com.bardia.fileexplorer.operations.OperationManager]. Every screen that lists files uses the same instance
 * type so behaviour is identical everywhere.
 */
@Stable
class EntryActions(
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val context: Context,
    private val notify: (String) -> Unit,
    private val navigate: (String) -> Unit,
    /** Opens a folder in the Browse screen. */
    private val showFolder: (List<Crumb>) -> Unit
) {
    sealed interface Dialog {
        data class Rename(val entry: FileEntry, val onDone: (FileEntry) -> Unit) : Dialog
        data class Delete(val entries: List<FileEntry>, val onDone: () -> Unit) : Dialog
        data class Pick(val kind: PickKind, val entries: List<FileEntry>, val onDone: () -> Unit, val selected: Set<String>? = null) : Dialog
        data class Properties(val entry: FileEntry) : Dialog
        data class NewFolder(val parentUri: String, val onDone: () -> Unit) : Dialog
        data class Compress(val entries: List<FileEntry>, val onDone: () -> Unit) : Dialog
    }

    enum class PickKind { COPY, MOVE, EXTRACT }

    var dialog by mutableStateOf<Dialog?>(null)
        private set

    fun dismiss() { dialog = null }

    // ------------------------------------------------------------ opening

    /** Opens a file in the right in-app viewer, or in another app. Folders are the caller's business. */
    fun open(entry: FileEntry, parentTrail: List<Crumb> = emptyList()) {
        if (entry.isDirectory) return
        scope.launch {
            container.metadata.addRecent(
                RecentItem(entry.uri, entry.name, entry.mime, entry.size, entry.modified, parentTrail, System.currentTimeMillis()),
                container.settings.value.recentLimit
            )
        }
        val route = Routes.routeFor(entry)
        if (route != null) navigate(route) else openExternally(entry)
    }

    fun openExternally(entry: FileEntry) {
        IntentUtil.open(context, entry)?.let(notify)
    }

    // ------------------------------------------------------------ actions

    fun copyTo(entries: List<FileEntry>, onDone: () -> Unit = {}) { dialog = Dialog.Pick(PickKind.COPY, entries, onDone) }
    fun moveTo(entries: List<FileEntry>, onDone: () -> Unit = {}) { dialog = Dialog.Pick(PickKind.MOVE, entries, onDone) }
    fun extract(zip: FileEntry) { dialog = Dialog.Pick(PickKind.EXTRACT, listOf(zip), {}) }
    fun extractSelected(zip: FileEntry, selected: Set<String>) { dialog = Dialog.Pick(PickKind.EXTRACT, listOf(zip), {}, selected) }
    fun delete(entries: List<FileEntry>, onDone: () -> Unit = {}) { dialog = Dialog.Delete(entries, onDone) }
    fun rename(entry: FileEntry, onDone: (FileEntry) -> Unit = {}) { dialog = Dialog.Rename(entry, onDone) }
    fun properties(entry: FileEntry) { dialog = Dialog.Properties(entry) }
    fun newFolder(parentUri: String, onDone: () -> Unit = {}) { dialog = Dialog.NewFolder(parentUri, onDone) }
    fun compress(entries: List<FileEntry>, onDone: () -> Unit = {}) { dialog = Dialog.Compress(entries, onDone) }

    fun share(entries: List<FileEntry>) {
        IntentUtil.share(context, entries)?.let(notify)
    }

    fun copyPath(entry: FileEntry) {
        IntentUtil.copyText(context, "Path", friendlyPath(entry.uri, entry.displayPath))
        notify("Path copied")
    }

    fun copyUri(entry: FileEntry) {
        IntentUtil.copyText(context, "URI", entry.uri)
        notify("URI copied")
    }

    fun toggleFavorite(entry: FileEntry, trail: List<Crumb>, isFavorite: Boolean) {
        scope.launch {
            if (isFavorite) {
                container.metadata.removeFavorite(entry.uri)
                notify("Removed from favorites")
            } else {
                // Folders store the trail *to* the folder so Favorites can navigate there directly.
                val t = if (entry.isDirectory && (trail.isEmpty() || trail.last().uri != entry.uri)) trail + Crumb(entry.uri, entry.name) else trail
                container.metadata.addFavorite(FavoriteItem(entry.uri, entry.name, entry.isDirectory, entry.mime, t, System.currentTimeMillis()))
                notify("Added to favorites")
            }
        }
    }

    // ------------------------------------------------------------ menus

    /** Context menu for one item. [trail] is the folder trail the item was listed from (may be empty). */
    fun menu(entry: FileEntry, trail: List<Crumb>, isFavorite: Boolean, extra: List<MenuAction> = emptyList()): List<MenuAction> {
        val manageable = entry.isManageable
        val list = ArrayList<MenuAction>()
        if (!entry.isDirectory) list.add(MenuAction("Open with...", Icons.Filled.OpenInNew) { openExternally(entry) })
        list.addAll(extra)
        if (!entry.isDirectory) list.add(MenuAction("Share", Icons.Filled.Share) { share(listOf(entry)) })
        list.add(MenuAction("Copy to...", Icons.Filled.ContentCopy, enabled = manageable) { copyTo(listOf(entry)) })
        list.add(MenuAction("Move to...", Icons.Filled.DriveFileMove, enabled = manageable && entry.canDelete) { moveTo(listOf(entry)) })
        list.add(MenuAction("Rename", Icons.Filled.DriveFileRenameOutline, enabled = manageable && entry.canRename) { rename(entry) })
        list.add(MenuAction("Compress to ZIP", Icons.Filled.FolderZip, enabled = manageable) { compress(listOf(entry)) })
        if (FileTypes.isZip(entry)) list.add(MenuAction("Extract to...", Icons.Filled.Unarchive, enabled = manageable) { extract(entry) })
        list.add(
            MenuAction(if (isFavorite) "Remove from favorites" else "Add to favorites", if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder) {
                toggleFavorite(entry, trail, isFavorite)
            }
        )
        list.add(MenuAction("Copy path", Icons.Filled.Link) { copyPath(entry) })
        list.add(MenuAction("Properties", Icons.Filled.Info) { properties(entry) })
        list.add(MenuAction("Delete", Icons.Filled.Delete, enabled = manageable && entry.canDelete, destructive = true) { delete(listOf(entry)) })
        return list
    }

    // ------------------------------------------------------------ dialogs

    @Composable
    fun Dialogs() {
        when (val d = dialog) {
            null -> Unit
            is Dialog.Rename -> TextInputDialog(
                title = "Rename", label = "New name", initial = d.entry.name, confirmLabel = "Rename",
                validate = { NameUtils.validate(it) }, selectBaseName = !d.entry.isDirectory,
                onConfirm = { name ->
                    dismiss()
                    scope.launch {
                        container.ops.rename(d.entry, name)
                            .onSuccess { d.onDone(it) }
                            .onFailure { notify(it.message ?: "Could not rename") }
                    }
                },
                onDismiss = ::dismiss
            )
            is Dialog.NewFolder -> TextInputDialog(
                title = "New folder", label = "Folder name", initial = "", confirmLabel = "Create",
                validate = { NameUtils.validate(it) },
                onConfirm = { name ->
                    dismiss()
                    scope.launch {
                        container.ops.createFolder(d.parentUri, name)
                            .onSuccess { d.onDone() }
                            .onFailure { notify(it.message ?: "Could not create folder") }
                    }
                },
                onDismiss = ::dismiss
            )
            is Dialog.Compress -> {
                val suggestion = if (d.entries.size == 1) d.entries[0].name.substringBeforeLast('.', d.entries[0].name) + ".zip" else "Archive.zip"
                TextInputDialog(
                    title = "Create ZIP archive", label = "Archive name", initial = suggestion, confirmLabel = "Create",
                    validate = { NameUtils.validate(it) }, selectBaseName = true,
                    onConfirm = { name0 ->
                        dismiss()
                        val name = if (name0.endsWith(".zip", ignoreCase = true)) name0 else "$name0.zip"
                        val parent = d.entries.firstOrNull()?.parentUri
                        if (parent == null) notify("Cannot create an archive here") else {
                            container.ops.submit(OperationRequest.Compress(d.entries, parent, name))
                            d.onDone()
                        }
                    },
                    onDismiss = ::dismiss
                )
            }
            is Dialog.Delete -> DeleteDialog(d)
            is Dialog.Pick -> {
                val title = when (d.kind) {
                    PickKind.COPY -> "Copy ${label(d.entries)} to"
                    PickKind.MOVE -> "Move ${label(d.entries)} to"
                    PickKind.EXTRACT -> "Extract ${d.entries[0].name} to"
                }
                FolderPickerDialog(
                    title = title,
                    confirmLabel = when (d.kind) { PickKind.COPY -> "Copy here"; PickKind.MOVE -> "Move here"; PickKind.EXTRACT -> "Extract here" },
                    blocked = d.entries.filter { it.isDirectory }.map { it.uri }.toSet(),
                    onPick = { trail ->
                        dismiss()
                        val dest = trail.last().uri
                        when (d.kind) {
                            PickKind.COPY -> container.ops.submit(OperationRequest.Copy(d.entries, dest))
                            PickKind.MOVE -> container.ops.submit(OperationRequest.Move(d.entries, dest))
                            PickKind.EXTRACT -> container.ops.submit(OperationRequest.Extract(d.entries[0], dest, d.selected, true))
                        }
                        d.onDone()
                    },
                    onDismiss = ::dismiss
                )
            }
            is Dialog.Properties -> PropertiesDialog(d.entry, onDismiss = ::dismiss)
        }
    }

    private fun label(entries: List<FileEntry>) = if (entries.size == 1) "\"${entries[0].name}\"" else "${entries.size} items"

    @Composable
    private fun DeleteDialog(d: Dialog.Delete) {
        var permanent by remember { mutableStateOf(false) }
        val n = d.entries.size
        val what = if (n == 1) "\"${d.entries[0].name}\"" else "$n items"
        androidx.compose.material3.AlertDialog(
            onDismissRequest = ::dismiss,
            title = { androidx.compose.material3.Text("Delete $what?") },
            text = {
                androidx.compose.material3.Text(
                    "Deleted items are moved to the app's trash, where you can restore them from the Undo button or Tools > Trash " +
                        "(kept for ${container.settings.value.trashRetentionDays} days). The trash is stored on this device inside the app, so large " +
                        "items need free space and the copy is slower than a normal delete.\n\n" +
                        "\"Delete permanently\" removes the data immediately and CANNOT be undone."
                )
            },
            confirmButton = {
                androidx.compose.material3.Button(onClick = {
                    dismiss()
                    container.ops.submit(OperationRequest.Delete(d.entries, permanent = false))
                    d.onDone()
                }) { androidx.compose.material3.Text("Move to trash") }
            },
            dismissButton = {
                androidx.compose.foundation.layout.Row {
                    androidx.compose.material3.TextButton(onClick = ::dismiss) { androidx.compose.material3.Text("Cancel") }
                    androidx.compose.material3.TextButton(onClick = { permanent = true }) {
                        androidx.compose.material3.Text("Delete permanently", color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                    }
                }
            }
        )
        if (permanent) {
            ConfirmDialog(
                title = "Delete permanently?",
                text = "$what will be erased immediately. This cannot be undone.",
                confirmLabel = "Delete permanently", destructive = true,
                onConfirm = {
                    permanent = false
                    dismiss()
                    container.ops.submit(OperationRequest.Delete(d.entries, permanent = true))
                    d.onDone()
                },
                onDismiss = { permanent = false }
            )
        }
    }
}
