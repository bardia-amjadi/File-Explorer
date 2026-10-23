package com.bardia.fileexplorer.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bardia.fileexplorer.app.AppContainer
import com.bardia.fileexplorer.core.Crumb
import com.bardia.fileexplorer.core.FileCategory
import com.bardia.fileexplorer.core.SortField
import com.bardia.fileexplorer.search.SearchCriteria
import com.bardia.fileexplorer.search.SearchMatcher
import com.bardia.fileexplorer.search.SearchResult
import com.bardia.fileexplorer.storage.WalkStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DatePreset(val label: String, val days: Int?) { ANY("Any time", null), TODAY("Today", 1), WEEK("Last 7 days", 7), MONTH("Last 30 days", 30), YEAR("Last year", 365), CUSTOM("Custom", null) }

data class SearchForm(
    val text: String = "",
    val extensions: String = "",
    val mime: String = "",
    val category: FileCategory? = null,
    val minMb: String = "",
    val maxMb: String = "",
    val datePreset: DatePreset = DatePreset.ANY,
    val customAfter: Long? = null,
    val customBefore: Long? = null,
    val includeHidden: Boolean = false,
    val sortField: SortField = SortField.NAME,
    val ascending: Boolean = true,
    /** Root-document URIs chosen as scope; empty = every granted folder. */
    val scopeUris: Set<String> = emptySet()
)

data class SearchUi(
    val form: SearchForm = SearchForm(),
    val running: Boolean = false,
    val ranOnce: Boolean = false,
    val results: List<SearchResult> = emptyList(),
    val scannedFolders: Int = 0,
    val skippedFolders: Int = 0,
    val scopeCount: Int = 0,
    val error: String? = null
)

/** Search state survives navigating to a result and back (scoped to the Search back-stack entry). */
class SearchViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(SearchUi(form = SearchForm(includeHidden = c.settings.value.searchIncludeHidden)))
    val ui: StateFlow<SearchUi> = _ui.asStateFlow()
    private var job: Job? = null

    fun edit(f: (SearchForm) -> SearchForm) = _ui.update { it.copy(form = f(it.form)) }

    fun criteria(form: SearchForm, now: Long = System.currentTimeMillis()): SearchCriteria {
        val after = when (form.datePreset) {
            DatePreset.ANY -> null
            DatePreset.CUSTOM -> form.customAfter
            else -> now - (form.datePreset.days ?: 0) * 86_400_000L
        }
        val before = if (form.datePreset == DatePreset.CUSTOM) form.customBefore?.plus(86_399_999L) else null
        return SearchCriteria(
            text = form.text.trim(),
            extensions = SearchMatcher.parseExtensions(form.extensions),
            mime = form.mime.trim(),
            category = form.category,
            minSize = form.minMb.toDoubleOrNull()?.let { (it * 1024 * 1024).toLong() },
            maxSize = form.maxMb.toDoubleOrNull()?.let { (it * 1024 * 1024).toLong() },
            modifiedAfter = after, modifiedBefore = before, includeHidden = form.includeHidden
        )
    }

    fun start(scopes: List<Crumb>) {
        val criteria = criteria(_ui.value.form)
        if (criteria.isEmpty) {
            _ui.update { it.copy(error = "Enter a name or choose at least one filter.") }
            return
        }
        if (scopes.isEmpty()) {
            _ui.update { it.copy(error = "No accessible folders to search. Grant access to a folder first.") }
            return
        }
        job?.cancel()
        val stats = WalkStats()
        _ui.update { it.copy(running = true, ranOnce = true, results = emptyList(), error = null, scopeCount = scopes.size, scannedFolders = 0, skippedFolders = 0) }
        job = viewModelScope.launch {
            val acc = ArrayList<SearchResult>()
            try {
                c.search.search(criteria, scopes, stats).collect { r ->
                    acc.add(r)
                    // Publish incrementally, but not on every hit.
                    if (acc.size <= 20 || acc.size % 25 == 0) {
                        _ui.update { it.copy(results = acc.toList(), scannedFolders = stats.scannedFolders.get(), skippedFolders = stats.skippedFolders.get()) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message ?: "Search failed") }
            } finally {
                _ui.update { it.copy(running = false, results = acc.toList(), scannedFolders = stats.scannedFolders.get(), skippedFolders = stats.skippedFolders.get()) }
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun clear() {
        job?.cancel()
        _ui.value = SearchUi(form = SearchForm(includeHidden = c.settings.value.searchIncludeHidden))
    }

    override fun onCleared() {
        job?.cancel()
    }
}
