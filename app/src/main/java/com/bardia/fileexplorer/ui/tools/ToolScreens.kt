@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.bardia.fileexplorer.ui.tools

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.bardia.fileexplorer.app.LocalContainer
import com.bardia.fileexplorer.core.FileEntry
import com.bardia.fileexplorer.design.AppTopBar
import com.bardia.fileexplorer.design.ConfirmDialog
import com.bardia.fileexplorer.design.EmptyState
import com.bardia.fileexplorer.design.FluentCard
import com.bardia.fileexplorer.design.InfoBanner
import com.bardia.fileexplorer.design.KeyValueRow
import com.bardia.fileexplorer.design.SectionHeader
import com.bardia.fileexplorer.operations.OperationRequest
import com.bardia.fileexplorer.operations.TrashItem
import com.bardia.fileexplorer.tools.ApkDetails
import com.bardia.fileexplorer.tools.CompareResult
import com.bardia.fileexplorer.ui.common.LocalNotify
import com.bardia.fileexplorer.ui.common.FolderPickerDialog
import com.bardia.fileexplorer.util.Format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ------------------------------------------------------------------------------------------ APK

@Composable
fun ApkScreen(uri: String, onBack: () -> Unit) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val notify = LocalNotify.current
    val details by produceState<Result<ApkDetails>?>(null, uri) {
        value = runCatching {
            val e = withContext(Dispatchers.IO) { c.fs.statAny(Uri.parse(uri)) } ?: error("File not found")
            c.apk.inspect(e)
        }
    }
    var showSettingsHint by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        AppTopBar("APK", onBack)
        val d = details
        when {
            d == null -> CircularProgressIndicator(Modifier.padding(32.dp))
            d.isFailure -> EmptyState(Icons.Filled.Warning, "Cannot read this APK", d.exceptionOrNull()?.message ?: "The file may be corrupted.")
            else -> {
                val a = d.getOrThrow()
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val bmp = remember(a) { runCatching { a.icon?.toBitmap(128, 128)?.asImageBitmap() }.getOrNull() }
                        if (bmp != null) Image(bmp, null, Modifier.size(64.dp)) else Icon(Icons.Filled.Android, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.padding(start = 16.dp)) {
                            Text(a.label, style = MaterialTheme.typography.titleLarge)
                            Text(a.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    FluentCard {
                        Column(Modifier.padding(12.dp)) {
                            KeyValueRow("Version", "${a.versionName ?: "?"} (${a.versionCode})")
                            KeyValueRow("Minimum Android SDK", a.minSdk?.toString() ?: "unknown")
                            KeyValueRow("Target SDK", a.targetSdk.toString())
                            KeyValueRow("Permissions", a.permissions.size.toString())
                        }
                    }
                    if (a.permissions.isNotEmpty()) {
                        SectionHeader("Requested permissions")
                        a.permissions.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    InfoBanner("Only install APKs from sources you trust. Android shows its own confirmation and checks the package signature; this app cannot install silently.")
                    if (showSettingsHint) InfoBanner("Android needs you to allow this app to install apps. Enable it on the screen that opened, then return and tap Install again.")
                    Button(onClick = {
                        try {
                            if (!c.apk.canInstall()) { showSettingsHint = true; ctx.startActivity(c.apk.unknownSourcesIntent()) }
                            else ctx.startActivity(c.apk.installIntent(a))
                        } catch (e: ActivityNotFoundException) { notify("No installer is available on this device.") }
                        catch (e: Exception) { notify(e.message ?: "Could not start the installer") }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Install") }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ compare

@Composable
fun CompareScreen(onBack: () -> Unit) {
    val c = LocalContainer.current
    val notify = LocalNotify.current
    val sel by c.compareSelection.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<CompareResult?>(null) }
    var running by remember { mutableStateOf(false) }
    var bytes by remember { mutableStateOf(0L) }
    fun pick(first: Boolean) = { uri: Uri? ->
        if (uri != null) scope.launch {
            val e = withContext(Dispatchers.IO) { runCatching { c.fs.statAny(uri) }.getOrNull() }
            if (e == null || e.isDirectory) notify("Pick a file, not a folder.") else {
                result = null
                c.compareSelection.value = if (first) e to sel.second else sel.first to e
            }
        }
        Unit
    }
    val pickA = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { pick(true)(it) }
    val pickB = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { pick(false)(it) }
    val a = sel.first
    val b = sel.second
    LaunchedEffect(a?.uri, b?.uri) { if (a != null && b != null) result = c.comparer.metadata(a, b) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        AppTopBar("Compare files", onBack)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CompareSlot("File A", a) { pickA.launch(arrayOf("*/*")) }
            CompareSlot("File B", b) { pickB.launch(arrayOf("*/*")) }
            val r = result
            if (a != null && b != null && r != null) {
                FluentCard {
                    Column(Modifier.padding(12.dp)) {
                        Text(r.verdict, style = MaterialTheme.typography.titleSmall)
                        KeyValueRow("Same name", if (r.sameName) "Yes" else "No")
                        KeyValueRow("Same size", if (r.sameSize) "Yes (${Format.size(a.size)})" else "No (${Format.size(a.size)} vs ${Format.size(b.size)})")
                        KeyValueRow("Same modified time", if (r.sameModified) "Yes" else "No")
                        r.hashA?.let { KeyValueRow("SHA-256 A", it) }
                        r.hashB?.let { KeyValueRow("SHA-256 B", it) }
                    }
                }
                if (running) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("${Format.size(bytes)} read", style = MaterialTheme.typography.bodySmall) }
                if (!r.contentVerified) Button(enabled = !running, onClick = {
                    running = true; bytes = 0
                    scope.launch {
                        try { result = c.comparer.verifyContent(a, b) { bytes += it } }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { notify("Could not read one of the files: ${e.message}") }
                        finally { running = false }
                    }
                }) { Text("Verify content (SHA-256)") }
            }
        }
    }
}

@Composable
private fun CompareSlot(title: String, entry: FileEntry?, onPick: () -> Unit) {
    FluentCard {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(entry?.name ?: "Nothing chosen", style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                if (entry != null) Text("${Format.size(entry.size)}  ${Format.date(entry.modified)}", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onPick) { Text(if (entry == null) "Choose" else "Change") }
        }
    }
}

// ------------------------------------------------------------------------------------------ trash

@Composable
fun TrashScreen(onBack: () -> Unit) {
    val c = LocalContainer.current
    val items by c.trash.items.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<TrashItem?>(null) }
    var restoreElsewhere by remember { mutableStateOf<TrashItem?>(null) }
    val settings by c.settings.collectAsStateWithLifecycle()
    val retention = settings.trashRetentionDays
    Column(Modifier.fillMaxSize()) {
        AppTopBar("Trash", onBack) {
            if (items.isNotEmpty()) IconButton(onClick = { confirmEmpty = true }) { Icon(Icons.Filled.DeleteSweep, "Empty trash") }
        }
        InfoBanner("Deleted items are kept here for $retention days, then removed automatically. Items deleted permanently or on removable storage that cannot be moved never appear here.", Modifier.padding(12.dp))
        if (items.isEmpty()) EmptyState(Icons.Filled.Delete, "Trash is empty", "Files you delete go here first so you can restore them.")
        else LazyColumn(Modifier.fillMaxSize()) {
            items(items, key = { it.id }) { t ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.name, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${if (t.size >= 0) Format.size(t.size) else ""}  deleted ${Format.date(t.deletedAt)}" + (t.originalPath?.let { "\nFrom $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { c.ops.submit(OperationRequest.Restore(listOf(t), null)) }) { Icon(Icons.Filled.Restore, "Restore") }
                    TextButton(onClick = { restoreElsewhere = t }) { Text("Restore to...") }
                    IconButton(onClick = { confirmDelete = t }) { Icon(Icons.Filled.Delete, "Delete forever") }
                }
            }
        }
    }
    if (confirmEmpty) ConfirmDialog("Empty trash?", "${items.size} items will be deleted permanently. This cannot be undone.", "Empty trash", {
        confirmEmpty = false; scope.launch { c.trash.purgeAll() }
    }, { confirmEmpty = false }, destructive = true)
    confirmDelete?.let { t ->
        ConfirmDialog("Delete forever?", "\"${t.name}\" will be deleted permanently.", "Delete", {
            confirmDelete = null; scope.launch { c.trash.purge(t.id) }
        }, { confirmDelete = null }, destructive = true)
    }
    restoreElsewhere?.let { t ->
        FolderPickerDialog(
            title = "Restore \"${t.name}\" to", confirmLabel = "Restore here", blocked = emptySet(),
            onPick = { trail -> restoreElsewhere = null; c.ops.submit(OperationRequest.Restore(listOf(t), trail.last().uri)) },
            onDismiss = { restoreElsewhere = null }
        )
    }
}

// ------------------------------------------------------------------------------------------ tools hub

@Composable
fun ToolsScreen(navigate: (String) -> Unit, onBack: (() -> Unit)? = null) {
    data class Tool(val title: String, val text: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val route: String)
    val tools = listOf(
        Tool("Storage analysis", "See which file types use the most space in folders you granted.", Icons.Filled.PieChart, com.bardia.fileexplorer.app.Routes.ANALYSIS),
        Tool("Large files", "Find the biggest files so you can free up space.", Icons.Filled.Storage, com.bardia.fileexplorer.app.Routes.LARGE_FILES),
        Tool("Duplicate files", "Find identical files, verified by SHA-256.", Icons.Filled.ContentCopy, com.bardia.fileexplorer.app.Routes.DUPLICATES),
        Tool("Compare files", "Compare two files by metadata or exact content.", Icons.Filled.CompareArrows, com.bardia.fileexplorer.app.Routes.COMPARE),
        Tool("Trash", "Restore or permanently remove deleted files.", Icons.Filled.Delete, com.bardia.fileexplorer.app.Routes.TRASH),
        Tool("Storage access", "Manage folders and drives this app can read.", Icons.Filled.SdStorage, com.bardia.fileexplorer.app.Routes.STORAGE)
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        AppTopBar("Tools", onBack)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            tools.forEach { t ->
                FluentCard(onClick = { navigate(t.route) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(t.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        Column(Modifier.padding(start = 16.dp)) {
                            Text(t.title, style = MaterialTheme.typography.titleSmall)
                            Text(t.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            SectionHeader("Why some folders are not available")
            Text(
                "Android protects app data and system folders. On Android 11 and newer, other apps cannot read /Android/data or /Android/obb, " +
                    "and the root of storage and the Download folder cannot be granted as a whole. This app uses Android's scoped storage and never asks for \"all files access\", " +
                    "so you choose which folders it may use. APK installs always go through Android's own installer.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}
