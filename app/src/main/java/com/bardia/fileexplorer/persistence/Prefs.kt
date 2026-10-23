package com.bardia.fileexplorer.persistence

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bardia.fileexplorer.core.IconSize
import com.bardia.fileexplorer.core.Settings
import com.bardia.fileexplorer.core.SortField
import com.bardia.fileexplorer.core.StartDestination
import com.bardia.fileexplorer.core.ThemeMode
import com.bardia.fileexplorer.core.ViewMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "file_explorer")

/**
 * DataStore-backed persistence for user settings plus small JSON documents (favorites, recents, tabs, granted
 * roots). It stores *metadata only*; the file system itself is always read from the real storage providers.
 */
class Prefs(context: Context) {
    private val ds = context.applicationContext.dataStore

    private object K {
        val theme = stringPreferencesKey("theme")
        val viewMode = stringPreferencesKey("view_mode")
        val iconSize = stringPreferencesKey("icon_size")
        val sortField = stringPreferencesKey("sort_field")
        val sortAsc = booleanPreferencesKey("sort_asc")
        val foldersFirst = booleanPreferencesKey("folders_first")
        val groupByType = booleanPreferencesKey("group_by_type")
        val showHidden = booleanPreferencesKey("show_hidden")
        val start = stringPreferencesKey("start_destination")
        val searchHidden = booleanPreferencesKey("search_hidden")
        val recentLimit = intPreferencesKey("recent_limit")
        val trashDays = intPreferencesKey("trash_days")
    }

    private inline fun <reified E : Enum<E>> Preferences.enumOf(key: Preferences.Key<String>, default: E): E =
        this[key]?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    private fun read(p: Preferences): Settings {
        val d = Settings()
        return Settings(
            theme = p.enumOf(K.theme, d.theme),
            viewMode = p.enumOf(K.viewMode, d.viewMode),
            iconSize = p.enumOf(K.iconSize, d.iconSize),
            sortField = p.enumOf(K.sortField, d.sortField),
            sortAscending = p[K.sortAsc] ?: d.sortAscending,
            foldersFirst = p[K.foldersFirst] ?: d.foldersFirst,
            groupByType = p[K.groupByType] ?: d.groupByType,
            showHidden = p[K.showHidden] ?: d.showHidden,
            startDestination = p.enumOf(K.start, d.startDestination),
            searchIncludeHidden = p[K.searchHidden] ?: d.searchIncludeHidden,
            recentLimit = p[K.recentLimit] ?: d.recentLimit,
            trashRetentionDays = p[K.trashDays] ?: d.trashRetentionDays
        )
    }

    val settings: Flow<Settings> = ds.data.map { read(it) }.distinctUntilChanged()

    /** Applies [transform] to the current settings atomically and persists the result. */
    suspend fun update(transform: (Settings) -> Settings) {
        ds.edit { p ->
            val s = transform(read(p))
            p[K.theme] = s.theme.name
            p[K.viewMode] = s.viewMode.name
            p[K.iconSize] = s.iconSize.name
            p[K.sortField] = s.sortField.name
            p[K.sortAsc] = s.sortAscending
            p[K.foldersFirst] = s.foldersFirst
            p[K.groupByType] = s.groupByType
            p[K.showHidden] = s.showHidden
            p[K.start] = s.startDestination.name
            p[K.searchHidden] = s.searchIncludeHidden
            p[K.recentLimit] = s.recentLimit
            p[K.trashDays] = s.trashRetentionDays
        }
    }

    fun stringFlow(name: String): Flow<String?> {
        val key = stringPreferencesKey(name)
        return ds.data.map { it[key] }.distinctUntilChanged()
    }

    suspend fun editString(name: String, transform: (String?) -> String?) {
        val key = stringPreferencesKey(name)
        ds.edit { p ->
            val next = transform(p[key])
            if (next == null) p.remove(key) else p[key] = next
        }
    }
}
