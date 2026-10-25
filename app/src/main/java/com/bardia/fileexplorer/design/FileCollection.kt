@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.bardia.fileexplorer.design

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.bardia.fileexplorer.core.FileCategory
import com.bardia.fileexplorer.core.FileEntry
import com.bardia.fileexplorer.core.FileTypes
import com.bardia.fileexplorer.core.IconSize
import com.bardia.fileexplorer.core.SortField
import com.bardia.fileexplorer.core.ViewMode
import com.bardia.fileexplorer.util.Format

private fun glyphFor(entry: FileEntry, dark: Boolean): Pair<ImageVector, Color> = when {
    entry.isDirectory -> Icons.Filled.Folder to (if (dark) Fluent.FolderYellowDark else Fluent.FolderYellow)
    entry.extension == "pdf" -> Icons.Filled.Description to Color(0xFFD13438)
    else -> when (entry.category) {
        FileCategory.IMAGES -> Icons.Filled.Image to Color(0xFF8764B8)
        FileCategory.VIDEOS -> Icons.Filled.Videocam to Color(0xFFE3008C)
        FileCategory.AUDIO -> Icons.Filled.Audiotrack to Color(0xFFEA7C1B)
        FileCategory.DOCUMENTS -> Icons.Filled.Description to Color(0xFF2B88D8)
        FileCategory.ARCHIVES -> Icons.Filled.FolderZip to Color(0xFFA67C52)
        FileCategory.APKS -> Icons.Filled.Android to Color(0xFF2E9E5B)
        FileCategory.OTHER -> Icons.Filled.Description to Color(0xFF7A7A7A)
    }
}

/** File type icon; image files additionally show a real thumbnail decoded by Coil from the document URI. */
@Composable
fun FileGlyph(entry: FileEntry, size: Dp, modifier: Modifier = Modifier, thumbnails: Boolean = true) {
    val dark = MaterialTheme.colorScheme.background.red < 0.5f
    val (icon, tint) = glyphFor(entry, dark)
    val showThumb = thumbnails && !entry.isDirectory && entry.category == FileCategory.IMAGES &&
        entry.extension != "svg" && (entry.size < 0 || entry.size < 60L * 1024 * 1024)
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.fillMaxSize(0.82f))
        if (showThumb) {
            val ctx = LocalContext.current
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(Uri.parse(entry.uri)).size(256).crossfade(true).build(),
                contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp))
            )
        }
    }
}

private sealed interface Row2 {
    data class Header(val title: String) : Row2
    data class Item(val entry: FileEntry) : Row2
}

private fun toRows(entries: List<FileEntry>, grouped: Boolean): List<Row2> {
    if (!grouped) return entries.map { Row2.Item(it) }
    val out = ArrayList<Row2>(entries.size + 8)
    var last: String? = null
    for (e in entries) {
        val g = FileTypes.groupLabel(e)
        if (g != last) {
            out.add(Row2.Header(g))
            last = g
        }
        out.add(Row2.Item(e))
    }
    return out
}

/**
 * One collection component for every file list in the app (browser, search results, recents, favorites,
 * categories). Supports list / grid / detailed modes, selection visuals, per-item context menus, grouping and
 * "end reached" callbacks for incremental loading.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileCollection(
    entries: List<FileEntry>,
    viewMode: ViewMode,
    iconSize: IconSize,
    selected: Set<String>,
    selectionMode: Boolean,
    onClick: (FileEntry) -> Unit,
    onLongClick: (FileEntry) -> Unit,
    modifier: Modifier = Modifier,
    menuFor: ((FileEntry) -> List<MenuAction>)? = null,
    subtitle: ((FileEntry) -> String)? = null,
    favorites: Set<String> = emptySet(),
    groupByType: Boolean = false,
    sortField: SortField? = null,
    ascending: Boolean = true,
    onSortHeader: ((SortField) -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onEndReached: (() -> Unit)? = null
) {
    val rows = remember(entries, groupByType) { toRows(entries, groupByType) }
    when (viewMode) {
        ViewMode.GRID -> {
            val state = rememberLazyGridState()
            EndReached(state, rows.size, onEndReached)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(iconSize.gridDp.dp + 28.dp), state = state, modifier = modifier,
                contentPadding = PaddingValues(
                    start = 8.dp + contentPadding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                    end = 8.dp, top = 8.dp + contentPadding.calculateTopPadding(), bottom = 8.dp + contentPadding.calculateBottomPadding()
                ),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                gridItems(
                    rows, key = { if (it is Row2.Header) "h:${it.title}" else (it as Row2.Item).entry.uri },
                    span = { if (it is Row2.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                    contentType = { if (it is Row2.Header) 0 else 1 }
                ) { row ->
                    when (row) {
                        is Row2.Header -> GroupHeader(row.title)
                        is Row2.Item -> GridCell(row.entry, iconSize, row.entry.uri in selected, selectionMode, row.entry.uri in favorites, onClick, onLongClick, menuFor)
                    }
                }
            }
        }
        ViewMode.LIST, ViewMode.DETAILS -> {
            val details = viewMode == ViewMode.DETAILS
            Column(modifier) {
                if (details && sortField != null) DetailsHeader(sortField, ascending, onSortHeader)
                val state = rememberLazyListState()
                EndReached(state, rows.size, onEndReached)
                LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
                    items(
                        rows, key = { if (it is Row2.Header) "h:${it.title}" else (it as Row2.Item).entry.uri },
                        contentType = { if (it is Row2.Header) 0 else 1 }
                    ) { row ->
                        when (row) {
                            is Row2.Header -> GroupHeader(row.title)
                            is Row2.Item -> if (details) DetailsRow(
                                row.entry, iconSize, row.entry.uri in selected, selectionMode, row.entry.uri in favorites, onClick, onLongClick, menuFor
                            ) else ListRow(
                                row.entry, iconSize, row.entry.uri in selected, selectionMode, row.entry.uri in favorites, subtitle, onClick, onLongClick, menuFor
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EndReached(state: LazyListState, total: Int, callback: (() -> Unit)?) {
    if (callback == null) return
    LaunchedEffect(state, total) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { if (it >= total - 10) callback() }
    }
}

@Composable
private fun EndReached(state: LazyGridState, total: Int, callback: (() -> Unit)?) {
    if (callback == null) return
    LaunchedEffect(state, total) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { if (it >= total - 10) callback() }
    }
}

@Composable
private fun GroupHeader(title: String) {
    Text(
        title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun SelectionMark(selected: Boolean) {
    Icon(
        if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, contentDescription = null,
        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, modifier = Modifier.size(22.dp)
    )
}

private fun describe(e: FileEntry) = buildString {
    append(if (e.isDirectory) "Folder " else "File ")
    append(e.name)
    if (!e.isDirectory && e.size >= 0) append(", ").append(Format.size(e.size))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListRow(
    e: FileEntry, iconSize: IconSize, isSelected: Boolean, selectionMode: Boolean, favorite: Boolean,
    subtitle: ((FileEntry) -> String)?, onClick: (FileEntry) -> Unit, onLongClick: (FileEntry) -> Unit,
    menuFor: ((FileEntry) -> List<MenuAction>)?
) {
    val bg = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 1.dp).clip(MaterialTheme.shapes.medium).background(bg)
            .combinedClickable(onClick = { onClick(e) }, onLongClick = { onLongClick(e) })
            .semantics(mergeDescendants = true) { selected = isSelected; contentDescription = describe(e) }
            .padding(horizontal = 8.dp, vertical = 6.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            SelectionMark(isSelected)
            Spacer(Modifier.width(10.dp))
        }
        FileGlyph(e, iconSize.listDp.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = subtitle?.invoke(e) ?: defaultSubtitle(e)
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (favorite) Icon(Icons.Filled.Star, contentDescription = "Favorite", tint = Fluent.FolderYellow, modifier = Modifier.size(16.dp))
        if (menuFor != null && !selectionMode) ActionMenuButton(menuFor(e), contentDescription = "Options for ${e.name}")
    }
}

private fun defaultSubtitle(e: FileEntry): String {
    val date = Format.shortDate(e.modified)
    return if (e.isDirectory) (if (e.modified > 0) date else "") else listOf(Format.size(e.size), date).filter { it != "—" }.joinToString("  •  ")
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridCell(
    e: FileEntry, iconSize: IconSize, isSelected: Boolean, selectionMode: Boolean, favorite: Boolean,
    onClick: (FileEntry) -> Unit, onLongClick: (FileEntry) -> Unit, menuFor: ((FileEntry) -> List<MenuAction>)?
) {
    val bg = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Box(
        Modifier.clip(MaterialTheme.shapes.medium).background(bg)
            .combinedClickable(onClick = { onClick(e) }, onLongClick = { onLongClick(e) })
            .semantics(mergeDescendants = true) { selected = isSelected; contentDescription = describe(e) }
    ) {
        Column(Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            FileGlyph(e, iconSize.gridDp.dp)
            Spacer(Modifier.height(6.dp))
            Text(e.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
        if (selectionMode) Box(Modifier.align(Alignment.TopStart).padding(6.dp)) { SelectionMark(isSelected) }
        if (favorite) Icon(Icons.Filled.Star, contentDescription = "Favorite", tint = Fluent.FolderYellow, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(14.dp))
        if (menuFor != null && !selectionMode) {
            Box(Modifier.align(Alignment.BottomEnd)) { ActionMenuButton(menuFor(e), Modifier.size(32.dp), "Options for ${e.name}") }
        }
    }
}

@Composable
private fun DetailsHeader(sortField: SortField, ascending: Boolean, onSort: ((SortField) -> Unit)?) {
    BoxWithConstraints(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        val cols = DetailColumns.forWidth(maxWidth)
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            HeaderCell("Name", SortField.NAME, sortField, ascending, onSort, Modifier.weight(1f))
            if (cols.showDate) HeaderCell("Date modified", SortField.MODIFIED, sortField, ascending, onSort, Modifier.width(cols.dateWidth))
            if (cols.showType) HeaderCell("Type", SortField.TYPE, sortField, ascending, onSort, Modifier.width(cols.typeWidth))
            HeaderCell("Size", SortField.SIZE, sortField, ascending, onSort, Modifier.width(cols.sizeWidth))
            Spacer(Modifier.width(40.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeaderCell(label: String, field: SortField, current: SortField, ascending: Boolean, onSort: ((SortField) -> Unit)?, modifier: Modifier) {
    Row(
        modifier.clip(MaterialTheme.shapes.small).combinedClickable(enabled = onSort != null, onClick = { onSort?.invoke(field) }).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (field == current) {
            Icon(
                if (ascending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                contentDescription = if (ascending) "Ascending" else "Descending", modifier = Modifier.size(14.dp)
            )
        }
    }
}

/** Columns shown by the detailed view depend on available width so it stays readable on phones. */
private data class DetailColumns(val showDate: Boolean, val showType: Boolean, val dateWidth: Dp, val typeWidth: Dp, val sizeWidth: Dp) {
    companion object {
        fun forWidth(w: Dp) = DetailColumns(
            showDate = w >= 380.dp, showType = w >= 640.dp,
            dateWidth = if (w >= 640.dp) 160.dp else 132.dp, typeWidth = 150.dp, sizeWidth = 76.dp
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailsRow(
    e: FileEntry, iconSize: IconSize, isSelected: Boolean, selectionMode: Boolean, favorite: Boolean,
    onClick: (FileEntry) -> Unit, onLongClick: (FileEntry) -> Unit, menuFor: ((FileEntry) -> List<MenuAction>)?
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cols = DetailColumns.forWidth(maxWidth)
        val bg = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(MaterialTheme.shapes.small).background(bg)
                .combinedClickable(onClick = { onClick(e) }, onLongClick = { onLongClick(e) })
                .semantics(mergeDescendants = true) { selected = isSelected; contentDescription = describe(e) }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                SelectionMark(isSelected)
                Spacer(Modifier.width(8.dp))
            }
            FileGlyph(e, (iconSize.listDp - 8).dp)
            Spacer(Modifier.width(10.dp))
            Text(e.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (favorite) Icon(Icons.Filled.Star, contentDescription = "Favorite", tint = Fluent.FolderYellow, modifier = Modifier.size(14.dp))
            if (cols.showDate) Text(Format.date(e.modified), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.width(cols.dateWidth))
            if (cols.showType) Text(if (e.isLocal) unixPerms(e) + "  " + FileTypes.typeLabel(e) else FileTypes.typeLabel(e), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(cols.typeWidth))
            Text(if (e.isDirectory) "" else Format.size(e.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.width(cols.sizeWidth))
            if (menuFor != null && !selectionMode) ActionMenuButton(menuFor(e), Modifier.size(40.dp), "Options for ${e.name}") else Spacer(Modifier.width(40.dp))
        }
    }
}

/** ls -l style flags (d/-, then r w x) as the app itself sees them; only meaningful for real-path entries. */
internal fun unixPerms(e: FileEntry): String {
    val f = java.io.File(android.net.Uri.parse(e.uri).path ?: return "----")
    return (if (e.isDirectory) "d" else "-") + (if (f.canRead()) "r" else "-") + (if (f.canWrite()) "w" else "-") + (if (f.canExecute()) "x" else "-")
}
