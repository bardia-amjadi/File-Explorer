@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.bardia.fileexplorer.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bardia.fileexplorer.core.Crumb

data class MenuAction(
    val label: String,
    val icon: ImageVector? = null,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val onClick: () -> Unit
)

/** Subtle bordered surface used for dashboard cards: Fluent "card" with a hairline border and no heavy shadow. */
@Composable
fun FluentCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = MaterialTheme.shapes.large
    val colors = MaterialTheme.colorScheme
    if (onClick != null) {
        Surface(
            onClick = onClick, modifier = modifier, shape = shape, color = colors.surface,
            border = BorderStroke(1.dp, colors.outlineVariant), tonalElevation = 0.dp, shadowElevation = 0.dp, content = content
        )
    } else {
        Surface(
            modifier = modifier, shape = shape, color = colors.surface,
            border = BorderStroke(1.dp, colors.outlineVariant), tonalElevation = 0.dp, shadowElevation = 0.dp, content = content
        )
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun EmptyState(
    icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier,
    actionLabel: String? = null, onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun ErrorPanel(
    message: String, modifier: Modifier = Modifier,
    primaryLabel: String? = null, onPrimary: (() -> Unit)? = null,
    secondaryLabel: String? = null, onSecondary: (() -> Unit)? = null
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.Error, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (primaryLabel != null && onPrimary != null) Button(onClick = onPrimary) { Text(primaryLabel) }
            if (secondaryLabel != null && onSecondary != null) TextButton(onClick = onSecondary) { Text(secondaryLabel) }
        }
    }
}

@Composable
fun StorageBar(fraction: Float, modifier: Modifier = Modifier) {
    val color = if (fraction > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().height(8.dp).semantics { contentDescription = "Storage ${(fraction * 100).toInt()} percent used" },
        color = color, trackColor = MaterialTheme.colorScheme.surfaceVariant,
        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {}
    )
}

/** Clickable path segments, Windows-style: Root > Folder > Sub. Scrolls to keep the current folder visible. */
@Composable
fun Breadcrumbs(rootLabel: String, trail: List<Crumb>, onRoot: () -> Unit, onCrumb: (Int) -> Unit, modifier: Modifier = Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(trail.size) { state.scrollToItem(trail.size.coerceAtLeast(0)) }
    LazyRow(modifier = modifier, state = state, verticalAlignment = Alignment.CenterVertically) {
        item(key = "root") {
            Row(
                Modifier.clickable(onClick = onRoot).padding(horizontal = 8.dp, vertical = 8.dp).semantics { contentDescription = "Go to $rootLabel" },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Home, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp))
                Text(rootLabel, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
        itemsIndexed(trail, key = { i, c -> "$i:${c.uri}" }) { index, crumb ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
                Text(
                    crumb.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (index == trail.lastIndex) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { onCrumb(index) }.padding(horizontal = 6.dp, vertical = 8.dp)
                )
            }
        }
    }
}

/** Overflow button that opens a context menu with the given actions. */
@Composable
fun ActionMenuButton(actions: List<MenuAction>, modifier: Modifier = Modifier, contentDescription: String = "More options") {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = contentDescription) }
        ActionMenu(open, actions) { open = false }
    }
}

@Composable
fun ActionMenu(expanded: Boolean, actions: List<MenuAction>, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        actions.forEach { a ->
            DropdownMenuItem(
                text = { Text(a.label, color = if (a.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
                leadingIcon = if (a.icon != null) {
                    { Icon(a.icon, contentDescription = null, tint = if (a.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                } else null,
                enabled = a.enabled,
                onClick = { onDismiss(); a.onClick() }
            )
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String, text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit,
    destructive: Boolean = false, dismissLabel: String = "Cancel"
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (destructive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors()
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } }
    )
}

/** Single-line text prompt with inline validation. When [selectBaseName] is set the extension is left unselected (rename). */
@Composable
fun TextInputDialog(
    title: String, label: String, initial: String, confirmLabel: String,
    validate: (String) -> String?, onConfirm: (String) -> Unit, onDismiss: () -> Unit,
    selectBaseName: Boolean = false
) {
    var value by remember {
        val end = if (selectBaseName && initial.lastIndexOf('.') > 0) initial.lastIndexOf('.') else initial.length
        mutableStateOf(TextFieldValue(initial, TextRange(0, end)))
    }
    val error = validate(value.text)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value, onValueChange = { value = it }, label = { Text(label) }, singleLine = true,
                isError = error != null && value.text.isNotEmpty(),
                supportingText = { if (error != null && value.text.isNotEmpty()) Text(error) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
            )
        },
        confirmButton = { Button(onClick = { onConfirm(value.text.trim()) }, enabled = error == null) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun KeyValueRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(120.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
fun InfoBanner(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Surface(
        modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

val ScreenPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
val CheckIcon = Icons.Filled.Check
val CloseIcon = Icons.Filled.Close
