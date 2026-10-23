package com.bardia.fileexplorer.ui.lists

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bardia.fileexplorer.app.LocalContainer
import com.bardia.fileexplorer.core.Crumb
import com.bardia.fileexplorer.core.FileCategory
import com.bardia.fileexplorer.core.FileEntry
import com.bardia.fileexplorer.core.FileSorter
import com.bardia.fileexplorer.design.AppTopBar
import com.bardia.fileexplorer.design.ConfirmDialog
import com.bardia.fileexplorer.design.EmptyState
import com.bardia.fileexplorer.design.FileCollection
import com.bardia.fileexplorer.design.FluentCard
import com.bardia.fileexplorer.design.InfoBanner
import com.bardia.fileexplorer.design.MenuAction
import com.bardia.fileexplorer.search.SearchCriteria
import com.bardia.fileexplorer.search.SearchResult
import com.bardia.fileexplorer.storage.RootStatus
import com.bardia.fileexplorer.storage.WalkStats
import com.bardia.fileexplorer.ui.browse.BrowseViewModel
import com.bardia.fileexplorer.ui.common.EntryActions
import com.bardia.fileexplorer.ui.common.LocalNotify
import com.bardia.fileexplorer.ui.common.rememberFolderGrantLauncher
import com.bardia.fileexplorer.ui.common.rememberQuickFolderOpener
import com.bardia.fileexplorer.ui.common.rememberRoots
import com.bardia.fileexplorer.ui.common.toEntry
import com.bardia.fileexplorer.ui.home.categoryIcon
import com.bardia.fileexplorer.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val noSelection = emptySet<String>()

// ------------------------------------------------------------------------------------------ Recent

@Composable
fun RecentScreen(vm: BrowseViewModel, actions: EntryActions, onOpenBrowse: () -> Unit) {
    val c = LocalContainer.current
    val settings by c.settings.collectAsStateWithLifecycle()
    val recents by c.metadata.recents.collectAsStateWithLifecycle(emptyList())
    val notify = LocalNotify.current
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }

    // Which items still exist; checked off the main thread so deleted/inaccessible files are shown as such.
    val alive by produceState<Map<String, Boolean>>(emptyMap(), recents) {
        value = withContext(Dispatchers.IO) { recents.associate { it.uri to (c.fs.stat(Uri.parse(it.uri)) != null) } }
    }
    val entries = remember(recents) { recents.map { it.toEntry() } }
    val byUri = remember(recents) { recents.associateBy { it.uri } }

    Scaffold(
        topBar = {
            AppTopBar("Recent files", actions = {
                IconButton(onClick = { confirmClear = true }, enabled = recents.isNotEmpty()) { Icon(Icons.Filled.Delete, "Clear history") }
            })
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) {
            if (entries.isEmpty()) {
                EmptyState(Icons.Filled.History, "No recent files", "Files you open with File Explorer show up here. History is kept on this device only.")
            } else {
                Column {
                    InfoBanner("History keeps the last ${settings.recentLimit} files. Change this in Settings.", Modifier.padding(12.dp))
                    FileCollection(
                        entries = entries, viewMode = settings.viewMode, iconSize = settings.iconSize,
                        selected = noSelection, selectionMode = false,
                        onClick = { e ->
                            if (alive[e.uri] == false) notify("\"${e.name}\" is no longer available. You can remove it from the item menu.")
                            else actions.open(e, byUri[e.uri]?.parentTrail ?: emptyList())
                        },
                        onLongClick = {},
                        subtitle = { e ->
                            val r = byUri[e.uri]
                            (if (alive[e.uri] == false) "No longer available  •  " else "") + (r?.let { Format.date(it.openedAt) } ?: "") +
                                (e.displayPath?.let { "  •  $it" } ?: "")
                        },
                        menuFor = { e ->
                            val r = byUri[e.uri]
                            val extra = ArrayList<MenuAction>()
                            if (r != null && r.parentTrail.isNotEmpty()) extra.add(MenuAction("Show in folder", Icons.Filled.FolderOpen) { vm.navigate(r.parentTrail); onOpenBrowse() })
                            extra.add(MenuAction("Remove from history", Icons.Filled.Delete) { scope.launch { c.metadata.removeRecent(e.uri) } })
                            if (alive[e.uri] == false) extra else listOf(
                                MenuAction("Share") { actions.share(listOf(e)) },
                                MenuAction("Properties") { actions.properties(e) }
                            ) + extra
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
    if (confirmClear) ConfirmDialog(
        "Clear recent files?", "This only clears the list in File Explorer. Your files are not touched.", "Clear",
        onConfirm = { confirmClear = false; scope.launch { c.metadata.clearRecents(); c.metadata.clearRecentFolders() } },
        onDismiss = { confirmClear = false }, destructive = true
    )
}

// ------------------------------------------------------------------------------------------ Favorites

@Composable
fun FavoritesScreen(vm: BrowseViewModel, actions: EntryActions, onOpenBrowse: () -> Unit) {
    val c = LocalContainer.current
    val settings by c.settings.collectAsStateWithLifecycle()
    val favorites by c.metadata.favorites.collectAsStateWithLifecycle(emptyList())
    val notify = LocalNotify.current
    val scope = rememberCoroutineScope()
    val grant = rememberFolderGrantLauncher()

    val alive by produceState<Map<String, Boolean>>(emptyMap(), favorites) {
        value = withContext(Dispatchers.IO) { favorites.associate { it.uri to (c.fs.stat(Uri.parse(it.uri)) != null) } }
    }
    val entries = remember(favorites) { favorites.map { it.toEntry() } }
    val byUri = remember(favorites) { favorites.associateBy { it.uri } }

    Scaffold(topBar = { AppTopBar("Favorites") }, containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) {
            if (entries.isEmpty()) {
                EmptyState(Icons.Filled.Star, "No favorites yet", "Open the menu on any file or folder and choose \"Add to favorites\" to pin it here.")
            } else FileCollection(
                entries = entries, viewMode = settings.viewMode, iconSize = settings.iconSize,
                selected = noSelection, selectionMode = false,
                onClick = { e ->
                    val f = byUri[e.uri] ?: return@FileCollection
                    if (alive[e.uri] == false) {
                        notify("\"${e.name}\" is unavailable. Access may have been revoked or the item was removed.")
                    } else if (f.isDirectory) { vm.navigate(f.trail); onOpenBrowse() } else actions.open(e, f.trail)
                },
                onLongClick = {},
                subtitle = { e -> (if (alive[e.uri] == false) "Unavailable  •  " else "") + (e.displayPath ?: "") },
                menuFor = { e ->
                    val f = byUri[e.uri]
                    val list = ArrayList<MenuAction>()
                    if (alive[e.uri] == false) {
                        list.add(MenuAction("Grant access again", Icons.Filled.Lock) { grant.pick(f?.trail?.firstOrNull()?.uri?.let { Uri.parse(it) }) })
                    } else {
                        if (f != null && !f.isDirectory && f.trail.isNotEmpty()) list.add(MenuAction("Show in folder", Icons.Filled.FolderOpen) { vm.navigate(f.trail); onOpenBrowse() })
                        if (!e.isDirectory) list.add(MenuAction("Share") { actions.share(listOf(e)) })
                        list.add(MenuAction("Properties") { actions.properties(e) })
                    }
                    list.add(MenuAction("Remove from favorites", Icons.Filled.Delete) { scope.launch { c.metadata.removeFavorite(e.uri) } })
                    list
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

// ------------------------------------------------------------------------------------------ Categories

@Composable
fun CategoriesScreen(vm: BrowseViewModel, onCategory: (FileCategory) -> Unit, onOpenBrowse: () -> Unit) {
    val c = LocalContainer.current
    val roots by rememberRoots()
    val openQuick = rememberQuickFolderOpener { trail -> vm.navigate(trail); onOpenBrowse() }
    Scaffold(topBar = { AppTopBar("Categories") }, containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, padding.calculateBottomPadding() + 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(FileCategory.values().filter { it != FileCategory.OTHER }) { cat ->
                val count by produceState<Int?>(null, cat, roots.size) { value = if (c.media.supports(cat)) c.media.count(cat) else null }
                FluentCard(Modifier.fillMaxWidth(), onClick = { onCategory(cat) }) {
                    Column(Modifier.padding(16.dp)) {
                        Icon(categoryIcon(cat), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(cat.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                c.media.supports(cat) -> count?.let { "$it items" } ?: "Needs media access"
                                else -> "Scans your folders"
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item {
                FluentCard(Modifier.fillMaxWidth(), onClick = { openQuick("Download") }) {
                    Column(Modifier.padding(16.dp)) {
                        Icon(Icons.Filled.Download, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Downloads", style = MaterialTheme.typography.titleMedium)
                        Text("Opens your Downloads folders", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ Category

private const val PAGE = 400

@Composable
fun CategoryScreen(category: FileCategory, vm: BrowseViewModel, actions: EntryActions, onBack: () -> Unit, onOpenBrowse: () -> Unit) {
    val c = LocalContainer.current
    val settings by c.settings.collectAsStateWithLifecycle()
    val isMedia = c.media.supports(category)
    Scaffold(topBar = { AppTopBar(category.label, onBack) }, containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) {
            if (isMedia) MediaCategory(category, actions, settings) else ScannedCategory(category, vm, actions, settings, onOpenBrowse)
        }
    }
}

@Composable
private fun MediaCategory(category: FileCategory, actions: EntryActions, settings: com.bardia.fileexplorer.core.Settings) {
    val c = LocalContainer.current
    var granted by remember { mutableStateOf(c.media.hasPermission(category)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = c.media.hasPermission(category) }
    if (!granted) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Filled.Lock, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(12.dp))
            Text("Allow access to ${category.label.lowercase()}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(6.dp))
            Text(
                "To list your ${category.label.lowercase()} from Android's media library, File Explorer needs the media permission. " +
                    "It is only used to read this list; nothing leaves your device.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(16.dp))
            Button(onClick = { launcher.launch(c.media.requiredPermissions(category)) }) { Text("Allow access") }
            Text("You can also grant folders instead from Browse.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    var items by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var endReached by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun loadMore(reset: Boolean) {
        if (loading || (endReached && !reset)) return
        loading = true
        scope.launch {
            try {
                val offset = if (reset) 0 else items.size
                val page = c.media.page(category, settings.sortField, settings.sortAscending, PAGE, offset)
                items = if (reset) page else items + page
                endReached = page.size < PAGE
                error = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: SecurityException) {
                granted = false
            } catch (e: Exception) {
                error = e.message ?: "Could not read the media library"
            }
            loading = false
        }
    }
    LaunchedEffect(category, settings.sortField, settings.sortAscending) { endReached = false; loading = false; loadMore(true) }

    Column(Modifier.fillMaxSize()) {
        InfoBanner("Items come from Android's media library and are read-only here. Use Browse to manage files in your folders.", Modifier.padding(12.dp))
        when {
            error != null -> EmptyState(Icons.Filled.FolderOpen, "Could not load", error ?: "", actionLabel = "Retry", onAction = { loadMore(true) })
            items.isEmpty() && loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            items.isEmpty() -> EmptyState(categoryIcon(category), "Nothing here", "No ${category.label.lowercase()} were found in the media library.")
            else -> FileCollection(
                entries = items, viewMode = settings.viewMode, iconSize = settings.iconSize,
                selected = noSelection, selectionMode = false,
                onClick = { actions.open(it) }, onLongClick = {},
                subtitle = { e -> listOf(Format.size(e.size), Format.shortDate(e.modified), e.displayPath ?: "").filter { it.isNotEmpty() && it != "—" }.joinToString("  •  ") },
                menuFor = { e -> listOf(MenuAction("Open with...") { actions.openExternally(e) }, MenuAction("Share") { actions.share(listOf(e)) }, MenuAction("Properties") { actions.properties(e) }) },
                sortField = settings.sortField, ascending = settings.sortAscending,
                onEndReached = { loadMore(false) }, modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ScannedCategory(
    category: FileCategory, vm: BrowseViewModel, actions: EntryActions,
    settings: com.bardia.fileexplorer.core.Settings, onOpenBrowse: () -> Unit
) {
    val c = LocalContainer.current
    val roots by rememberRoots()
    val grant = rememberFolderGrantLauncher()
    val okRoots = roots.filter { it.status == RootStatus.OK }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    val stats = remember { WalkStats() }

    LaunchedEffect(category, okRoots.map { it.root.treeUri }) {
        results = emptyList()
        if (okRoots.isEmpty()) return@LaunchedEffect
        running = true
        val acc = ArrayList<SearchResult>()
        try {
            val criteria = SearchCriteria(category = category, includeHidden = settings.showHidden)
            c.search.search(criteria, okRoots.map { Crumb(it.root.rootUri, it.root.name) }, stats).collect { r ->
                acc.add(r)
                if (acc.size % 25 == 0) results = acc.toList()
            }
        } finally {
            results = acc.toList()
            running = false
        }
    }

    if (okRoots.isEmpty()) {
        EmptyState(
            categoryIcon(category), "No folders to scan",
            "${category.label} are found by scanning folders you have granted access to. Android does not allow scanning your whole device.",
            actionLabel = "Choose a folder", onAction = { grant.pick() }
        )
        return
    }
    val sorted = remember(results, settings.sortField, settings.sortAscending) {
        FileSorter.sort(results.map { it.entry }, settings.sortField, settings.sortAscending, foldersFirst = false)
    }
    val byUri = remember(results) { results.associateBy { it.entry.uri } }
    Column(Modifier.fillMaxSize()) {
        InfoBanner(
            "Scanned ${okRoots.size} granted folder(s)" + (if (stats.skippedFolders.get() > 0) "; ${stats.skippedFolders.get()} sub-folder(s) could not be read" else "") +
                (if (running) " - scanning..." else ". Files outside these folders are not included."),
            Modifier.padding(12.dp)
        )
        if (sorted.isEmpty() && !running) EmptyState(categoryIcon(category), "Nothing found", "No ${category.label.lowercase()} in the granted folders.")
        else FileCollection(
            entries = sorted, viewMode = settings.viewMode, iconSize = settings.iconSize,
            selected = noSelection, selectionMode = false,
            onClick = { e -> actions.open(e, byUri[e.uri]?.trail ?: emptyList()) }, onLongClick = {},
            subtitle = { e -> listOf(Format.size(e.size), Format.shortDate(e.modified), e.displayPath ?: "").filter { it.isNotEmpty() && it != "—" }.joinToString("  •  ") },
            menuFor = { e ->
                val trail = byUri[e.uri]?.trail ?: emptyList()
                actions.menu(e, trail, false, listOf(MenuAction("Show in folder", Icons.Filled.FolderOpen) { vm.navigate(trail); onOpenBrowse() }))
            },
            sortField = settings.sortField, ascending = settings.sortAscending, modifier = Modifier.weight(1f)
        )
    }
}
