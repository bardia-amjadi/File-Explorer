package com.bardia.fileexplorer.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bardia.fileexplorer.app.LocalContainer
import com.bardia.fileexplorer.core.Crumb
import com.bardia.fileexplorer.core.FileEntry
import com.bardia.fileexplorer.core.FileTypes
import com.bardia.fileexplorer.core.IconSize
import com.bardia.fileexplorer.core.SortField
import com.bardia.fileexplorer.core.ViewMode
import com.bardia.fileexplorer.design.Breadcrumbs
import com.bardia.fileexplorer.design.ConfirmDialog
import com.bardia.fileexplorer.design.EmptyState
import com.bardia.fileexplorer.design.ErrorPanel
import com.bardia.fileexplorer.design.FileCollection
import com.bardia.fileexplorer.design.MenuAction
import com.bardia.fileexplorer.design.SectionHeader
import com.bardia.fileexplorer.operations.OperationRequest
import com.bardia.fileexplorer.core.BrowseErrorKind
import com.bardia.fileexplorer.storage.RootState
import com.bardia.fileexplorer.storage.RootStatus
import com.bardia.fileexplorer.storage.StorageRepository
import com.bardia.fileexplorer.ui.common.EntryActions
import com.bardia.fileexplorer.ui.common.LocalNotify
import com.bardia.fileexplorer.ui.common.RootRow
import com.bardia.fileexplorer.ui.common.ScopedStorageNote
import com.bardia.fileexplorer.ui.common.VolumeCard
import com.bardia.fileexplorer.ui.common.rememberFolderGrantLauncher
import com.bardia.fileexplorer.ui.common.rememberRoots
import com.bardia.fileexplorer.ui.common.rememberVolumes
import kotlinx.coroutines.launch

@Composable
fun BrowseScreen(vm: BrowseViewModel, actions: EntryActions, onSearch: () -> Unit, onCompare: () -> Unit) {
    val c = LocalContainer.current
    val tabs by vm.tabs.collectAsStateWithLifecycle()
    val activeId by vm.activeTabId.collectAsStateWithLifecycle()
    val settings by c.settings.collectAsStateWithLifecycle()
    val favorites by c.metadata.favorites.collectAsStateWithLifecycle(emptyList())
    val favUris = remember(favorites) { favorites.map { it.uri }.toSet() }
    val tab = tabs.firstOrNull { it.id == activeId } ?: tabs.first()
    val pane = tab.active
    val grant = rememberFolderGrantLauncher { root ->
        vm.navigate(listOf(Crumb(root.rootUri, root.name)))
    }

    // System back: clear selection, then go up one folder, then leave the screen.
    BackHandler(enabled = pane.selection.isNotEmpty() || pane.trail.isNotEmpty()) {
        if (pane.selection.isNotEmpty()) vm.clearSelection() else vm.goUp()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        val showBoth = tab.dual && wide
        Column(Modifier.fillMaxSize()) {
            TabStrip(tabs, activeId, onSwitch = vm::switchTab, onClose = vm::closeTab, onNew = { vm.newTab() })
            if (pane.selection.isNotEmpty()) {
                SelectionBar(vm, tab, actions, favUris, onCompare)
            } else {
                BrowseToolbar(vm, tab, onSearch, actions)
            }
            if (tab.dual && !wide) {
                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Left" to 0, "Right" to 1).forEach { (label, i) ->
                        FilterChip(
                            selected = tab.activePane == i, onClick = { vm.setActivePane(i) },
                            label = { Text("$label: ${tab.pane(i).trail.lastOrNull()?.name ?: "This device"}", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp)) }
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (showBoth) {
                Row(Modifier.fillMaxSize()) {
                    PaneView(vm, tab, 0, true, settings, favUris, actions, grant, Modifier.weight(1f))
                    androidx.compose.material3.VerticalDivider()
                    PaneView(vm, tab, 1, true, settings, favUris, actions, grant, Modifier.weight(1f))
                }
            } else {
                PaneView(vm, tab, tab.activePane, false, settings, favUris, actions, grant, Modifier.fillMaxSize())
            }
        }
    }
}

// ----------------------------------------------------------------------------------------- tabs

@Composable
private fun TabStrip(tabs: List<TabState>, activeId: Long, onSwitch: (Long) -> Unit, onClose: (Long) -> Unit, onNew: () -> Unit) {
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        items(tabs, key = { it.id }) { t ->
            val active = t.id == activeId
            Surface(
                onClick = { onSwitch(t.id) }, shape = MaterialTheme.shapes.medium,
                color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                border = if (active) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
            ) {
                Row(Modifier.padding(start = 10.dp, end = if (tabs.size > 1) 2.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Folder, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(6.dp))
                    Text(t.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp).padding(vertical = 8.dp))
                    if (tabs.size > 1) {
                        IconButton(onClick = { onClose(t.id) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Close tab ${t.title}", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
        item {
            IconButton(onClick = onNew) { Icon(Icons.Filled.Add, contentDescription = "New tab") }
        }
    }
}

// ----------------------------------------------------------------------------------------- toolbar

@Composable
private fun MenuCheckItem(text: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { if (checked) Icon(Icons.Filled.Check, null) else Spacer(Modifier.size(24.dp)) },
        onClick = onClick
    )
}

@Composable
private fun BrowseToolbar(vm: BrowseViewModel, tab: TabState, onSearch: () -> Unit, actions: EntryActions) {
    val c = LocalContainer.current
    val settings by c.settings.collectAsStateWithLifecycle()
    val pane = tab.active
    var viewMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    val notify = LocalNotify.current

    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.goBack() }, enabled = pane.canGoBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        IconButton(onClick = { vm.goForward() }, enabled = pane.canGoForward) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward") }
        IconButton(onClick = { vm.goUp() }, enabled = pane.trail.isNotEmpty()) { Icon(Icons.Filled.ArrowUpward, "Up to parent folder") }
        IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, "Refresh") }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, "Search") }
        Box {
            IconButton(onClick = { viewMenu = true }) { Icon(Icons.AutoMirrored.Filled.ViewList, "View options") }
            DropdownMenu(viewMenu, { viewMenu = false }) {
                ViewMode.values().forEach { m ->
                    MenuCheckItem("${m.label} view", settings.viewMode == m) { c.updateSettings { it.copy(viewMode = m) }; viewMenu = false }
                }
                HorizontalDivider()
                IconSize.values().forEach { s ->
                    MenuCheckItem("${s.label} icons", settings.iconSize == s) { c.updateSettings { it.copy(iconSize = s) }; viewMenu = false }
                }
                HorizontalDivider()
                MenuCheckItem("Group by type", settings.groupByType) { c.updateSettings { it.copy(groupByType = !it.groupByType) }; viewMenu = false }
            }
        }
        Box {
            IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort options") }
            DropdownMenu(sortMenu, { sortMenu = false }) {
                SortField.values().forEach { f ->
                    MenuCheckItem(f.label, settings.sortField == f) { c.updateSettings { it.copy(sortField = f) }; sortMenu = false }
                }
                HorizontalDivider()
                MenuCheckItem("Ascending", settings.sortAscending) { c.updateSettings { it.copy(sortAscending = true) }; sortMenu = false }
                MenuCheckItem("Descending", !settings.sortAscending) { c.updateSettings { it.copy(sortAscending = false) }; sortMenu = false }
                HorizontalDivider()
                MenuCheckItem("Folders first", settings.foldersFirst) { c.updateSettings { it.copy(foldersFirst = !it.foldersFirst) }; sortMenu = false }
            }
        }
        Box {
            IconButton(onClick = { moreMenu = true }) { Icon(Icons.Filled.MoreVert, "More options") }
            DropdownMenu(moreMenu, { moreMenu = false }) {
                DropdownMenuItem(
                    text = { Text("New folder") }, leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) },
                    enabled = pane.trail.isNotEmpty() && pane.canCreate,
                    onClick = { moreMenu = false; actions.newFolder(pane.trail.last().uri) { vm.refresh() } }
                )
                DropdownMenuItem(
                    text = { Text(if (settings.showHidden) "Hide hidden files" else "Show hidden files") },
                    leadingIcon = { Icon(if (settings.showHidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null) },
                    onClick = { moreMenu = false; c.updateSettings { it.copy(showHidden = !it.showHidden) } }
                )
                DropdownMenuItem(
                    text = { Text(if (tab.dual) "Close dual-pane" else "Dual-pane mode") }, leadingIcon = { Icon(Icons.Filled.VerticalSplit, null) },
                    onClick = { moreMenu = false; vm.setDual(!tab.dual) }
                )
                DropdownMenuItem(
                    text = { Text("Select all") }, leadingIcon = { Icon(Icons.Filled.SelectAll, null) },
                    enabled = pane.visible.isNotEmpty(), onClick = { moreMenu = false; vm.selectAll() }
                )
                DropdownMenuItem(
                    text = { Text("Open in new tab") }, leadingIcon = { Icon(Icons.Filled.Add, null) },
                    onClick = { moreMenu = false; vm.newTab(pane.trail) }
                )
                val dir = pane.dir
                if (dir != null && pane.trail.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Folder properties") }, leadingIcon = { Icon(Icons.Filled.Info, null) },
                        onClick = { moreMenu = false; actions.properties(dir.copy(parentUri = null, displayPath = pane.trail.joinToString("/") { it.name })) }
                    )
                    DropdownMenuItem(
                        text = { Text("Add folder to favorites") }, leadingIcon = { Icon(Icons.Filled.StarBorder, null) },
                        onClick = { moreMenu = false; actions.toggleFavorite(dir, pane.trail, false) }
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------------------- selection

@Composable
private fun SelectionBar(vm: BrowseViewModel, tab: TabState, actions: EntryActions, favUris: Set<String>, onCompare: () -> Unit) {
    val c = LocalContainer.current
    val idx = tab.activePane
    val pane = tab.active
    val selected = remember(pane.visible, pane.selection) { pane.visible.filter { it.uri in pane.selection } }
    val single = selected.singleOrNull()
    val allManageable = selected.all { it.isManageable }
    val clear = { vm.clearSelection() }
    val other = if (tab.dual) tab.pane(1 - idx) else null
    val otherReady = other != null && other.trail.isNotEmpty() && other.canCreate

    val overflow = ArrayList<MenuAction>()
    overflow.add(MenuAction("Select all", Icons.Filled.SelectAll) { vm.selectAll() })
    if (single != null) {
        overflow.add(MenuAction("Rename", Icons.Filled.DriveFileRenameOutline, enabled = single.canRename && allManageable) { actions.rename(single) { clear(); vm.refresh() } })
        overflow.add(MenuAction("Properties", Icons.Filled.Info) { actions.properties(single) })
        val fav = single.uri in favUris
        overflow.add(MenuAction(if (fav) "Remove from favorites" else "Add to favorites", if (fav) Icons.Filled.Star else Icons.Filled.StarBorder) {
            actions.toggleFavorite(single, pane.trail, fav)
        })
        if (FileTypes.isZip(single)) overflow.add(MenuAction("Extract to...", Icons.Filled.Unarchive, enabled = allManageable) { actions.extract(single) })
    }
    overflow.add(MenuAction("Compress to ZIP", Icons.Filled.FolderZip, enabled = allManageable) { actions.compress(selected) { clear() } })
    if (selected.size == 2 && selected.none { it.isDirectory }) {
        overflow.add(MenuAction("Compare files", Icons.Filled.Compare) {
            c.compareSelection.value = selected[0] to selected[1]
            onCompare()
        })
    }
    if (other != null) {
        overflow.add(MenuAction("Copy to other pane", Icons.Filled.ContentCopy, enabled = otherReady && allManageable) {
            c.ops.submit(OperationRequest.Copy(selected, other.trail.last().uri)); clear()
        })
        overflow.add(MenuAction("Move to other pane", Icons.Filled.DriveFileMove, enabled = otherReady && allManageable && selected.all { it.canDelete }) {
            c.ops.submit(OperationRequest.Move(selected, other.trail.last().uri)); clear()
        })
    }

    Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { clear() }) { Icon(Icons.Filled.Close, "Clear selection") }
            Text("${selected.size} selected", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { actions.copyTo(selected) { clear() } }, enabled = allManageable) { Icon(Icons.Filled.ContentCopy, "Copy to") }
            IconButton(onClick = { actions.moveTo(selected) { clear() } }, enabled = allManageable && selected.all { it.canDelete }) { Icon(Icons.Filled.DriveFileMove, "Move to") }
            IconButton(onClick = { actions.share(selected) }) { Icon(Icons.Filled.Share, "Share") }
            IconButton(onClick = { actions.delete(selected) { clear() } }, enabled = allManageable && selected.all { it.canDelete }) { Icon(Icons.Filled.Delete, "Delete") }
            com.bardia.fileexplorer.design.ActionMenuButton(overflow)
        }
    }
}

// ----------------------------------------------------------------------------------------- pane

@Composable
private fun PaneView(
    vm: BrowseViewModel, tab: TabState, idx: Int, dual: Boolean,
    settings: com.bardia.fileexplorer.core.Settings, favUris: Set<String>, actions: EntryActions,
    grant: com.bardia.fileexplorer.ui.common.FolderGrantLauncher, modifier: Modifier
) {
    val c = LocalContainer.current
    val pane = tab.pane(idx)
    val selectionMode = pane.selection.isNotEmpty()
    val isActive = tab.activePane == idx
    Column(modifier) {
        if (dual) {
            Surface(color = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                Text(
                    (if (idx == 0) "Left" else "Right") + (if (isActive) " (active)" else ""),
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }
        }
        Breadcrumbs(
            rootLabel = "This device", trail = pane.trail,
            onRoot = { vm.setActivePane(idx); vm.goRoot(idx) },
            onCrumb = { vm.setActivePane(idx); vm.navigateToCrumb(it, idx) },
            modifier = Modifier.fillMaxWidth()
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                pane.trail.isEmpty() -> LocationsPage(vm, idx, grant)
                pane.error != null -> {
                    val err = pane.error
                    when (err.kind) {
                        BrowseErrorKind.PERMISSION_DENIED -> ErrorPanel(
                            err.message, primaryLabel = "Grant access again", onPrimary = { grant.pick(android.net.Uri.parse(pane.trail.first().uri)) },
                            secondaryLabel = "Locations", onSecondary = { vm.goRoot(idx) }
                        )
                        BrowseErrorKind.NOT_FOUND -> ErrorPanel(
                            err.message, primaryLabel = "Go up", onPrimary = { vm.goUp(idx) },
                            secondaryLabel = "Locations", onSecondary = { vm.goRoot(idx) }
                        )
                        else -> ErrorPanel(
                            err.message, primaryLabel = "Try again", onPrimary = { vm.refresh(idx) },
                            secondaryLabel = "Locations", onSecondary = { vm.goRoot(idx) }
                        )
                    }
                }
                pane.loading && pane.visible.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                pane.visible.isEmpty() -> EmptyState(
                    Icons.Filled.FolderOpen,
                    if (pane.entries.isEmpty()) "This folder is empty" else "Nothing to show",
                    if (pane.entries.isEmpty()) "Files you add here will appear in this list." else "Only hidden items are here. Turn on \"Show hidden files\" in the menu to see them.",
                    actionLabel = if (pane.canCreate) "New folder" else null,
                    onAction = if (pane.canCreate) ({ actions.newFolder(pane.trail.last().uri) { vm.refresh(idx) } }) else null
                )
                else -> FileCollection(
                    entries = pane.visible, viewMode = settings.viewMode, iconSize = settings.iconSize,
                    selected = pane.selection, selectionMode = selectionMode,
                    onClick = { e ->
                        vm.setActivePane(idx)
                        when {
                            selectionMode -> vm.toggleSelect(e.uri, idx)
                            e.isDirectory -> vm.open(e, idx)
                            else -> actions.open(e, pane.trail)
                        }
                    },
                    onLongClick = { e -> vm.setActivePane(idx); vm.toggleSelect(e.uri, idx) },
                    menuFor = { e ->
                        val extra = if (e.isDirectory) listOf(MenuAction("Open in new tab", Icons.Filled.Add) { vm.newTab(pane.trail + Crumb(e.uri, e.name)) }) else emptyList()
                        actions.menu(e, pane.trail, e.uri in favUris, extra)
                    },
                    favorites = favUris, groupByType = settings.groupByType,
                    sortField = settings.sortField, ascending = settings.sortAscending,
                    onSortHeader = { f ->
                        c.updateSettings { s -> if (s.sortField == f) s.copy(sortAscending = !s.sortAscending) else s.copy(sortField = f, sortAscending = true) }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (pane.loading && pane.visible.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
}

// ----------------------------------------------------------------------------------------- locations page

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LocationsPage(vm: BrowseViewModel, idx: Int, grant: com.bardia.fileexplorer.ui.common.FolderGrantLauncher) {
    val c = LocalContainer.current
    val roots by rememberRoots()
    val volumes by rememberVolumes()
    val notify = LocalNotify.current
    val scope = rememberCoroutineScope()
    var removing by remember { mutableStateOf<RootState?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { SectionHeader("Storage") }
        items(volumes, key = { it.description + it.isPrimary }) { v -> VolumeCard(v, grant) }
        item { SectionHeader("Folders you can browse") }
        if (roots.isEmpty()) {
            item {
                EmptyState(
                    Icons.Filled.FolderOpen, "No folders yet",
                    "Choose a folder to start browsing. Android only lets an app use folders you allow.",
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), actionLabel = "Choose a folder", onAction = { grant.pick() }
                )
            }
        } else {
            items(roots, key = { it.root.treeUri }) { r ->
                RootRow(
                    r, onOpen = { vm.navigate(listOf(Crumb(r.root.rootUri, r.root.name)), idx) },
                    onRegrant = { grant.pick(android.net.Uri.parse(r.root.treeUri)) }, onRemove = { removing = r }
                )
            }
            item { TextButton(onClick = { grant.pick() }) { Icon(Icons.Filled.Add, null); Spacer(Modifier.size(6.dp)); Text("Add another folder") } }
        }
        item { SectionHeader("Quick grant") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Documents", "Pictures", "DCIM", "Music", "Movies", "Download").forEach { name ->
                        androidx.compose.material3.AssistChip(
                            onClick = {
                                val hit = roots.filter { it.status == RootStatus.OK }.firstNotNullOfOrNull { StorageRepository.trailTo(it.root, name) }
                                if (hit != null) vm.navigate(hit, idx) else {
                                    if (name == "Download") notify("Android does not allow granting the Download folder itself. Pick a folder inside it.")
                                    grant.pick(StorageRepository.primaryFolderHint(name))
                                }
                            },
                            label = { Text(name) }, leadingIcon = { Icon(Icons.Filled.Folder, null, modifier = Modifier.size(18.dp)) }
                        )
                    }
                }
            }
        }
        item { ScopedStorageNote() }
    }

    removing?.let { r ->
        ConfirmDialog(
            title = "Remove access?", text = "File Explorer will forget \"${r.root.name}\". Your files are not deleted; you can grant access again at any time.",
            confirmLabel = "Remove", destructive = true,
            onConfirm = { removing = null; scope.launch { c.storage.removeRoot(r.root) } },
            onDismiss = { removing = null }
        )
    }
}
