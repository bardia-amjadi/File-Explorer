package com.bardia.fileexplorer.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.bardia.fileexplorer.design.AppTopBar
import com.bardia.fileexplorer.design.EmptyState
import com.bardia.fileexplorer.design.FileGlyph
import com.bardia.fileexplorer.design.FluentCard
import com.bardia.fileexplorer.design.InfoBanner
import com.bardia.fileexplorer.design.StorageBar
import com.bardia.fileexplorer.storage.RootStatus
import com.bardia.fileexplorer.storage.WalkItem
import com.bardia.fileexplorer.tools.AnalysisResult
import com.bardia.fileexplorer.tools.DuplicateGroup
import com.bardia.fileexplorer.ui.common.EntryActions
import com.bardia.fileexplorer.ui.common.rememberRoots
import com.bardia.fileexplorer.util.Format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Granted folders that can currently be scanned. */
@Composable
private fun rememberScanRoots(): List<Crumb> {
    val roots by rememberRoots()
    return roots.filter { it.status == RootStatus.OK }.map { Crumb(it.root.rootUri, it.root.name) }
}

@Composable
private fun NoRoots() = EmptyState(
    Icons.Filled.Warning, "No folders to scan",
    "Android only lets this app read folders you grant. Grant a folder from Browse or Storage first, then come back."
)

@Composable
fun AnalysisScreen(onBack: () -> Unit) {
    val c = LocalContainer.current
    val roots = rememberScanRoots()
    val settings by c.settings.collectAsStateWithLifecycle()
    var result by remember { mutableStateOf<AnalysisResult?>(null) }
    var running by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    fun start() {
        job?.cancel()
        running = true
        job = scope.launch {
            try {
                c.scanner.analyze(roots, settings.showHidden) { result = it }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { /* partial result stays visible */ }
            finally { running = false }
        }
    }
    LaunchedEffect(roots.size) { if (roots.isNotEmpty() && result == null) start() }
    Column(Modifier.fillMaxSize()) {
        AppTopBar("Storage analysis", onBack)
        if (roots.isEmpty()) { NoRoots(); return@Column }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Scans only the folders you granted.", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    if (running) Button(onClick = { job?.cancel(); running = false }) { Text("Stop") } else Button(onClick = { start() }) { Text("Rescan") }
                }
                if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            val r = result
            if (r != null) {
                item {
                    Text("${Format.size(r.totalBytes)} in ${r.files} files, ${r.folders} folders", style = MaterialTheme.typography.titleMedium)
                    if (r.skippedFolders > 0) InfoBanner("${r.skippedFolders} folders could not be read and are not counted.", Modifier.padding(top = 6.dp))
                }
                val max = r.byCategory.values.maxOfOrNull { it.bytes }?.coerceAtLeast(1L) ?: 1L
                items(FileCategory.values().toList().filter { r.byCategory[it] != null }.sortedByDescending { r.byCategory[it]!!.bytes }) { cat ->
                    val s = r.byCategory[cat]!!
                    FluentCard {
                        Column(Modifier.padding(12.dp)) {
                            Row {
                                Text(cat.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                Text("${Format.size(s.bytes)} - ${s.count} files", style = MaterialTheme.typography.bodySmall)
                            }
                            StorageBar(s.bytes.toFloat() / max, Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LargeFilesScreen(actions: EntryActions, onBack: () -> Unit) {
    val c = LocalContainer.current
    val roots = rememberScanRoots()
    val settings by c.settings.collectAsStateWithLifecycle()
    var minMb by remember { mutableStateOf(50) }
    var items by remember { mutableStateOf<List<WalkItem>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    fun start() {
        job?.cancel()
        running = true; items = emptyList()
        job = scope.launch {
            try {
                c.scanner.largeFiles(roots, settings.showHidden, minMb * 1024L * 1024L, 200) { list, stats -> items = list; scanned = stats.scannedFolders.get() }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { } finally { running = false }
        }
    }
    LaunchedEffect(roots.size, minMb) { if (roots.isNotEmpty()) start() }
    Column(Modifier.fillMaxSize()) {
        AppTopBar("Large files", onBack)
        if (roots.isEmpty()) { NoRoots(); return@Column }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(10, 50, 100, 500).forEach { mb -> FilterChip(selected = minMb == mb, onClick = { minMb = mb }, label = { Text(">= $mb MB") }) }
        }
        if (running) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(12.dp)); Text("Scanned $scanned folders...", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall) }
        if (!running && items.isEmpty()) EmptyState(Icons.Filled.Warning, "No large files", "No file of at least $minMb MB was found in the folders you granted.")
        LazyColumn(Modifier.fillMaxSize()) {
            items(items, key = { it.entry.uri }) { w ->
                Row(Modifier.fillMaxWidth().clickable { actions.open(w.entry, w.trail) }.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    FileGlyph(w.entry, 40.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(w.entry.name, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
                        Text(w.path, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(Format.size(w.entry.size), style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = { actions.delete(listOf(w.entry)) { items = items.filterNot { it.entry.uri == w.entry.uri } } }) { Icon(Icons.Filled.Delete, "Delete") }
                }
            }
        }
    }
}

@Composable
fun DuplicatesScreen(actions: EntryActions, onBack: () -> Unit) {
    val c = LocalContainer.current
    val roots = rememberScanRoots()
    val settings by c.settings.collectAsStateWithLifecycle()
    var verify by remember { mutableStateOf(true) }
    var groups by remember { mutableStateOf<List<DuplicateGroup>?>(null) }
    var phase by remember { mutableStateOf("") }
    var hashed by remember { mutableStateOf(0L) }
    var running by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    fun start() {
        job?.cancel(); running = true; groups = null; hashed = 0
        job = scope.launch {
            try {
                groups = c.scanner.duplicates(roots, settings.showHidden, 1024L, verify, { phase = it }, { hashed += it })
            } catch (e: CancellationException) { throw e } catch (e: Exception) { phase = "Scan failed: ${e.message}" } finally { running = false }
        }
    }
    Column(Modifier.fillMaxSize()) {
        AppTopBar("Duplicate files", onBack)
        if (roots.isEmpty()) { NoRoots(); return@Column }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Verify with SHA-256", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (verify) "Slower, but only truly identical files are listed." else "Fast: groups files by size only. These are candidates, not proven duplicates.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = verify, onCheckedChange = { verify = it }, enabled = !running)
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running) Button(onClick = { job?.cancel(); running = false }) { Text("Stop") } else Button(onClick = { start() }) { Text(if (groups == null) "Scan" else "Scan again") }
        }
        if (running) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)); Text("$phase  ${Format.size(hashed)} read", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
        val g = groups
        if (g != null && g.isEmpty()) EmptyState(Icons.Filled.Warning, "No duplicates found", "Nothing in the granted folders matched.")
        if (g != null) LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(g, key = { it.items.first().entry.uri }) { grp ->
                FluentCard {
                    Column(Modifier.padding(12.dp)) {
                        Text("${grp.items.size} files, ${Format.size(grp.size)} each" + if (grp.verified) " - identical (verified)" else " - same size (NOT verified)", style = MaterialTheme.typography.titleSmall)
                        grp.items.forEach { w ->
                            Row(Modifier.fillMaxWidth().clickable { actions.open(w.entry, w.trail) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(w.entry.name, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                                    Text(w.path, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = {
                                    actions.delete(listOf(w.entry)) {
                                        groups = groups?.map { x -> x.copy(items = x.items.filterNot { it.entry.uri == w.entry.uri }) }?.filter { it.items.size > 1 }
                                    }
                                }) { Icon(Icons.Filled.Delete, "Delete this copy") }
                            }
                        }
                    }
                }
            }
        }
    }
}
