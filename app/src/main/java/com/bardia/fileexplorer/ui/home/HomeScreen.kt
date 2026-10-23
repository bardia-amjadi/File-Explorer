package com.bardia.fileexplorer.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bardia.fileexplorer.app.LocalContainer
import com.bardia.fileexplorer.core.Crumb
import com.bardia.fileexplorer.core.FileCategory
import com.bardia.fileexplorer.core.FileEntry
import com.bardia.fileexplorer.design.AppTopBar
import com.bardia.fileexplorer.design.FileGlyph
import com.bardia.fileexplorer.design.FluentCard
import com.bardia.fileexplorer.design.SectionHeader
import com.bardia.fileexplorer.core.IconSize
import com.bardia.fileexplorer.ui.browse.BrowseViewModel
import com.bardia.fileexplorer.ui.common.EntryActions
import com.bardia.fileexplorer.ui.common.LocalNotify
import com.bardia.fileexplorer.ui.common.ScopedStorageNote
import com.bardia.fileexplorer.ui.common.VolumeCard
import com.bardia.fileexplorer.ui.common.QuickFolderNames
import com.bardia.fileexplorer.ui.common.rememberFolderGrantLauncher
import com.bardia.fileexplorer.ui.common.rememberQuickFolderOpener
import com.bardia.fileexplorer.ui.common.rememberRoots
import com.bardia.fileexplorer.ui.common.rememberVolumes
import com.bardia.fileexplorer.ui.common.toEntry
import com.bardia.fileexplorer.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun categoryIcon(c: FileCategory): ImageVector = when (c) {
    FileCategory.IMAGES -> Icons.Filled.Image
    FileCategory.VIDEOS -> Icons.Filled.Videocam
    FileCategory.AUDIO -> Icons.Filled.Audiotrack
    FileCategory.DOCUMENTS -> Icons.Filled.Description
    FileCategory.ARCHIVES -> Icons.Filled.FolderZip
    FileCategory.APKS -> Icons.Filled.Android
    FileCategory.OTHER -> Icons.Filled.Folder
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    vm: BrowseViewModel, actions: EntryActions,
    onOpenBrowse: () -> Unit, onSearch: () -> Unit, onCategory: (FileCategory) -> Unit, onRecent: () -> Unit, onFavorites: () -> Unit
) {
    val c = LocalContainer.current
    val notify = LocalNotify.current
    val scope = rememberCoroutineScope()
    val volumes by rememberVolumes()
    val roots by rememberRoots()
    val favorites by c.metadata.favorites.collectAsStateWithLifecycle(emptyList())
    val recents by c.metadata.recents.collectAsStateWithLifecycle(emptyList())
    val recentFolders by c.metadata.recentFolders.collectAsStateWithLifecycle(emptyList())
    val grant = rememberFolderGrantLauncher { root -> vm.navigate(listOf(Crumb(root.rootUri, root.name))); onOpenBrowse() }
    val openQuick = rememberQuickFolderOpener { trail -> vm.navigate(trail); onOpenBrowse() }

    suspend fun exists(uri: String) = withContext(Dispatchers.IO) { c.fs.stat(android.net.Uri.parse(uri)) != null }

    Scaffold(
        topBar = { AppTopBar("File Explorer", actions = { IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, "Search") } }) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Search entry
            item {
                FluentCard(Modifier.fillMaxWidth(), onClick = onSearch) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Text("Search files in your folders", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (roots.isEmpty()) {
                item {
                    FluentCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Get started", style = MaterialTheme.typography.titleMedium)
                            Text("Choose the folders you want to manage. Android keeps the rest of your storage private.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            androidx.compose.material3.Button(onClick = { grant.pick() }) { Text("Choose a folder") }
                        }
                    }
                }
            }

            item { SectionHeader("Storage") }
            items(volumes, key = { "v:" + it.description + it.isPrimary }) { v -> VolumeCard(v, grant) }

            item { SectionHeader("Quick access") }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    QuickFolderNames.forEach { name ->
                        AssistChip(
                            onClick = { openQuick(name) },
                            label = { Text(if (name == "Download") "Downloads" else name) },
                            leadingIcon = { Icon(if (name == "Download") Icons.Filled.Download else Icons.Filled.Folder, null, modifier = Modifier.size(18.dp)) }
                        )
                    }
                }
            }

            item { SectionHeader("Categories") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(FileCategory.values().filter { it != FileCategory.OTHER }, key = { it.name }) { cat ->
                        val count by produceState<Int?>(null, cat, roots.size) { value = if (c.media.supports(cat)) c.media.count(cat) else null }
                        FluentCard(Modifier.width(112.dp), onClick = { onCategory(cat) }) {
                            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(categoryIcon(cat), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                                Spacer(Modifier.size(6.dp))
                                Text(cat.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                                Text(count?.let { "$it items" } ?: " ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            if (favorites.isNotEmpty()) {
                item { SectionHeader("Favorites") { TextButton(onClick = onFavorites) { Text("See all") } } }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(favorites.take(12), key = { it.uri }) { f ->
                            FluentCard(Modifier.width(150.dp), onClick = {
                                scope.launch {
                                    if (!exists(f.uri)) notify("\"${f.name}\" is no longer available. Remove it in Favorites.")
                                    else if (f.isDirectory) { vm.navigate(f.trail); onOpenBrowse() } else actions.open(f.toEntry(), f.trail)
                                }
                            }) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    FileGlyph(f.toEntry(), 28.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }

            if (recents.isNotEmpty()) {
                item { SectionHeader("Recent files") { TextButton(onClick = onRecent) { Text("See all") } } }
                items(recents.take(6), key = { "r:" + it.uri }) { r ->
                    val e = r.toEntry()
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            scope.launch { if (exists(r.uri)) actions.open(e, r.parentTrail) else notify("\"${r.name}\" is no longer available.") }
                        }.padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FileGlyph(e, 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            Text(Format.date(r.openedAt) + (e.displayPath?.let { "  •  $it" } ?: ""), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            if (recentFolders.isNotEmpty()) {
                item { SectionHeader("Recent folders") }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        recentFolders.take(8).forEach { trail ->
                            AssistChip(
                                onClick = { scope.launch { if (exists(trail.last().uri)) { vm.navigate(trail); onOpenBrowse() } else notify("That folder is no longer available.") } },
                                label = { Text(trail.last().name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp)) },
                                leadingIcon = { Icon(Icons.Filled.Folder, null, modifier = Modifier.size(18.dp)) }
                            )
                        }
                    }
                }
            }
            item { ScopedStorageNote(Modifier.padding(top = 8.dp)) }
        }
    }
}
