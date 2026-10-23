package com.bardia.fileexplorer.ui.preview

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bardia.fileexplorer.app.LocalContainer
import com.bardia.fileexplorer.archive.ZipListing
import com.bardia.fileexplorer.archive.ZipTree
import com.bardia.fileexplorer.core.FileEntry
import com.bardia.fileexplorer.design.AppTopBar
import com.bardia.fileexplorer.design.EmptyState
import com.bardia.fileexplorer.design.InfoBanner
import com.bardia.fileexplorer.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Browse the inside of a ZIP without extracting it, then extract everything or just a selection. */
@Composable
fun ArchiveScreen(uri: String, actions: com.bardia.fileexplorer.ui.common.EntryActions, onBack: () -> Unit) {
    val c = LocalContainer.current
    val entry by produceState<FileEntry?>(null, uri) {
        value = withContext(Dispatchers.IO) { runCatching { c.fs.statAny(Uri.parse(uri)) }.getOrNull() }
    }
    val listing by produceState<Result<ZipListing>?>(null, uri) {
        value = runCatching { c.zip.list(Uri.parse(uri)) }
    }
    var prefix by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }
    BackHandler(enabled = prefix.isNotEmpty()) { prefix = prefix.substringBeforeLast('/', "") }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(entry?.name ?: "Archive", onBack)
        val l = listing
        when {
            l == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            l.isFailure -> EmptyState(Icons.Filled.Warning, "Cannot read this archive", l.exceptionOrNull()?.message ?: "The file may be corrupted.")
            else -> {
                val zl = l.getOrThrow()
                val nodes = remember(zl, prefix) { ZipTree.children(zl.entries, prefix) }
                val unsafe = zl.entries.count { it.unsafe }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("/" + prefix, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { entry?.let { actions.extract(it) } }, enabled = entry != null) { Text("Extract all") }
                    TextButton(
                        onClick = { entry?.let { actions.extractSelected(it, selected.toSet()) } },
                        enabled = entry != null && selected.isNotEmpty()
                    ) { Text("Extract selected (${selected.size})") }
                }
                if (zl.truncated) InfoBanner("This archive has a very large number of entries; only the first part is listed.", Modifier.padding(12.dp, 0.dp))
                if (unsafe > 0) InfoBanner("$unsafe entries have unsafe paths (such as \"..\") and will be skipped on extraction.", Modifier.padding(12.dp, 4.dp))
                if (nodes.isEmpty()) EmptyState(Icons.Filled.FolderZip, "Empty", "Nothing here.")
                else LazyColumn(Modifier.fillMaxSize()) {
                    if (prefix.isNotEmpty()) item {
                        Row(
                            Modifier.fillMaxWidth().clickable { prefix = prefix.substringBeforeLast('/', "") }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) { Text("..", style = MaterialTheme.typography.bodyLarge) }
                    }
                    items(nodes, key = { it.path }) { n ->
                        val path = n.path
                        Row(
                            Modifier.fillMaxWidth().clickable { if (n.isDirectory) prefix = n.path }.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Checkbox(checked = path in selected, onCheckedChange = { if (it) selected.add(path) else selected.remove(path) })
                            Icon(if (n.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text(n.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                Text(
                                    if (n.isDirectory) "${n.itemCount} items" else Format.size(n.size),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
