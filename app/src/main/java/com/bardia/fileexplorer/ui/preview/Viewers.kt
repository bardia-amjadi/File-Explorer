@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.bardia.fileexplorer.ui.preview

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.bardia.fileexplorer.app.LocalContainer
import com.bardia.fileexplorer.core.FileEntry
import com.bardia.fileexplorer.design.EmptyState
import com.bardia.fileexplorer.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

fun Context.findActivity(): Activity? {
    var c = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

// ------------------------------------------------------------------------------------------ images

/** Image viewer with pinch-zoom, pan and double-tap to zoom. Coil downsamples large images safely. */
@Composable
fun ImageViewer(entry: FileEntry) {
    val ctx = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var failed by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    offset = if (scale <= 1f) Offset.Zero else offset + pan
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                })
            },
        contentAlignment = Alignment.Center
    ) {
        if (failed) {
            Text("This image could not be decoded.", color = Color.White)
        } else {
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(Uri.parse(entry.uri)).size(2048).build(),
                contentDescription = entry.name, contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
            )
        }
    }
}

// ------------------------------------------------------------------------------------------ text

private const val MAX_TEXT_BYTES = 1_000_000
private const val MAX_TEXT_LINES = 50_000

private data class TextLoad(val lines: List<String>, val truncated: Boolean, val binary: Boolean)

/** Text/source viewer with safeguards: size cap, binary detection, malformed UTF-8 replaced, line cap. */
@Composable
fun TextViewer(entry: FileEntry, onOpenExternally: () -> Unit) {
    val c = LocalContainer.current
    var wrap by remember { mutableStateOf(false) }
    val load by produceState<Result<TextLoad>?>(null, entry.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val out = ByteArrayOutputStream()
                var truncated = false
                c.fs.openInput(Uri.parse(entry.uri)).use { input ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        val room = MAX_TEXT_BYTES - out.size()
                        if (n > room) { out.write(buf, 0, room); truncated = true; break }
                        out.write(buf, 0, n)
                    }
                }
                val bytes = out.toByteArray()
                val binary = bytes.take(8000).any { it == 0.toByte() }
                if (binary) TextLoad(emptyList(), truncated, true) else {
                    val text = String(bytes, Charsets.UTF_8) // malformed sequences become U+FFFD, never an exception
                    var lines = text.split("\n")
                    var cut = truncated
                    if (lines.size > MAX_TEXT_LINES) { lines = lines.take(MAX_TEXT_LINES); cut = true }
                    TextLoad(lines.map { it.trimEnd('\r').let { l -> if (l.length > 3000) l.take(3000) + " …" else l } }, cut, false)
                }
            }
        }
    }
    val result = load
    when {
        result == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        result.isFailure -> EmptyState(Icons.Filled.Warning, "Cannot read this file", result.exceptionOrNull()?.message ?: "Unknown error")
        result.getOrThrow().binary -> EmptyState(
            Icons.Filled.Warning, "Binary file", "This file does not look like text, so it is not shown here.",
            actionLabel = "Open with another app", onAction = onOpenExternally
        )
        else -> {
            val t = result.getOrThrow()
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${t.lines.size} lines" + if (t.truncated) " - showing the first part only (large file)" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)
                    )
                    androidx.compose.material3.TextButton(onClick = { wrap = !wrap }) { Text(if (wrap) "No wrap" else "Wrap") }
                }
                val hScroll = rememberScrollState()
                val gutter = t.lines.size.toString().length
                LazyColumn(Modifier.fillMaxSize().then(if (wrap) Modifier else Modifier.horizontalScroll(hScroll))) {
                    itemsIndexed(t.lines) { i, line ->
                        Row(Modifier.padding(horizontal = 8.dp)) {
                            Text(
                                (i + 1).toString().padStart(gutter), fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(end = 10.dp)
                            )
                            Text(line.ifEmpty { " " }, fontFamily = FontFamily.Monospace, fontSize = 13.sp, softWrap = wrap)
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ PDF

private class PdfHolder(val renderer: PdfRenderer, val pfd: ParcelFileDescriptor) {
    val mutex = Mutex() // PdfRenderer allows one open page at a time and is not thread-safe.
    @Volatile var closed = false
    fun close() {
        runBlocking {
            mutex.withLock {
                closed = true
                runCatching { renderer.close() }
                runCatching { pfd.close() }
            }
        }
    }
}

@Composable
fun PdfViewer(entry: FileEntry, onOpenExternally: () -> Unit) {
    val c = LocalContainer.current
    var holder by remember { mutableStateOf<PdfHolder?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(entry.uri) {
        try {
            holder = withContext(Dispatchers.IO) {
                val pfd = c.fs.openDescriptor(Uri.parse(entry.uri))
                try { PdfHolder(PdfRenderer(pfd), pfd) } catch (e: Exception) { pfd.close(); throw e }
            }
        } catch (e: SecurityException) {
            error = "This PDF is password protected or restricted and cannot be shown here."
        } catch (e: Exception) {
            error = "This PDF could not be opened (${e.message ?: "corrupted or unsupported"})."
        }
    }
    DisposableEffect(Unit) { onDispose { holder?.close() } }

    val h = holder
    when {
        error != null -> EmptyState(Icons.Filled.Warning, "Cannot show PDF", error ?: "", actionLabel = "Open with another app", onAction = onOpenExternally)
        h == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            val widthPx = with(LocalDensity.current) { maxWidth.toPx().toInt() }.coerceIn(200, 1800)
            val pages = h.renderer.pageCount
            val state = rememberLazyListState()
            LazyColumn(state = state, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pages) { index -> PdfPageView(h, index, widthPx) }
            }
            Text(
                "${state.firstVisibleItemIndex + 1} / $pages", color = Color.White, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).background(Color(0x99000000), MaterialTheme.shapes.small).padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun PdfPageView(h: PdfHolder, index: Int, widthPx: Int) {
    var bmp by remember(index, widthPx) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(index, widthPx) {
        bmp = withContext(Dispatchers.IO) {
            h.mutex.withLock {
                if (h.closed) null else h.renderer.openPage(index).use { page ->
                    val height = (widthPx.toFloat() / page.width * page.height).toInt().coerceAtLeast(1)
                    val b = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                    b.eraseColor(AColor.WHITE)
                    page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    b
                }
            }
        }
    }
    val b = bmp
    if (b == null) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.72f).background(Color.White), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
    } else {
        Image(b.asImageBitmap(), contentDescription = "Page ${index + 1}", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
    }
}

// ------------------------------------------------------------------------------------------ media

@Composable
private fun rememberPlayer(uri: Uri, onError: (String) -> Unit): ExoPlayer {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val player = remember(uri) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    onError("This file cannot be played here (${error.errorCodeName}).")
                }
            })
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player, owner) {
        // Playback pauses when the app goes to the background; no background service is used.
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) player.pause() }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            player.release()
        }
    }
    return player
}

@Composable
fun AudioPlayer(entry: FileEntry, onOpenExternally: () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    val player = rememberPlayer(Uri.parse(entry.uri)) { error = it }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            playing = player.isPlaying
            delay(250)
        }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Filled.Audiotrack, null, modifier = Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(entry.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(24.dp))
        val max = duration.coerceAtLeast(1L).toFloat()
        Slider(value = position.toFloat().coerceIn(0f, max), onValueChange = { position = it.toLong(); player.seekTo(it.toLong()) }, valueRange = 0f..max, enabled = duration > 0)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(Format.duration(position), style = MaterialTheme.typography.bodySmall)
            Text(if (duration > 0) Format.duration(duration) else "--:--", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconButton(onClick = { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) }) { Icon(Icons.Filled.Replay10, "Back 10 seconds") }
            FilledIconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }, modifier = Modifier.size(64.dp)) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play", modifier = Modifier.size(32.dp))
            }
            IconButton(onClick = { player.seekTo(player.currentPosition + 10_000) }) { Icon(Icons.Filled.Forward10, "Forward 10 seconds") }
        }
        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onOpenExternally, modifier = Modifier.padding(top = 8.dp)) { Text("Open with another app") }
        }
    }
}

@Composable
fun VideoPlayer(entry: FileEntry, onOpenExternally: () -> Unit) {
    val ctx = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    val player = rememberPlayer(Uri.parse(entry.uri)) { error = it }
    // Orientation handling: the layout follows the device; the top-bar button in ViewerScreen can lock landscape.
    DisposableEffect(Unit) { onDispose { ctx.findActivity()?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c -> PlayerView(c).apply { this.player = player; useController = true } },
            modifier = Modifier.fillMaxSize()
        )
        error?.let {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(it, color = Color.White)
                Button(onClick = onOpenExternally, modifier = Modifier.padding(top = 8.dp)) { Text("Open with another app") }
            }
        }
    }
}
