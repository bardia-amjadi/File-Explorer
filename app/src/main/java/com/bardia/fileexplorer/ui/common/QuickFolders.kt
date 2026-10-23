package com.bardia.fileexplorer.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.bardia.fileexplorer.core.Crumb
import com.bardia.fileexplorer.persistence.FavoriteItem
import com.bardia.fileexplorer.persistence.RecentItem
import com.bardia.fileexplorer.core.FileEntry
import com.bardia.fileexplorer.storage.RootStatus
import com.bardia.fileexplorer.storage.StorageRepository

val QuickFolderNames = listOf("Documents", "Pictures", "DCIM", "Music", "Movies", "Download")

/**
 * Returns a function that opens a well-known folder (e.g. "Documents"). If a granted folder already contains it
 * the trail is delivered to [onTrail]; otherwise the system picker opens pre-pointed at that folder.
 */
@Composable
fun rememberQuickFolderOpener(onTrail: (List<Crumb>) -> Unit): (String) -> Unit {
    val roots by rememberRoots()
    val notify = LocalNotify.current
    val callback by rememberUpdatedState(onTrail)
    val grant = rememberFolderGrantLauncher { root -> callback(listOf(Crumb(root.rootUri, root.name))) }
    return { name ->
        val hit = roots.filter { it.status == RootStatus.OK }.firstNotNullOfOrNull { StorageRepository.trailTo(it.root, name) }
        if (hit != null) callback(hit) else {
            if (name == "Download") notify("Android does not allow granting the Download folder itself. Pick a folder inside it.")
            grant.pick(StorageRepository.primaryFolderHint(name))
        }
    }
}

fun FavoriteItem.toEntry() = FileEntry(
    uri = uri, name = name, isDirectory = isDirectory, mime = mime,
    parentUri = if (isDirectory) trail.getOrNull(trail.size - 2)?.uri else trail.lastOrNull()?.uri,
    displayPath = trail.joinToString("/") { it.name }.ifEmpty { null }
)

fun RecentItem.toEntry() = FileEntry(
    uri = uri, name = name, isDirectory = false, size = size, modified = modified, mime = mime,
    parentUri = parentTrail.lastOrNull()?.uri,
    displayPath = parentTrail.joinToString("/") { it.name }.ifEmpty { null }
)
