package com.bardia.fileexplorer.ui.settings

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bardia.fileexplorer.app.LocalContainer
import com.bardia.fileexplorer.core.Crumb
import com.bardia.fileexplorer.design.AppTopBar
import com.bardia.fileexplorer.design.ConfirmDialog
import com.bardia.fileexplorer.design.EmptyState
import com.bardia.fileexplorer.design.KeyValueRow
import com.bardia.fileexplorer.design.FluentCard
import com.bardia.fileexplorer.design.SectionHeader
import com.bardia.fileexplorer.storage.RootState
import com.bardia.fileexplorer.ui.common.RootRow
import com.bardia.fileexplorer.ui.common.ScopedStorageNote
import com.bardia.fileexplorer.ui.common.VolumeCard
import com.bardia.fileexplorer.ui.common.rememberFolderGrantLauncher
import com.bardia.fileexplorer.ui.common.rememberRoots
import com.bardia.fileexplorer.ui.common.rememberVolumes
import com.bardia.fileexplorer.util.Format
import kotlinx.coroutines.launch

/** Volumes, the folders the app may access, and trash usage. Also reachable from Settings. */
@Composable
fun StorageScreen(onOpenTrail: (List<Crumb>) -> Unit, onTrash: () -> Unit, onBack: (() -> Unit)? = null) {
    val c = LocalContainer.current
    val volumes by rememberVolumes()
    val roots by rememberRoots()
    val trash by c.trash.items.collectAsStateWithLifecycle()
    val grant = rememberFolderGrantLauncher()
    val scope = rememberCoroutineScope()
    var removing by remember { mutableStateOf<RootState?>(null) }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { AppTopBar("Storage", onBack) }
        item { SectionHeader("Volumes", Modifier.padding(horizontal = 16.dp)) }
        if (volumes.isEmpty()) item { Text("No storage information available.", Modifier.padding(16.dp)) }
        items(volumes) { v -> VolumeCard(v, grant, Modifier.padding(horizontal = 16.dp)) }
        item { SectionHeader("Folders this app can access", Modifier.padding(horizontal = 16.dp)) }
        if (roots.isEmpty()) item { Text("You have not granted any folder yet.", Modifier.padding(horizontal = 16.dp)) }
        items(roots, key = { it.root.treeUri }) { r ->
            RootRow(r, onOpen = { onOpenTrail(listOf(Crumb(r.root.rootUri, r.root.name))) },
                onRegrant = { grant.pick(Uri.parse(r.root.treeUri)) }, onRemove = { removing = r })
        }
        item {
            OutlinedButton(onClick = { grant.pick() }, Modifier.padding(horizontal = 16.dp)) {
                Icon(Icons.Filled.Add, null); Text("  Grant a folder")
            }
        }
        item { SectionHeader("Trash", Modifier.padding(horizontal = 16.dp)) }
        item {
            FluentCard(Modifier.padding(horizontal = 16.dp), onClick = onTrash) {
                Column(Modifier.padding(12.dp)) {
                    KeyValueRow("Items", trash.size.toString())
                    KeyValueRow("Space used", Format.size(c.trash.totalSize()))
                }
            }
        }
        item { ScopedStorageNote(Modifier.padding(16.dp)) }
    }
    removing?.let { r ->
        ConfirmDialog(
            "Remove access?", "File Explorer will forget \"${r.root.name}\". Your files are not touched; you can grant the folder again later.",
            "Remove", { removing = null; scope.launch { c.storage.removeRoot(r.root) } }, { removing = null }
        )
    }
}
