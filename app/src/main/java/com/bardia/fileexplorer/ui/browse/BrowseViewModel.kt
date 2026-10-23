package com.bardia.fileexplorer.ui.browse

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bardia.fileexplorer.app.AppContainer
import com.bardia.fileexplorer.core.BrowseError
import com.bardia.fileexplorer.core.Crumb
import com.bardia.fileexplorer.core.FileEntry
import com.bardia.fileexplorer.core.FileSorter
import com.bardia.fileexplorer.core.Settings
import com.bardia.fileexplorer.persistence.SavedTab
import com.bardia.fileexplorer.storage.FileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** State of one browsing pane (a tab has one pane, or two in dual-pane mode). Empty [trail] = locations page. */
data class PaneState(
    val trail: List<Crumb> = emptyList(),
    val back: List<List<Crumb>> = emptyList(),
    val forward: List<List<Crumb>> = emptyList(),
    val dir: FileEntry? = null,
    val entries: List<FileEntry> = emptyList(),
    val visible: List<FileEntry> = emptyList(),
    val loading: Boolean = false,
    val error: BrowseError? = null,
    val selection: Set<String> = emptySet()
) {
    val canGoBack get() = back.isNotEmpty()
    val canGoForward get() = forward.isNotEmpty()
    val canCreate get() = dir?.canWriteInto == true
}

data class TabState(val id: Long, val left: PaneState, val right: PaneState? = null, val activePane: Int = 0) {
    val dual get() = right != null
    fun pane(i: Int): PaneState = if (i == 1 && right != null) right else left
    val active get() = pane(activePane)
    val title: String get() = active.trail.lastOrNull()?.name ?: "This device"
}

/**
 * Owns all browsing state: tabs, per-pane navigation history, directory loading, sorting, selection and
 * persistence of tabs. Lives at activity scope so other screens (Home, Favorites, Search, ...) can steer the
 * browser, e.g. "show in folder".
 */
class BrowseViewModel(private val c: AppContainer) : ViewModel() {
    private val _tabs = MutableStateFlow(listOf(TabState(1L, PaneState())))
    val tabs: StateFlow<List<TabState>> = _tabs.asStateFlow()
    private val _activeTabId = MutableStateFlow(1L)
    val activeTabId: StateFlow<Long> = _activeTabId.asStateFlow()

    private val _restored = MutableStateFlow(false)
    val restored: StateFlow<Boolean> = _restored.asStateFlow()

    private var nextTabId = 2L
    private var settings: Settings = c.settings.value
    private val loadJobs = HashMap<Pair<Long, Int>, Job>()
    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            restoreTabs()
            _restored.value = true
            c.settings.collect { s ->
                val changed = s.showHidden != settings.showHidden || s.sortField != settings.sortField ||
                    s.sortAscending != settings.sortAscending || s.foldersFirst != settings.foldersFirst || s.groupByType != settings.groupByType
                settings = s
                if (changed) recomputeAll()
            }
        }
        viewModelScope.launch { c.ops.changed.collect { refreshVisible() } }
    }

    // ------------------------------------------------------------ helpers

    private fun sortFilter(list: List<FileEntry>): List<FileEntry> {
        val s = settings
        return FileSorter.sort(FileSorter.visible(list, s.showHidden), s.sortField, s.sortAscending, s.foldersFirst, s.groupByType)
    }

    private fun updateTab(tabId: Long, f: (TabState) -> TabState) = _tabs.update { l -> l.map { if (it.id == tabId) f(it) else it } }

    private fun updatePane(tabId: Long, pane: Int, f: (PaneState) -> PaneState) = updateTab(tabId) { t ->
        if (pane == 1 && t.right != null) t.copy(right = f(t.right)) else t.copy(left = f(t.left))
    }

    private fun paneOf(tabId: Long, pane: Int): PaneState? = _tabs.value.firstOrNull { it.id == tabId }?.pane(pane)

    private fun recomputeAll() {
        viewModelScope.launch(Dispatchers.Default) {
            for (t in _tabs.value) for (i in 0..(if (t.dual) 1 else 0)) {
                val p = t.pane(i)
                val v = sortFilter(p.entries)
                updatePane(t.id, i) { cur -> if (cur.entries === p.entries) cur.copy(visible = v) else cur }
            }
        }
    }

    // ------------------------------------------------------------ navigation

    /** Navigates [pane] of the given tab (default: active tab) to [trail]. */
    fun navigate(trail: List<Crumb>, pane: Int = -1, tabId: Long = _activeTabId.value, pushHistory: Boolean = true) {
        val idx = if (pane >= 0) pane else (_tabs.value.firstOrNull { it.id == tabId }?.activePane ?: 0)
        updatePane(tabId, idx) { p ->
            p.copy(
                trail = trail,
                back = if (pushHistory && p.trail != trail) (p.back + listOf(p.trail)).takeLast(100) else p.back,
                forward = if (pushHistory) emptyList() else p.forward,
                selection = emptySet(), error = null
            )
        }
        load(tabId, idx)
        persist()
        if (trail.isNotEmpty()) viewModelScope.launch { c.metadata.addRecentFolder(trail) }
    }

    fun open(entry: FileEntry, pane: Int = -1) {
        val tab = activeTab()
        val idx = if (pane >= 0) pane else tab.activePane
        if (entry.isDirectory) navigate(tab.pane(idx).trail + Crumb(entry.uri, entry.name), idx)
    }

    fun goBack(pane: Int = -1) = step(pane, back = true)
    fun goForward(pane: Int = -1) = step(pane, back = false)

    private fun step(pane: Int, back: Boolean) {
        val tab = activeTab()
        val idx = if (pane >= 0) pane else tab.activePane
        val p = tab.pane(idx)
        val target = (if (back) p.back else p.forward).lastOrNull() ?: return
        updatePane(tab.id, idx) {
            if (back) it.copy(trail = target, back = it.back.dropLast(1), forward = it.forward + listOf(it.trail), selection = emptySet(), error = null)
            else it.copy(trail = target, forward = it.forward.dropLast(1), back = it.back + listOf(it.trail), selection = emptySet(), error = null)
        }
        load(tab.id, idx)
        persist()
    }

    fun goUp(pane: Int = -1) {
        val tab = activeTab()
        val idx = if (pane >= 0) pane else tab.activePane
        val p = tab.pane(idx)
        if (p.trail.isNotEmpty()) navigate(p.trail.dropLast(1), idx)
    }

    /** Opens the locations page ("This device"). */
    fun goRoot(pane: Int = -1) = navigate(emptyList(), pane)

    fun navigateToCrumb(index: Int, pane: Int = -1) {
        val tab = activeTab()
        val idx = if (pane >= 0) pane else tab.activePane
        navigate(tab.pane(idx).trail.take(index + 1), idx)
    }

    fun refresh(pane: Int = -1) {
        val tab = activeTab()
        load(tab.id, if (pane >= 0) pane else tab.activePane)
    }

    private fun refreshVisible() {
        val tab = activeTab()
        load(tab.id, 0)
        if (tab.dual) load(tab.id, 1)
    }

    fun activeTab(): TabState = _tabs.value.first { it.id == _activeTabId.value }

    private fun load(tabId: Long, pane: Int) {
        val key = tabId to pane
        loadJobs[key]?.cancel()
        val p = paneOf(tabId, pane) ?: return
        val trail = p.trail
        if (trail.isEmpty()) {
            updatePane(tabId, pane) { it.copy(entries = emptyList(), visible = emptyList(), loading = false, error = null, dir = null) }
            return
        }
        loadJobs[key] = viewModelScope.launch {
            updatePane(tabId, pane) { if (it.trail == trail) it.copy(loading = true, error = null) else it }
            try {
                val dirUri = Uri.parse(trail.last().uri)
                val (list, dir) = withContext(Dispatchers.IO) {
                    val dir = c.fs.stat(dirUri)
                    val list = c.fs.list(dirUri, onFirstChunk = { chunk ->
                        // Show the first rows immediately while the rest of a large folder is still being read.
                        val v = sortFilter(chunk)
                        updatePane(tabId, pane) { cur -> if (cur.trail == trail && cur.loading) cur.copy(entries = chunk, visible = v, dir = dir ?: cur.dir) else cur }
                    })
                    list.map { it } to dir
                }
                val visible = withContext(Dispatchers.Default) { sortFilter(list) }
                updatePane(tabId, pane) { cur ->
                    if (cur.trail != trail) cur else {
                        val uris = list.asSequence().map { it.uri }.toHashSet()
                        cur.copy(entries = list, visible = visible, dir = dir, loading = false, error = null, selection = cur.selection.filter { it in uris }.toSet())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                updatePane(tabId, pane) { cur ->
                    if (cur.trail != trail) cur else cur.copy(entries = emptyList(), visible = emptyList(), loading = false, error = FileSystem.classify(e))
                }
            }
        }
    }

    // ------------------------------------------------------------ selection

    fun toggleSelect(uri: String, pane: Int = -1) {
        val tab = activeTab()
        val idx = if (pane >= 0) pane else tab.activePane
        updatePane(tab.id, idx) { it.copy(selection = if (uri in it.selection) it.selection - uri else it.selection + uri) }
    }

    fun selectAll(pane: Int = -1) {
        val tab = activeTab()
        val idx = if (pane >= 0) pane else tab.activePane
        updatePane(tab.id, idx) { it.copy(selection = it.visible.map { e -> e.uri }.toSet()) }
    }

    fun clearSelection(pane: Int = -1) {
        val tab = activeTab()
        val idx = if (pane >= 0) pane else tab.activePane
        updatePane(tab.id, idx) { it.copy(selection = emptySet()) }
    }

    // ------------------------------------------------------------ tabs & dual pane

    fun newTab(trail: List<Crumb> = emptyList()) {
        val id = nextTabId++
        _tabs.update { it + TabState(id, PaneState()) }
        _activeTabId.value = id
        navigate(trail, 0, id, pushHistory = false)
    }

    fun closeTab(id: Long) {
        val list = _tabs.value
        if (list.size <= 1) {
            // The last tab is reset instead of closed so there is always somewhere to browse.
            navigate(emptyList(), 0, id)
            return
        }
        val idx = list.indexOfFirst { it.id == id }
        loadJobs.keys.filter { it.first == id }.forEach { loadJobs.remove(it)?.cancel() }
        _tabs.value = list.filterNot { it.id == id }
        if (_activeTabId.value == id) _activeTabId.value = _tabs.value[(idx - 1).coerceAtLeast(0)].id
        persist()
    }

    fun switchTab(id: Long) {
        _activeTabId.value = id
        persist()
    }

    fun setDual(enabled: Boolean) {
        val tab = activeTab()
        if (enabled == tab.dual) return
        if (enabled) {
            updateTab(tab.id) { it.copy(right = PaneState(trail = it.left.trail, dir = it.left.dir, entries = it.left.entries, visible = it.left.visible), activePane = 0) }
            load(tab.id, 1)
        } else {
            loadJobs.remove(tab.id to 1)?.cancel()
            updateTab(tab.id) { it.copy(right = null, activePane = 0) }
        }
        persist()
    }

    fun setActivePane(i: Int) = updateTab(_activeTabId.value) { if (it.dual) it.copy(activePane = i) else it }

    // ------------------------------------------------------------ persistence

    private fun persist() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            kotlinx.coroutines.delay(400)
            val saved = _tabs.value.map { SavedTab(it.id, it.left.trail, it.right?.trail, it.dual) }
            c.metadata.saveTabs(saved, _activeTabId.value)
        }
    }

    /** Restores tabs, keeping only locations whose access is still valid; otherwise falls back to the locations page. */
    private suspend fun restoreTabs() {
        val saved = c.metadata.savedTabs.first()
        if (saved.isEmpty()) return
        val valid = withContext(Dispatchers.IO) {
            saved.map { t ->
                fun ok(trail: List<Crumb>?): List<Crumb> =
                    if (trail.isNullOrEmpty() || c.fs.stat(Uri.parse(trail.last().uri)) == null) emptyList() else trail
                t.copy(left = ok(t.left), right = t.right?.let { ok(it) })
            }
        }
        val tabs = valid.map { t -> TabState(t.id, PaneState(trail = t.left), if (t.dual) PaneState(trail = t.right ?: emptyList()) else null) }
        if (tabs.isEmpty()) return
        nextTabId = (tabs.maxOf { it.id }) + 1
        _tabs.value = tabs
        val active = c.metadata.activeTabId.first()
        _activeTabId.value = if (tabs.any { it.id == active }) active!! else tabs.first().id
        for (t in tabs) {
            load(t.id, 0)
            if (t.dual) load(t.id, 1)
        }
    }
}
