package com.bardia.fileexplorer.ui.preview

import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.bardia.fileexplorer.app.LocalContainer
import com.bardia.fileexplorer.core.FileCategory
import com.bardia.fileexplorer.core.FileEntry
import com.bardia.fileexplorer.design.AppTopBar
import com.bardia.fileexplorer.design.EmptyState
import com.bardia.fileexplorer.ui.common.EntryActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Picks the right preview for a file. Anything unsupported falls back to "open with another app". */
@Composable
fun ViewerScreen(uri: String, actions: EntryActions, onBack: () -> Unit) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val entry by produceState<FileEntry?>(null, uri) {
        value = withContext(Dispatchers.IO) { runCatching { c.fs.statAny(Uri.parse(uri)) }.getOrNull() }
    }
    val e = entry
    val isVideo = e?.category == FileCategory.VIDEOS
    Column(Modifier.fillMaxSize()) {
        AppTopBar(e?.name ?: "Preview", onBack) {
            if (isVideo) IconButton(onClick = {
                val a = ctx.findActivity()
                if (a != null) a.requestedOrientation =
                    if (a.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }) { Icon(Icons.Filled.ScreenRotation, "Rotate video") }
            if (e != null) {
                IconButton(onClick = { actions.share(listOf(e)) }) { Icon(Icons.Filled.Share, "Share") }
                IconButton(onClick = { actions.properties(e) }) { Icon(Icons.Filled.Info, "Properties") }
                IconButton(onClick = { actions.openExternally(e) }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open with another app") }
            }
        }
        Box(Modifier.fillMaxSize()) {
            when {
                e == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                e.category == FileCategory.IMAGES -> ImageViewer(e)
                e.category == FileCategory.VIDEOS -> VideoPlayer(e) { actions.openExternally(e) }
                e.category == FileCategory.AUDIO -> AudioPlayer(e) { actions.openExternally(e) }
                e.extension == "pdf" || e.mime == "application/pdf" -> PdfViewer(e) { actions.openExternally(e) }
                else -> TextViewer(e) { actions.openExternally(e) }
            }
        }
    }
    // The properties dialog etc. are hosted by the shell's EntryActions.Dialogs().
}
