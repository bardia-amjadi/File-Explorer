package com.bardia.fileexplorer.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bardia.fileexplorer.design.ActionMenuButton
import com.bardia.fileexplorer.design.FluentCard
import com.bardia.fileexplorer.design.MenuAction
import com.bardia.fileexplorer.design.StorageBar
import com.bardia.fileexplorer.storage.RootState
import com.bardia.fileexplorer.storage.RootStatus
import com.bardia.fileexplorer.storage.VolumeInfo
import com.bardia.fileexplorer.util.Format

/** One storage volume with real capacity numbers; "unavailable" is shown when Android does not expose them. */
@Composable
fun VolumeCard(volume: VolumeInfo, grant: FolderGrantLauncher, modifier: Modifier = Modifier) {
    FluentCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val icon = when {
                    volume.isPrimary -> Icons.Filled.PhoneAndroid
                    volume.description.contains("USB", ignoreCase = true) -> Icons.Filled.Usb
                    else -> Icons.Filled.SdCard
                }
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(volume.description, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (!volume.mounted) "Not mounted" else if (volume.isPrimary) "Internal shared storage" else "Removable storage",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            val frac = volume.usedFraction
            if (frac != null && volume.totalBytes != null && volume.freeBytes != null) {
                StorageBar(frac)
                Spacer(Modifier.height(6.dp))
                Text(
                    "${Format.size(volume.usedBytes ?: 0)} used of ${Format.size(volume.totalBytes)}  •  ${Format.size(volume.freeBytes)} free",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    if (volume.mounted) "Capacity is not exposed by Android for this volume." else "Insert or mount the storage to see its capacity.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val intent = volume.grantIntent
            if (intent != null && !volume.isPrimary) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { grant.pick(intent) }) { Text("Grant access to this storage") }
            }
        }
    }
}

/** A granted folder with its access status and recovery actions. */
@Composable
fun RootRow(state: RootState, onOpen: () -> Unit, onRegrant: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val ok = state.status == RootStatus.OK
    FluentCard(modifier.fillMaxWidth(), onClick = if (ok) onOpen else null) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (ok) Icons.Filled.Folder else Icons.Filled.Warning, contentDescription = null,
                tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(state.root.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    friendlyPath(state.root.rootUri) .let { if (ok) it else when (state.status) {
                        RootStatus.ACCESS_REVOKED -> "Access was revoked. Grant it again to keep using this folder."
                        else -> "Unavailable: the storage may be disconnected or the folder was removed."
                    } },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                )
            }
            if (!ok) TextButton(onClick = onRegrant) { Text("Grant again") }
            ActionMenuButton(
                listOf(
                    MenuAction("Grant access again") { onRegrant() },
                    MenuAction("Remove from app", destructive = true) { onRemove() }
                )
            )
        }
    }
}

/** Explains Android's scoped storage in plain words (used where the user needs to understand why they must pick folders). */
@Composable
fun ScopedStorageNote(modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    FluentCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Why do I choose folders?", style = MaterialTheme.typography.titleSmall)
            Text(
                "Android protects your files: an app can only see folders you explicitly allow. File Explorer never asks for \"all files access\"; " +
                    "you pick folders (or an SD card / USB drive) and the grant is remembered.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (expanded) {
                Text(
                    "• Android 11+ does not allow granting the whole internal storage, the Download folder itself, or Android/data. " +
                        "You can still grant their sub-folders and folders like Documents, DCIM, Pictures, Music and Movies.\n" +
                        "• SD cards and USB drives can be granted from their root.\n" +
                        "• Each storage provider decides which operations it supports; unsupported actions are disabled with an explanation.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Learn more") }
        }
    }
}
