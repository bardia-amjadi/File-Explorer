@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.bardia.fileexplorer.ui.common

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bardia.fileexplorer.app.LocalContainer
import com.bardia.fileexplorer.persistence.GrantedRoot
import com.bardia.fileexplorer.storage.RootState
import com.bardia.fileexplorer.storage.VolumeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shows a short message (snackbar) from anywhere in the UI. */
val LocalNotify = staticCompositionLocalOf<(String) -> Unit> { {} }

/** Increments every time the app returns to the foreground; used to re-validate storage grants. */
@Composable
fun rememberResumeTick(): State<Int> {
    val tick = remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick.intValue++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return tick
}

/** Granted folders with a live validity status (re-checked on resume and whenever the list changes). */
@Composable
fun rememberRoots(): State<List<RootState>> {
    val c = LocalContainer.current
    val tick by rememberResumeTick()
    val stored by c.metadata.roots.collectAsStateWithLifecycle(emptyList<GrantedRoot>())
    return produceState(initialValue = emptyList(), tick, stored) {
        value = c.storage.checkRoots()
    }
}

@Composable
fun rememberVolumes(): State<List<VolumeInfo>> {
    val c = LocalContainer.current
    val tick by rememberResumeTick()
    return produceState(initialValue = emptyList(), tick) {
        value = withContext(Dispatchers.IO) { try { c.storage.volumes() } catch (e: Exception) { emptyList() } }
    }
}

/** Wraps the system folder picker (generic or targeted at a volume) and registers the result as a granted root. */
class FolderGrantLauncher(
    private val tree: ManagedActivityResultLauncher<Uri?, Uri?>,
    private val intent: ManagedActivityResultLauncher<Intent, ActivityResult>
) {
    fun pick(initial: Uri? = null) = tree.launch(initial)
    fun pick(volumeIntent: Intent) = intent.launch(volumeIntent)
}

@Composable
fun rememberFolderGrantLauncher(onGranted: (GrantedRoot) -> Unit = {}): FolderGrantLauncher {
    val c = LocalContainer.current
    val notify = LocalNotify.current
    val scope = rememberCoroutineScope()
    val callback by rememberUpdatedState(onGranted)
    val handle: (Uri?) -> Unit = { uri ->
        if (uri != null) scope.launch {
            try {
                callback(c.storage.addRoot(uri))
                notify("Access granted. You can now browse this folder.")
            } catch (e: Exception) {
                notify(e.message ?: "Could not use this folder")
            }
        }
    }
    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { handle(it) }
    val intent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { handle(it.data?.data) }
    return remember(tree, intent) { FolderGrantLauncher(tree, intent) }
}

/** A readable location string. External-storage document ids look like "primary:DCIM/Camera". */
fun friendlyPath(uriString: String, displayPath: String? = null): String {
    if (!displayPath.isNullOrBlank()) return displayPath
    return try {
        val uri = Uri.parse(uriString)
        val id = DocumentsContract.getDocumentId(uri)
        if (uri.authority == "com.android.externalstorage.documents") {
            val volume = id.substringBefore(':')
            val rest = id.substringAfter(':', "")
            val vol = if (volume == "primary") "Internal storage" else "Removable storage ($volume)"
            if (rest.isEmpty()) vol else "$vol/$rest"
        } else id
    } catch (e: Exception) {
        uriString
    }
}
