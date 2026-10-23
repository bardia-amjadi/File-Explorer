package com.bardia.fileexplorer.ui.common

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bardia.fileexplorer.app.LocalContainer
import com.bardia.fileexplorer.core.Crumb
import com.bardia.fileexplorer.core.FileEntry
import com.bardia.fileexplorer.core.FileSorter
import com.bardia.fileexplorer.core.SortField
import com.bardia.fileexplorer.design.Breadcrumbs
import com.bardia.fileexplorer.design.EmptyState
import com.bardia.fileexplorer.design.TextInputDialog
import com.bardia.fileexplorer.storage.FileSystem
import com.bardia.fileexplorer.storage.RootStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.bardia.fileexplorer.core.NameUtils

/**
 * In-app location picker for copy/move/extract destinations. It browses only granted folders (SAF), lists
 * folders only, and can create a new folder. Returns the trail of the chosen folder.
 *
 * [blocked] holds folder URIs that must not be chosen (e.g. the folder being moved).
 */
@Composable
fun FolderPickerDialog(
    title: String,
    confirmLabel: String,
    initialTrail: List<Crumb> = emptyList(),
    blocked: Set<String> = emptySet(),
    onPick: (List<Crumb>) -> Unit,
    onDismiss: () -> Unit
) {
    val c = LocalContainer.current
    val roots by rememberRoots()
    var trail by remember { mutableStateOf(initialTrail) }
    var folders by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var canCreate by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var showNew by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val notify = LocalNotify.current
    val grant = rememberFolderGrantLauncher()

    LaunchedEffect(trail, reload) {
        error = null
        if (trail.isEmpty()) {
            folders = emptyList()
            canCreate = false
            return@LaunchedEffect
        }
        loading = true
        try {
            val dir = Uri.parse(trail.last().uri)
            val result = withContext(Dispatchers.IO) {
                val list = c.fs.list(dir).filter { it.isDirectory && !it.isHidden }
                val self = c.fs.stat(dir)
                list to (self?.canWriteInto == true)
            }
            folders = FileSorter.sort(result.first, SortField.NAME, true)
            canCreate = result.second
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = FileSystem.classify(e).message
            folders = emptyList()
        }
        loading = false
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).widthIn(max = 640.dp).fillMaxHeight(0.9f).heightIn(max = 680.dp),
            shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { showNew = true }, enabled = trail.isNotEmpty() && canCreate) {
                        Icon(Icons.Filled.CreateNewFolder, contentDescription = "New folder")
                    }
                }
                Breadcrumbs(
                    rootLabel = "Locations", trail = trail,
                    onRoot = { trail = emptyList() }, onCrumb = { i -> trail = trail.take(i + 1) },
                    modifier = Modifier.fillMaxWidth()
                )
                HorizontalDivider()
                Column(Modifier.weight(1f)) {
                    when {
                        trail.isEmpty() -> {
                            if (roots.isEmpty()) {
                                EmptyState(
                                    Icons.Filled.FolderOpen, "No folders available",
                                    "Grant access to a folder first. Android only lets apps use folders you pick.",
                                    actionLabel = "Choose a folder", onAction = { grant.pick() }
                                )
                            } else {
                                LazyColumn(Modifier.weight(1f)) {
                                    items(roots, key = { it.root.treeUri }) { r ->
                                        val ok = r.status == RootStatus.OK
                                        Row(
                                            Modifier.fillMaxWidth().clickable(enabled = ok) { trail = listOf(Crumb(r.root.rootUri, r.root.name)) }
                                                .padding(horizontal = 20.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary)
                                            Spacer(Modifier.width(14.dp))
                                            Column {
                                                Text(r.root.name, style = MaterialTheme.typography.bodyLarge)
                                                if (!ok) Text("Access lost - re-grant it in Storage", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    }
                                }
                                TextButton(onClick = { grant.pick() }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Add another folder...") }
                            }
                        }
                        loading -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator() }
                        error != null -> EmptyState(Icons.Filled.FolderOpen, "Cannot open this folder", error ?: "", actionLabel = "Retry", onAction = { reload++ })
                        folders.isEmpty() -> EmptyState(Icons.Filled.FolderOpen, "No sub-folders", "You can choose this folder or create a new one.")
                        else -> LazyColumn(Modifier.weight(1f)) {
                            items(folders, key = { it.uri }) { f ->
                                val isBlocked = f.uri in blocked
                                Row(
                                    Modifier.fillMaxWidth().clickable(enabled = !isBlocked) { trail = trail + Crumb(f.uri, f.name) }
                                        .padding(horizontal = 20.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Filled.Folder, null, tint = if (isBlocked) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(14.dp))
                                    Text(f.name, style = MaterialTheme.typography.bodyLarge, color = if (isBlocked) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
                val currentBlocked = trail.isNotEmpty() && trail.last().uri in blocked
                Column(Modifier.padding(12.dp)) {
                    if (trail.isNotEmpty() && !loading && error == null && !canCreate) {
                        Text("This location is read-only; items cannot be placed here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.size(6.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                        Button(onClick = { onPick(trail) }, enabled = trail.isNotEmpty() && canCreate && !currentBlocked && !loading) { Text(confirmLabel) }
                    }
                }
            }
        }
    }

    if (showNew && trail.isNotEmpty()) {
        TextInputDialog(
            title = "New folder", label = "Folder name", initial = "", confirmLabel = "Create",
            validate = { NameUtils.validate(it) },
            onConfirm = { name ->
                showNew = false
                scope.launch {
                    c.ops.createFolder(trail.last().uri, name)
                        .onSuccess { reload++ }
                        .onFailure { notify(it.message ?: "Could not create folder") }
                }
            },
            onDismiss = { showNew = false }
        )
    }
}
