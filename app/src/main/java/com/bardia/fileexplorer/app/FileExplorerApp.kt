package com.bardia.fileexplorer.app

import android.app.Application
import androidx.compose.runtime.staticCompositionLocalOf
import com.bardia.fileexplorer.archive.ZipEngine
import com.bardia.fileexplorer.core.Settings
import com.bardia.fileexplorer.operations.OperationManager
import com.bardia.fileexplorer.operations.TrashManager
import com.bardia.fileexplorer.operations.UndoManager
import com.bardia.fileexplorer.persistence.MetadataStore
import com.bardia.fileexplorer.persistence.Prefs
import com.bardia.fileexplorer.search.SearchEngine
import com.bardia.fileexplorer.storage.FileSystem
import com.bardia.fileexplorer.storage.MediaStoreRepository
import com.bardia.fileexplorer.storage.StorageRepository
import com.bardia.fileexplorer.storage.TreeWalker
import com.bardia.fileexplorer.tools.ApkTools
import com.bardia.fileexplorer.tools.FileComparer
import com.bardia.fileexplorer.tools.FolderSizer
import com.bardia.fileexplorer.tools.StorageScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Hand-written service locator: the app is small enough that a DI framework would add more than it saves. */
class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val fs = FileSystem(app)
    val prefs = Prefs(app)
    val metadata = MetadataStore(prefs)
    val settings: StateFlow<Settings> = prefs.settings.stateIn(scope, SharingStarted.Eagerly, Settings())
    val storage = StorageRepository(app, fs, metadata)
    val media = MediaStoreRepository(app)
    val trash = TrashManager(app, fs)
    val undo = UndoManager()
    val ops = OperationManager(app, fs, trash, undo, scope)
    val walker = TreeWalker(fs)
    val search = SearchEngine(walker)
    val scanner = StorageScanner(walker, fs)
    val zip = ZipEngine(fs)
    val apk = ApkTools(app, fs)
    val sizer = FolderSizer(fs)
    val comparer = FileComparer(fs)

    /** Two files chosen for comparison (set by the browser selection, read by the Compare screen). */
    val compareSelection = kotlinx.coroutines.flow.MutableStateFlow<Pair<com.bardia.fileexplorer.core.FileEntry?, com.bardia.fileexplorer.core.FileEntry?>>(null to null)

    init {
        scope.launch(Dispatchers.IO) {
            trash.load()
            trash.purgeExpired(prefs.settings.first().trashRetentionDays)
        }
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        scope.launch { prefs.update(transform) }
    }
}

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

class FileExplorerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
