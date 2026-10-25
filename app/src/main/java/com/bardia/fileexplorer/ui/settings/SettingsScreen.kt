@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.bardia.fileexplorer.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bardia.fileexplorer.app.LocalContainer
import com.bardia.fileexplorer.core.IconSize
import com.bardia.fileexplorer.core.SortField
import com.bardia.fileexplorer.core.StartDestination
import com.bardia.fileexplorer.core.ThemeMode
import com.bardia.fileexplorer.core.ViewMode
import com.bardia.fileexplorer.design.AppTopBar
import com.bardia.fileexplorer.design.ConfirmDialog
import com.bardia.fileexplorer.design.FluentCard
import com.bardia.fileexplorer.design.KeyValueRow
import com.bardia.fileexplorer.design.SectionHeader
import com.bardia.fileexplorer.ui.common.LocalNotify
import kotlinx.coroutines.launch

@Composable
private fun <T> Choice(title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) }) }
        }
    }
}

@Composable
private fun Toggle(title: String, text: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (text != null) Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun SettingsScreen(onStorage: () -> Unit, onTrash: () -> Unit, onBack: (() -> Unit)? = null) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val notify = LocalNotify.current
    val s by c.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmClearRecents by remember { mutableStateOf(false) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        AppTopBar("Settings", onBack)
        SectionHeader("Appearance", Modifier.padding(horizontal = 16.dp))
        Choice("Theme", ThemeMode.values().toList(), s.theme, { it.label }) { v -> c.updateSettings { it.copy(theme = v) } }
        SectionHeader("Browsing", Modifier.padding(horizontal = 16.dp))
        Choice("Default view", ViewMode.values().toList(), s.viewMode, { it.label }) { v -> c.updateSettings { it.copy(viewMode = v) } }
        Choice("Icon size", IconSize.values().toList(), s.iconSize, { it.label }) { v -> c.updateSettings { it.copy(iconSize = v) } }
        Choice("Sort by", SortField.values().toList(), s.sortField, { it.label }) { v -> c.updateSettings { it.copy(sortField = v) } }
        Choice("Direction", listOf(true, false), s.sortAscending, { if (it) "Ascending" else "Descending" }) { v -> c.updateSettings { it.copy(sortAscending = v) } }
        Toggle("Folders first", checked = s.foldersFirst) { v -> c.updateSettings { it.copy(foldersFirst = v) } }
        Toggle("Group by type", checked = s.groupByType) { v -> c.updateSettings { it.copy(groupByType = v) } }
        Toggle("Show hidden files", "Files and folders whose names start with a dot.", s.showHidden) { v -> c.updateSettings { it.copy(showHidden = v) } }
        Choice("Open at start", StartDestination.values().toList(), s.startDestination, { it.label }) { v -> c.updateSettings { it.copy(startDestination = v) } }
        SectionHeader("Search", Modifier.padding(horizontal = 16.dp))
        Toggle("Include hidden files in search", checked = s.searchIncludeHidden) { v -> c.updateSettings { it.copy(searchIncludeHidden = v) } }
        SectionHeader("History and trash", Modifier.padding(horizontal = 16.dp))
        Choice("Recent files to keep", listOf(10, 25, 50, 100), s.recentLimit, { it.toString() }) { v ->
            c.updateSettings { it.copy(recentLimit = v) }
            scope.launch { c.metadata.trimRecents(v) }
        }
        OutlinedButton(onClick = { confirmClearRecents = true }, Modifier.padding(horizontal = 16.dp)) { Text("Clear recent files") }
        Choice("Keep deleted items for", listOf(7, 14, 30, 90), s.trashRetentionDays, { "$it days" }) { v -> c.updateSettings { it.copy(trashRetentionDays = v) } }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onTrash) { Text("Open trash") }
            OutlinedButton(onClick = { confirmEmptyTrash = true }) { Text("Empty trash") }
        }
        SectionHeader("Storage access", Modifier.padding(horizontal = 16.dp))
        com.bardia.fileexplorer.ui.common.AllFilesAccessCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        OutlinedButton(onClick = onStorage, Modifier.padding(horizontal = 16.dp)) { Text("Manage folders and drives") }
        SectionHeader("About and compatibility", Modifier.padding(horizontal = 16.dp))
        FluentCard(Modifier.padding(horizontal = 16.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                KeyValueRow("Version", version)
                KeyValueRow("Requires", "Android 12 or newer")
                Text(
                    "File Explorer works offline and has no ads, accounts or analytics. It uses Android's scoped storage: you grant folders and drives, " +
                        "and Android keeps /Android/data, /Android/obb and app-private folders off limits. It never requests \"all files access\".",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Some file providers (cloud drives, certain USB devices) do not support every operation such as rename, move or delete; " +
                        "when that happens the action is hidden or an explanation is shown.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
    if (confirmClearRecents) ConfirmDialog("Clear recent files?", "Only the list is cleared; no files are deleted.", "Clear", {
        confirmClearRecents = false; scope.launch { c.metadata.clearRecents() }; notify("Recent files cleared")
    }, { confirmClearRecents = false })
    if (confirmEmptyTrash) ConfirmDialog("Empty trash?", "All items in the trash will be deleted permanently.", "Empty trash", {
        confirmEmptyTrash = false; scope.launch { c.trash.purgeAll() }; notify("Trash emptied")
    }, { confirmEmptyTrash = false }, destructive = true)
}
