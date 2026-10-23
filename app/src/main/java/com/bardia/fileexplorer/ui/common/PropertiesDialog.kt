package com.bardia.fileexplorer.ui.common

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bardia.fileexplorer.app.LocalContainer
import com.bardia.fileexplorer.core.FileCategory
import com.bardia.fileexplorer.core.FileEntry
import com.bardia.fileexplorer.core.FileTypes
import com.bardia.fileexplorer.design.KeyValueRow
import com.bardia.fileexplorer.tools.Hashing
import com.bardia.fileexplorer.tools.SizeResult
import com.bardia.fileexplorer.util.Format
import com.bardia.fileexplorer.util.IntentUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** File/folder properties with on-demand folder size (cancellable) and streaming SHA-256. */
@Composable
fun PropertiesDialog(entry: FileEntry, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val notify = LocalNotify.current
    val scope = rememberCoroutineScope()
    val uri = remember(entry.uri) { Uri.parse(entry.uri) }

    // Media metadata (dimensions / duration) read off the main thread.
    val media by produceState<List<Pair<String, String>>>(emptyList(), entry.uri) {
        value = withContext(Dispatchers.IO) {
            val out = ArrayList<Pair<String, String>>()
            try {
                if (!entry.isDirectory && entry.category == FileCategory.IMAGES) {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    c.fs.openInput(uri).use { BitmapFactory.decodeStream(it, null, o) }
                    if (o.outWidth > 0) out.add("Dimensions" to "${o.outWidth} x ${o.outHeight} px")
                } else if (!entry.isDirectory && (entry.category == FileCategory.AUDIO || entry.category == FileCategory.VIDEOS)) {
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(context, uri)
                        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { out.add("Duration" to Format.duration(it)) }
                        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        if (w != null && h != null) out.add("Resolution" to "$w x $h px")
                        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let { out.add("Bitrate" to "${it / 1000} kbps") }
                        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let { out.add("Artist" to it) }
                        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { out.add("Title" to it) }
                    } finally {
                        r.release()
                    }
                }
            } catch (e: Exception) {
                // Metadata is optional; unreadable media simply shows nothing extra.
            }
            out
        }
    }

    // Folder size: starts automatically, can be cancelled.
    var sizeResult by remember { mutableStateOf<SizeResult?>(null) }
    var sizeDone by remember { mutableStateOf(false) }
    var sizeJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(entry.uri) {
        if (entry.isDirectory) {
            sizeJob = scope.launch {
                try {
                    sizeResult = withContext(Dispatchers.IO) { c.sizer.compute(entry) { p -> sizeResult = p } }
                    sizeDone = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    sizeDone = true
                }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { sizeJob?.cancel() } }

    // SHA-256 on demand.
    var hash by remember { mutableStateOf<String?>(null) }
    var hashProgress by remember { mutableStateOf<Float?>(null) }
    var hashJob by remember { mutableStateOf<Job?>(null) }
    var hashError by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { hashJob?.cancel() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entry.isDirectory) "Folder properties" else "File properties") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                KeyValueRow("Name", entry.name)
                if (entry.extension.isNotEmpty()) KeyValueRow("Extension", entry.extension)
                KeyValueRow("Type", FileTypes.typeLabel(entry))
                if (!entry.isDirectory) KeyValueRow("MIME type", FileTypes.effectiveMime(entry) ?: IntentUtil.mimeFor(entry))
                if (entry.isDirectory) {
                    val r = sizeResult
                    KeyValueRow(
                        "Size",
                        if (r == null) "Calculating..." else Format.size(r.bytes) + (if (sizeDone) "" else "  (counting...)")
                    )
                    if (r != null) KeyValueRow("Contains", "${r.files} files, ${r.folders.coerceAtLeast(0)} folders")
                    if (r != null && r.inaccessible > 0) KeyValueRow("Unreadable", "${r.inaccessible} folder(s) could not be read; size is a lower bound")
                    if (!sizeDone) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp))
                        TextButton(onClick = { sizeJob?.cancel(); sizeDone = true }) { Text("Stop counting") }
                    }
                } else {
                    KeyValueRow("Size", if (entry.size >= 0) "${Format.size(entry.size)} (${entry.size} bytes)" else "Unknown")
                }
                KeyValueRow("Modified", Format.date(entry.modified))
                KeyValueRow("Created", "Not provided by Android storage providers")
                KeyValueRow("Location", friendlyPath(entry.uri, entry.displayPath))
                val rw = if (entry.isDirectory) {
                    if (entry.canWriteInto) "Read and write" else "Read only"
                } else if (entry.canWriteContent) "Read and write" else "Read only (or unknown)"
                KeyValueRow("Access", rw)
                media.forEach { (k, v) -> KeyValueRow(k, v) }

                if (!entry.isDirectory) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text("SHA-256", style = MaterialTheme.typography.titleSmall)
                    val h = hash
                    when {
                        h != null -> {
                            Text(h, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            TextButton(onClick = { IntentUtil.copyText(context, "SHA-256", h); notify("Hash copied") }) { Text("Copy hash") }
                        }
                        hashProgress != null -> {
                            LinearProgressIndicator(progress = { hashProgress ?: 0f }, modifier = Modifier.fillMaxWidth())
                            TextButton(onClick = { hashJob?.cancel(); hashProgress = null }) { Text("Cancel") }
                        }
                        else -> {
                            hashError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            OutlinedButton(onClick = {
                                hashError = null
                                hashProgress = 0f
                                hashJob = scope.launch {
                                    var done = 0L
                                    try {
                                        hash = withContext(Dispatchers.IO) {
                                            c.fs.openInput(uri).use { input ->
                                                Hashing.sha256(input) { n ->
                                                    done += n
                                                    if (entry.size > 0) hashProgress = (done.toFloat() / entry.size).coerceIn(0f, 1f)
                                                }
                                            }
                                        }
                                        hashProgress = null
                                    } catch (e: CancellationException) {
                                        hashProgress = null
                                        throw e
                                    } catch (e: Exception) {
                                        hashProgress = null
                                        hashError = "Could not read the file: ${e.message}"
                                    }
                                }
                            }) { Text("Calculate SHA-256") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
