package com.bardia.fileexplorer.persistence

import com.bardia.fileexplorer.core.Crumb
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/** A folder the user granted through the system folder picker (persisted URI permission). */
data class GrantedRoot(val treeUri: String, val rootUri: String, val name: String)

data class FavoriteItem(
    val uri: String, val name: String, val isDirectory: Boolean, val mime: String?,
    /** For folders: the trail ending at the folder. For files: the trail of the containing folder. */
    val trail: List<Crumb>, val addedAt: Long
)

data class RecentItem(
    val uri: String, val name: String, val mime: String?, val size: Long, val modified: Long,
    val parentTrail: List<Crumb>, val openedAt: Long
)

data class SavedTab(val id: Long, val left: List<Crumb>, val right: List<Crumb>?, val dual: Boolean)

/** Persistent local metadata: granted roots, favorites, recent files/folders and open tabs. */
class MetadataStore(private val prefs: Prefs) {
    private object Name {
        const val ROOTS = "roots_json"
        const val FAVORITES = "favorites_json"
        const val RECENTS = "recents_json"
        const val RECENT_FOLDERS = "recent_folders_json"
        const val TABS = "tabs_json"
        const val ACTIVE_TAB = "active_tab"
    }

    // ------------------------------------------------------------ JSON helpers

    private fun trailToJson(t: List<Crumb>) = JSONArray().apply {
        t.forEach { put(JSONObject().put("u", it.uri).put("n", it.name)) }
    }

    private fun trailFromJson(a: JSONArray?): List<Crumb> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val u = o.optString("u")
            if (u.isEmpty()) null else Crumb(u, o.optString("n"))
        }
    }

    private fun <T : Any> parseArray(json: String?, mapper: (JSONObject) -> T?): List<T> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(mapper) }
        } catch (e: Exception) {
            emptyList() // A corrupted document must never crash the app; it is simply treated as empty.
        }
    }

    // ------------------------------------------------------------ roots

    val roots: Flow<List<GrantedRoot>> = prefs.stringFlow(Name.ROOTS).map { json ->
        parseArray(json) { o ->
            GrantedRoot(o.optString("t"), o.optString("r"), o.optString("n")).takeIf { it.treeUri.isNotEmpty() && it.rootUri.isNotEmpty() }
        }
    }

    private fun rootsToJson(list: List<GrantedRoot>) = JSONArray().apply {
        list.forEach { put(JSONObject().put("t", it.treeUri).put("r", it.rootUri).put("n", it.name)) }
    }.toString()

    suspend fun addRoot(root: GrantedRoot) = prefs.editString(Name.ROOTS) { old ->
        val cur = parseArray(old) { o -> GrantedRoot(o.optString("t"), o.optString("r"), o.optString("n")) }
        rootsToJson(cur.filterNot { it.treeUri == root.treeUri } + root)
    }

    suspend fun removeRoot(treeUri: String) = prefs.editString(Name.ROOTS) { old ->
        val cur = parseArray(old) { o -> GrantedRoot(o.optString("t"), o.optString("r"), o.optString("n")) }
        rootsToJson(cur.filterNot { it.treeUri == treeUri })
    }

    // ------------------------------------------------------------ favorites

    val favorites: Flow<List<FavoriteItem>> = prefs.stringFlow(Name.FAVORITES).map { parseFavorites(it) }

    private fun parseFavorites(json: String?) = parseArray(json) { o ->
        FavoriteItem(
            uri = o.optString("u"), name = o.optString("n"), isDirectory = o.optBoolean("d"),
            mime = o.optString("m").ifEmpty { null }, trail = trailFromJson(o.optJSONArray("t")), addedAt = o.optLong("a")
        ).takeIf { it.uri.isNotEmpty() }
    }

    private fun favoritesToJson(list: List<FavoriteItem>) = JSONArray().apply {
        list.forEach {
            put(JSONObject().put("u", it.uri).put("n", it.name).put("d", it.isDirectory).put("m", it.mime ?: "")
                .put("t", trailToJson(it.trail)).put("a", it.addedAt))
        }
    }.toString()

    suspend fun addFavorite(item: FavoriteItem) = prefs.editString(Name.FAVORITES) { old ->
        favoritesToJson(parseFavorites(old).filterNot { it.uri == item.uri } + item)
    }

    suspend fun removeFavorite(uri: String) = prefs.editString(Name.FAVORITES) { old ->
        favoritesToJson(parseFavorites(old).filterNot { it.uri == uri })
    }

    // ------------------------------------------------------------ recents

    val recents: Flow<List<RecentItem>> = prefs.stringFlow(Name.RECENTS).map { parseRecents(it) }

    private fun parseRecents(json: String?) = parseArray(json) { o ->
        RecentItem(
            uri = o.optString("u"), name = o.optString("n"), mime = o.optString("m").ifEmpty { null },
            size = o.optLong("s", -1), modified = o.optLong("l"), parentTrail = trailFromJson(o.optJSONArray("t")),
            openedAt = o.optLong("o")
        ).takeIf { it.uri.isNotEmpty() }
    }

    private fun recentsToJson(list: List<RecentItem>) = JSONArray().apply {
        list.forEach {
            put(JSONObject().put("u", it.uri).put("n", it.name).put("m", it.mime ?: "").put("s", it.size)
                .put("l", it.modified).put("t", trailToJson(it.parentTrail)).put("o", it.openedAt))
        }
    }.toString()

    suspend fun addRecent(item: RecentItem, limit: Int) = prefs.editString(Name.RECENTS) { old ->
        recentsToJson((listOf(item) + parseRecents(old).filterNot { it.uri == item.uri }).take(limit.coerceAtLeast(1)))
    }

    suspend fun removeRecent(uri: String) = prefs.editString(Name.RECENTS) { old ->
        recentsToJson(parseRecents(old).filterNot { it.uri == uri })
    }

    suspend fun trimRecents(limit: Int) = prefs.editString(Name.RECENTS) { old ->
        recentsToJson(parseRecents(old).take(limit.coerceAtLeast(1)))
    }

    suspend fun clearRecents() = prefs.editString(Name.RECENTS) { null }

    // ------------------------------------------------------------ recent folders

    val recentFolders: Flow<List<List<Crumb>>> = prefs.stringFlow(Name.RECENT_FOLDERS).map { parseFolders(it) }

    private fun parseFolders(json: String?): List<List<Crumb>> = parseArray(json) { o ->
        trailFromJson(o.optJSONArray("t")).takeIf { it.isNotEmpty() }
    }

    suspend fun addRecentFolder(trail: List<Crumb>) {
        if (trail.isEmpty()) return
        prefs.editString(Name.RECENT_FOLDERS) { old ->
            val list = (listOf(trail) + parseFolders(old).filterNot { it.last().uri == trail.last().uri }).take(12)
            JSONArray().apply { list.forEach { put(JSONObject().put("t", trailToJson(it))) } }.toString()
        }
    }

    suspend fun clearRecentFolders() = prefs.editString(Name.RECENT_FOLDERS) { null }

    // ------------------------------------------------------------ tabs

    val savedTabs: Flow<List<SavedTab>> = prefs.stringFlow(Name.TABS).map { json ->
        parseArray(json) { o ->
            val right = o.optJSONArray("r")
            SavedTab(
                id = o.optLong("i"), left = trailFromJson(o.optJSONArray("l")),
                right = if (right == null) null else trailFromJson(right), dual = o.optBoolean("d")
            )
        }
    }

    val activeTabId: Flow<Long?> = prefs.stringFlow(Name.ACTIVE_TAB).map { it?.toLongOrNull() }

    suspend fun saveTabs(tabs: List<SavedTab>, activeId: Long) {
        prefs.editString(Name.TABS) {
            JSONArray().apply {
                tabs.forEach { t ->
                    put(JSONObject().put("i", t.id).put("l", trailToJson(t.left)).put("d", t.dual).apply {
                        if (t.right != null) put("r", trailToJson(t.right))
                    })
                }
            }.toString()
        }
        prefs.editString(Name.ACTIVE_TAB) { activeId.toString() }
    }
}
