@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.bardia.fileexplorer.ui.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bardia.fileexplorer.app.LocalContainer
import com.bardia.fileexplorer.core.Crumb
import com.bardia.fileexplorer.core.FileCategory
import com.bardia.fileexplorer.core.FileSorter
import com.bardia.fileexplorer.core.SortField
import com.bardia.fileexplorer.design.AppTopBar
import com.bardia.fileexplorer.design.EmptyState
import com.bardia.fileexplorer.design.FileCollection
import com.bardia.fileexplorer.design.InfoBanner
import com.bardia.fileexplorer.design.MenuAction
import com.bardia.fileexplorer.storage.RootStatus
import com.bardia.fileexplorer.ui.browse.BrowseViewModel
import com.bardia.fileexplorer.ui.common.EntryActions
import com.bardia.fileexplorer.ui.common.rememberRoots
import com.bardia.fileexplorer.util.Format

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(sv: SearchViewModel, vm: BrowseViewModel, actions: EntryActions, onBack: () -> Unit, onOpenBrowse: () -> Unit) {
    val c = LocalContainer.current
    val ui by sv.ui.collectAsStateWithLifecycle()
    val settings by c.settings.collectAsStateWithLifecycle()
    val roots by rememberRoots()
    val okRoots = roots.filter { it.status == RootStatus.OK }
    val tabs by vm.tabs.collectAsStateWithLifecycle()
    val activeId by vm.activeTabId.collectAsStateWithLifecycle()
    val currentFolder: Crumb? = tabs.firstOrNull { it.id == activeId }?.active?.trail?.lastOrNull()
    val form = ui.form
    var showFilters by remember { mutableStateOf(false) }
    var datePicker by remember { mutableStateOf<String?>(null) } // "after" | "before"

    fun scopes(): List<Crumb> {
        val chosen = if (form.scopeUris.isEmpty()) okRoots else okRoots.filter { it.root.rootUri in form.scopeUris }
        val list = chosen.map { Crumb(it.root.rootUri, it.root.name) }.toMutableList()
        if (currentFolder != null && currentFolder.uri in form.scopeUris) list.add(currentFolder)
        return list
    }

    val sorted = remember(ui.results, form.sortField, form.ascending) {
        FileSorter.sort(ui.results.map { it.entry }, form.sortField, form.ascending, foldersFirst = true)
    }
    val byUri = remember(ui.results) { ui.results.associateBy { it.entry.uri } }

    Scaffold(
        topBar = { AppTopBar("Search", onBack) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = form.text, onValueChange = { v -> sv.edit { it.copy(text = v) } }, singleLine = true,
                    placeholder = { Text("File or folder name") }, modifier = Modifier.weight(1f),
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = { if (form.text.isNotEmpty()) IconButton(onClick = { sv.edit { it.copy(text = "") } }) { Icon(Icons.Filled.Clear, "Clear text") } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { sv.start(scopes()) })
                )
                IconButton(onClick = { showFilters = !showFilters }) { Icon(Icons.Filled.FilterList, if (showFilters) "Hide filters" else "Show filters") }
                if (ui.running) IconButton(onClick = { sv.cancel() }) { Icon(Icons.Filled.Stop, "Stop search") }
                else IconButton(onClick = { sv.start(scopes()) }) { Icon(Icons.Filled.Search, "Run search") }
            }
            if (ui.running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))

            if (showFilters) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Where to search", style = MaterialTheme.typography.labelLarge)
                    if (okRoots.isEmpty() && currentFolder == null) {
                        Text("No accessible folders. Grant a folder in Browse first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = form.scopeUris.isEmpty(), onClick = { sv.edit { it.copy(scopeUris = emptySet()) } }, label = { Text("All granted folders") })
                        okRoots.forEach { r ->
                            FilterChip(
                                selected = r.root.rootUri in form.scopeUris,
                                onClick = { sv.edit { f -> f.copy(scopeUris = if (r.root.rootUri in f.scopeUris) f.scopeUris - r.root.rootUri else f.scopeUris + r.root.rootUri) } },
                                label = { Text(r.root.name) }
                            )
                        }
                        if (currentFolder != null) FilterChip(
                            selected = currentFolder.uri in form.scopeUris,
                            onClick = { sv.edit { f -> f.copy(scopeUris = if (currentFolder.uri in f.scopeUris) f.scopeUris - currentFolder.uri else f.scopeUris + currentFolder.uri) } },
                            label = { Text("Current folder: ${currentFolder.name}") }
                        )
                    }
                    Text("Category", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = form.category == null, onClick = { sv.edit { it.copy(category = null) } }, label = { Text("Any") })
                        FileCategory.values().filter { it != FileCategory.OTHER }.forEach { cat ->
                            FilterChip(selected = form.category == cat, onClick = { sv.edit { it.copy(category = cat) } }, label = { Text(cat.label) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(form.extensions, { v -> sv.edit { it.copy(extensions = v) } }, label = { Text("Extensions") }, placeholder = { Text("jpg, png") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(form.mime, { v -> sv.edit { it.copy(mime = v) } }, label = { Text("MIME type") }, placeholder = { Text("image/*") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(form.minMb, { v -> sv.edit { it.copy(minMb = v) } }, label = { Text("Min size (MB)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                        OutlinedTextField(form.maxMb, { v -> sv.edit { it.copy(maxMb = v) } }, label = { Text("Max size (MB)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    }
                    Text("Modified", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DatePreset.values().forEach { p -> FilterChip(selected = form.datePreset == p, onClick = { sv.edit { it.copy(datePreset = p) } }, label = { Text(p.label) }) }
                    }
                    if (form.datePreset == DatePreset.CUSTOM) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { datePicker = "after" }) { Text("From: " + (form.customAfter?.let { Format.shortDate(it) } ?: "any")) }
                            TextButton(onClick = { datePicker = "before" }) { Text("To: " + (form.customBefore?.let { Format.shortDate(it) } ?: "any")) }
                        }
                    }
                    Text("Sort results by", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SortField.values().forEach { f -> FilterChip(selected = form.sortField == f, onClick = { sv.edit { it.copy(sortField = f) } }, label = { Text(f.label) }) }
                        FilterChip(selected = form.ascending, onClick = { sv.edit { it.copy(ascending = !it.ascending) } }, label = { Text(if (form.ascending) "Ascending" else "Descending") })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = form.includeHidden, onCheckedChange = { v -> sv.edit { it.copy(includeHidden = v) } })
                        Text("  Include hidden files", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }

            if (ui.ranOnce) {
                InfoBanner(
                    "${sorted.size} result(s) in ${ui.scopeCount} selected folder(s)" +
                        (if (ui.skippedFolders > 0) "; ${ui.skippedFolders} sub-folder(s) could not be read" else "") +
                        (if (ui.running) " - searching..." else ". Search covers only the folders you granted."),
                    Modifier.padding(12.dp)
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (!ui.ranOnce) {
                    EmptyState(Icons.Filled.Search, "Search your folders", "Find files by name, extension, type, size or date. Search only looks inside folders you have granted access to.")
                } else if (sorted.isEmpty() && !ui.running) {
                    EmptyState(Icons.Filled.Search, "No results", "Try a shorter name or relax the filters.")
                } else FileCollection(
                    entries = sorted, viewMode = com.bardia.fileexplorer.core.ViewMode.LIST, iconSize = settings.iconSize,
                    selected = emptySet(), selectionMode = false,
                    onClick = { e ->
                        val r = byUri[e.uri]
                        if (e.isDirectory) { vm.navigate((r?.trail ?: emptyList()) + Crumb(e.uri, e.name)); onOpenBrowse() } else actions.open(e, r?.trail ?: emptyList())
                    },
                    onLongClick = {},
                    subtitle = { e -> listOf(if (e.isDirectory) "" else Format.size(e.size), Format.shortDate(e.modified), e.displayPath ?: "").filter { it.isNotEmpty() && it != "—" }.joinToString("  •  ") },
                    menuFor = { e ->
                        val r = byUri[e.uri]
                        val trail = r?.trail ?: emptyList()
                        actions.menu(e, trail, false, listOf(MenuAction("Show containing folder", Icons.Filled.FolderOpen) { vm.navigate(trail); onOpenBrowse() }))
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    datePicker?.let { which ->
        val state = rememberDatePickerState(initialSelectedDateMillis = if (which == "after") form.customAfter else form.customBefore)
        DatePickerDialog(
            onDismissRequest = { datePicker = null },
            confirmButton = {
                TextButton(onClick = {
                    val v = state.selectedDateMillis
                    sv.edit { if (which == "after") it.copy(customAfter = v) else it.copy(customBefore = v) }
                    datePicker = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { datePicker = null }) { Text("Cancel") } }
        ) { DatePicker(state = state) }
    }
}
