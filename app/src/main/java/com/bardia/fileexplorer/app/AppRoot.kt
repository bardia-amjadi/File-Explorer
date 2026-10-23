@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.bardia.fileexplorer.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bardia.fileexplorer.core.FileCategory
import com.bardia.fileexplorer.core.StartDestination
import com.bardia.fileexplorer.ui.browse.BrowseScreen
import com.bardia.fileexplorer.ui.browse.BrowseViewModel
import com.bardia.fileexplorer.ui.common.EntryActions
import com.bardia.fileexplorer.ui.common.LocalNotify
import com.bardia.fileexplorer.ui.common.OperationsPanel
import com.bardia.fileexplorer.ui.home.HomeScreen
import com.bardia.fileexplorer.ui.lists.CategoriesScreen
import com.bardia.fileexplorer.ui.lists.CategoryScreen
import com.bardia.fileexplorer.ui.lists.FavoritesScreen
import com.bardia.fileexplorer.ui.lists.RecentScreen
import com.bardia.fileexplorer.ui.preview.ArchiveScreen
import com.bardia.fileexplorer.ui.preview.ViewerScreen
import com.bardia.fileexplorer.ui.search.SearchScreen
import com.bardia.fileexplorer.ui.search.SearchViewModel
import com.bardia.fileexplorer.ui.settings.SettingsScreen
import com.bardia.fileexplorer.ui.settings.StorageScreen
import com.bardia.fileexplorer.ui.tools.AnalysisScreen
import com.bardia.fileexplorer.ui.tools.ApkScreen
import com.bardia.fileexplorer.ui.tools.CompareScreen
import com.bardia.fileexplorer.ui.tools.DuplicatesScreen
import com.bardia.fileexplorer.ui.tools.LargeFilesScreen
import com.bardia.fileexplorer.ui.tools.ToolsScreen
import com.bardia.fileexplorer.ui.tools.TrashScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
inline fun <reified VM : ViewModel> containerViewModel(crossinline create: (AppContainer) -> VM): VM {
    val c = LocalContainer.current
    return viewModel(factory = viewModelFactory { initializer { create(c) } })
}

private data class Dest(val route: String, val label: String, val icon: ImageVector)

private val allDests = listOf(
    Dest(Routes.HOME, "Home", Icons.Filled.Home),
    Dest(Routes.BROWSE, "Browse", Icons.Filled.Folder),
    Dest(Routes.RECENT, "Recent", Icons.Filled.History),
    Dest(Routes.FAVORITES, "Favorites", Icons.Filled.Star),
    Dest(Routes.CATEGORIES, "Categories", Icons.Filled.Category),
    Dest(Routes.STORAGE, "Storage", Icons.Filled.SdStorage),
    Dest(Routes.TOOLS, "Tools", Icons.Filled.Build),
    Dest(Routes.SETTINGS, "Settings", Icons.Filled.Settings)
)
private val primaryRoutes = listOf(Routes.HOME, Routes.BROWSE, Routes.RECENT, Routes.FAVORITES)

private fun NavHostController.goTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val c = LocalContainer.current
    val start by produceState<String?>(null) {
        value = if (c.prefs.settings.first().startDestination == StartDestination.BROWSE) Routes.BROWSE else Routes.HOME
    }
    val startRoute = start ?: return

    val nav = rememberNavController()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val browseVm: BrowseViewModel = containerViewModel { BrowseViewModel(it) }
    val notify: (String) -> Unit = remember {
        { msg -> scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(msg, duration = SnackbarDuration.Short) } }
    }
    val actions = remember {
        EntryActions(
            container = c, scope = scope, context = ctx, notify = notify,
            navigate = { route -> nav.navigate(route) { launchSingleTop = true } },
            showFolder = { trail ->
                browseVm.navigate(trail)
                nav.goTopLevel(Routes.BROWSE)
            }
        )
    }
    val openBrowse: () -> Unit = { nav.goTopLevel(Routes.BROWSE) }

    // Operation results: a snackbar, with Undo when the operation can be reversed.
    LaunchedEffect(Unit) {
        c.ops.events.collect { ev ->
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                val res = snackbar.showSnackbar(
                    ev.message, actionLabel = if (ev.undoId != null) "Undo" else null,
                    duration = if (ev.undoId != null || ev.isError) SnackbarDuration.Long else SnackbarDuration.Short
                )
                if (res == SnackbarResult.ActionPerformed && ev.undoId != null) {
                    if (!c.undo.run(ev.undoId)) notify("This action can no longer be undone.")
                }
            }
        }
    }

    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val isTop = route in Routes.topLevel
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    var moreOpen by remember { mutableStateOf(false) }

    CompositionLocalProvider(LocalNotify provides notify) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (isTop && !wide) {
                    NavigationBar {
                        allDests.filter { it.route in primaryRoutes }.forEach { d ->
                            NavigationBarItem(
                                selected = route == d.route, onClick = { nav.goTopLevel(d.route) },
                                icon = { Icon(d.icon, d.label) }, label = { Text(d.label) }
                            )
                        }
                        NavigationBarItem(
                            selected = route != null && route !in primaryRoutes, onClick = { moreOpen = true },
                            icon = { Icon(Icons.Filled.MoreHoriz, "More") }, label = { Text("More") }
                        )
                    }
                }
            }
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (isTop && wide) {
                    NavigationRail {
                        allDests.forEach { d ->
                            NavigationRailItem(
                                selected = route == d.route, onClick = { nav.goTopLevel(d.route) },
                                icon = { Icon(d.icon, d.label) }, label = { Text(d.label) }
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxSize().then(if (isTop && !wide) Modifier else Modifier.navigationBarsPadding())) {
                    NavHost(nav, startDestination = startRoute, modifier = Modifier.fillMaxSize()) {
                        composable(Routes.HOME) {
                            HomeScreen(
                                browseVm, actions, onOpenBrowse = openBrowse,
                                onSearch = { nav.navigate(Routes.SEARCH) },
                                onCategory = { nav.navigate(Routes.category(it)) },
                                onRecent = { nav.goTopLevel(Routes.RECENT) },
                                onFavorites = { nav.goTopLevel(Routes.FAVORITES) }
                            )
                        }
                        composable(Routes.BROWSE) {
                            Box(Modifier.fillMaxSize().statusBarsPadding()) {
                                BrowseScreen(browseVm, actions, onSearch = { nav.navigate(Routes.SEARCH) }, onCompare = { nav.navigate(Routes.COMPARE) })
                            }
                        }
                        composable(Routes.RECENT) { RecentScreen(browseVm, actions, openBrowse) }
                        composable(Routes.FAVORITES) { FavoritesScreen(browseVm, actions, openBrowse) }
                        composable(Routes.CATEGORIES) { CategoriesScreen(browseVm, { nav.navigate(Routes.category(it)) }, openBrowse) }
                        composable(Routes.STORAGE) {
                            StorageScreen(
                                onOpenTrail = { browseVm.navigate(it); nav.goTopLevel(Routes.BROWSE) },
                                onTrash = { nav.navigate(Routes.TRASH) },
                                onBack = if (isTop) null else ({ nav.popBackStack() })
                            )
                        }
                        composable(Routes.TOOLS) { ToolsScreen({ nav.navigate(it) }) }
                        composable(Routes.SETTINGS) {
                            SettingsScreen(onStorage = { nav.goTopLevel(Routes.STORAGE) }, onTrash = { nav.navigate(Routes.TRASH) })
                        }
                        composable(Routes.SEARCH) {
                            val sv: SearchViewModel = containerViewModel { SearchViewModel(it) }
                            SearchScreen(sv, browseVm, actions, onBack = { nav.popBackStack() }, onOpenBrowse = openBrowse)
                        }
                        composable(Routes.COMPARE) { CompareScreen { nav.popBackStack() } }
                        composable(Routes.ANALYSIS) { AnalysisScreen { nav.popBackStack() } }
                        composable(Routes.LARGE_FILES) { LargeFilesScreen(actions) { nav.popBackStack() } }
                        composable(Routes.DUPLICATES) { DuplicatesScreen(actions) { nav.popBackStack() } }
                        composable(Routes.TRASH) { TrashScreen { nav.popBackStack() } }
                        composable(Routes.VIEWER, arguments = listOf(navArgument(Routes.ARG) { type = NavType.StringType })) { e ->
                            ViewerScreen(e.arguments?.getString(Routes.ARG).orEmpty(), actions) { nav.popBackStack() }
                        }
                        composable(Routes.ARCHIVE, arguments = listOf(navArgument(Routes.ARG) { type = NavType.StringType })) { e ->
                            ArchiveScreen(e.arguments?.getString(Routes.ARG).orEmpty(), actions) { nav.popBackStack() }
                        }
                        composable(Routes.APK, arguments = listOf(navArgument(Routes.ARG) { type = NavType.StringType })) { e ->
                            ApkScreen(e.arguments?.getString(Routes.ARG).orEmpty()) { nav.popBackStack() }
                        }
                        composable(Routes.CATEGORY, arguments = listOf(navArgument(Routes.ARG) { type = NavType.StringType })) { e ->
                            val cat = runCatching { FileCategory.valueOf(e.arguments?.getString(Routes.ARG).orEmpty()) }.getOrNull()
                            if (cat != null) CategoryScreen(cat, browseVm, actions, { nav.popBackStack() }, openBrowse)
                        }
                    }
                    OperationsPanel(Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                }
            }
        }
        actions.Dialogs()

        if (moreOpen) {
            ModalBottomSheet(onDismissRequest = { moreOpen = false }) {
                allDests.filter { it.route !in primaryRoutes }.forEach { d ->
                    ListItem(
                        headlineContent = { Text(d.label) },
                        leadingContent = { Icon(d.icon, null) },
                        modifier = Modifier.clickable { moreOpen = false; nav.goTopLevel(d.route) }
                    )
                }
            }
        }
    }
}
