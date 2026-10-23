package com.bardia.fileexplorer.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bardia.fileexplorer.app.LocalContainer
import com.bardia.fileexplorer.operations.ConflictAction
import com.bardia.fileexplorer.operations.ConflictDecision
import com.bardia.fileexplorer.operations.OpStatus
import com.bardia.fileexplorer.operations.OperationState
import com.bardia.fileexplorer.util.Format

/** Progress surface for the operation queue: running/queued operations, cancel, and error details. */
@Composable
fun OperationsPanel(modifier: Modifier = Modifier) {
    val c = LocalContainer.current
    val ops by c.ops.operations.collectAsStateWithLifecycle()
    var details by remember { mutableStateOf<OperationState?>(null) }
    AnimatedVisibility(visible = ops.isNotEmpty(), modifier = modifier) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), tonalElevation = 2.dp
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ops.takeLast(4).forEach { op -> OperationRow(op, onCancel = { c.ops.cancel(op.id) }, onDismiss = { c.ops.dismiss(op.id) }, onDetails = { details = op }) }
            }
        }
    }
    details?.let { op ->
        AlertDialog(
            onDismissRequest = { details = null },
            title = { Text(op.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    op.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Spacer(Modifier.height(8.dp))
                    op.errors.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
                }
            },
            confirmButton = { TextButton(onClick = { c.ops.dismiss(op.id); details = null }) { Text("Dismiss") } },
            dismissButton = { TextButton(onClick = { details = null }) { Text("Close") } }
        )
    }
    ConflictDialogHost()
}

@Composable
private fun OperationRow(op: OperationState, onCancel: () -> Unit, onDismiss: () -> Unit, onDetails: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (op.status == OpStatus.DONE_WITH_ERRORS || op.status == OpStatus.FAILED) {
                Icon(Icons.Filled.ErrorOutline, contentDescription = "Problems", tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(op.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = when (op.status) {
                    OpStatus.QUEUED -> "Waiting in queue"
                    OpStatus.RUNNING -> listOf(op.current, if (op.bytesTotal > 0) "${Format.size(op.bytesDone)} of ${Format.size(op.bytesTotal)}" else if (op.bytesDone > 0) Format.size(op.bytesDone) else "")
                        .filter { it.isNotEmpty() }.joinToString("  •  ")
                    else -> op.message.orEmpty()
                }
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (op.isActive) {
                IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Cancel ${op.title}") }
            } else {
                if (op.errors.isNotEmpty()) TextButton(onClick = onDetails) { Text("Details") }
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Dismiss") }
            }
        }
        if (op.status == OpStatus.RUNNING) {
            val p = op.progress
            if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}

/** Asks what to do when a name already exists at the destination. Never overwrites without an explicit choice. */
@Composable
private fun ConflictDialogHost() {
    val c = LocalContainer.current
    val pending by c.ops.pendingConflict.collectAsStateWithLifecycle()
    val p = pending ?: return
    var applyAll by remember(p.opId, p.info.name) { mutableStateOf(false) }
    val info = p.info
    AlertDialog(
        onDismissRequest = { /* must choose explicitly */ },
        title = { Text("\"${info.name}\" already exists") },
        text = {
            Column {
                Text(p.opTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Text("Existing", style = MaterialTheme.typography.labelLarge)
                Text(
                    (if (info.existing.isDirectory) "Folder" else Format.size(info.existing.size)) + "  •  " + Format.date(info.existing.modified),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Text("Incoming", style = MaterialTheme.typography.labelLarge)
                Text(
                    (if (info.incomingIsDirectory) "Folder" else Format.size(info.incomingSize)) + "  •  " + Format.date(info.incomingModified),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Replace moves the existing item to the trash first, so it can be restored with Undo.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Checkbox(checked = applyAll, onCheckedChange = { applyAll = it })
                    Text("Apply to all remaining conflicts", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { c.ops.resolveConflict(ConflictDecision(ConflictAction.REPLACE, applyAll)) }) { Text("Replace") }
                TextButton(onClick = { c.ops.resolveConflict(ConflictDecision(ConflictAction.KEEP_BOTH, applyAll)) }) { Text("Keep both") }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { c.ops.resolveConflict(ConflictDecision(ConflictAction.SKIP, applyAll)) }) { Text("Skip") }
                TextButton(onClick = { c.ops.resolveConflict(ConflictDecision(ConflictAction.CANCEL, false)) }) { Text("Cancel") }
            }
        }
    )
}
