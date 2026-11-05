package de.tetnavigator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.lifecycle.HiltViewModel
import de.tetnavigator.R
import de.tetnavigator.core.designsystem.BottomNavBar
import de.tetnavigator.core.designsystem.NavTab
import de.tetnavigator.core.designsystem.TetIcons
import de.tetnavigator.core.designsystem.TetTheme
import de.tetnavigator.core.preferences.AppSettings
import de.tetnavigator.ui.downloads.DownloadBanner
import de.tetnavigator.ui.downloads.DownloadsScreen
import de.tetnavigator.ui.drive.DriveScreen
import de.tetnavigator.ui.importer.ImportScreen
import de.tetnavigator.ui.library.CountryScreen
import de.tetnavigator.ui.library.LibraryScreen
import de.tetnavigator.ui.library.SectionScreen
import de.tetnavigator.ui.logbook.JournalScreen
import de.tetnavigator.ui.logbook.LogbookScreen
import de.tetnavigator.ui.logistik.PoiScreen
import de.tetnavigator.ui.logistik.StagePlannerScreen
import de.tetnavigator.ui.permissions.PermissionsScreen
import de.tetnavigator.ui.settings.SettingsScreen
import de.tetnavigator.ui.setup.SetupScreen
import javax.inject.Inject

object Routes {
    const val PERMISSIONS = "permissions"
    const val SETUP = "setup"
    const val MAP = "map"
    const val TRACKS = "tracks"
    const val LOGBOOK = "logbook"
    const val IMPORT = "import"
    const val COUNTRY = "country/{cc}"
    const val SECTION = "section/{id}"
    const val SETTINGS = "settings"
    const val STAGES = "stages"
    const val JOURNAL = "journal"
    const val POIS = "pois"
    const val DOWNLOADS = "downloads"
    fun country(cc: String) = "country/$cc"
    fun section(id: Long) = "section/$id"
}

@HiltViewModel
class RootViewModel @Inject constructor(val events: AppEvents) : ViewModel()

@Composable
fun AppRoot(settings: AppSettings) {
    val nav = rememberNavController()
    val root: RootViewModel = hiltViewModel()
    val incoming by root.events.incoming.collectAsStateWithLifecycle()
    // Startziel nur einmal festlegen (spätere Einstellungsänderungen bauen den Graphen nicht neu).
    val start = remember {
        when {
            !settings.permissionsExplained -> Routes.PERMISSIONS
            !settings.setupDone -> Routes.SETUP
            else -> Routes.TRACKS
        }
    }
    // Geteilte GPX-Dateien → direkt zum Import (sobald die Einrichtung durch ist).
    LaunchedEffect(incoming, settings.setupDone, settings.permissionsExplained) {
        if (incoming.isNotEmpty() && settings.setupDone && settings.permissionsExplained) nav.navigate(Routes.IMPORT) { launchSingleTop = true }
    }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val tabIndex = when (route) { Routes.MAP -> 0; Routes.TRACKS -> 1; Routes.LOGBOOK -> 2; else -> -1 }

    // Aus der Download-Benachrichtigung geöffnet → Download-Anzeige
    val openDownloads by root.events.openDownloads.collectAsStateWithLifecycle()
    LaunchedEffect(openDownloads, settings.permissionsExplained) {
        if (openDownloads && settings.permissionsExplained) {
            root.events.openDownloads.value = false
            nav.navigate(if (settings.setupDone) Routes.DOWNLOADS else Routes.SETUP) { launchSingleTop = true }
        }
    }
    Column(Modifier.fillMaxSize().background(TetTheme.colors.bg)) {
        // Laufender/pausierter Download auf allen Screens sichtbar (außer Fahr-Screen und Einrichtung)
        if (route != null && route != Routes.MAP && route != Routes.SETUP && route != Routes.DOWNLOADS && route != Routes.PERMISSIONS) {
            Box(Modifier.statusBarsPadding()) { DownloadBanner(onOpen = { nav.navigate(Routes.DOWNLOADS) { launchSingleTop = true } }) }
        }
        Box(Modifier.weight(1f).let { if (route == Routes.MAP) it else it.statusBarsPadding() }) {
            NavHost(nav, startDestination = start) {
                composable(Routes.PERMISSIONS) { PermissionsScreen(onDone = { nav.go(if (settings.setupDone) Routes.TRACKS else Routes.SETUP, clear = true) }) }
                composable(Routes.SETUP) { SetupScreen(onDone = { nav.go(Routes.TRACKS, clear = true) }, onBack = if (settings.setupDone) ({ nav.popBackStack() }) else null) }
                composable(Routes.MAP) { DriveScreen(nav) }
                composable(Routes.TRACKS) { LibraryScreen(nav) }
                composable(Routes.LOGBOOK) { LogbookScreen(nav) }
                composable(Routes.IMPORT) { ImportScreen(nav) }
                composable(Routes.COUNTRY, listOf(navArgument("cc") { type = NavType.StringType })) { CountryScreen(nav, it.arguments?.getString("cc") ?: "") }
                composable(Routes.SECTION, listOf(navArgument("id") { type = NavType.LongType })) { SectionScreen(nav, it.arguments?.getLong("id") ?: 0L) }
                composable(Routes.SETTINGS) { SettingsScreen(nav) }
                composable(Routes.STAGES) { StagePlannerScreen(nav) }
                composable(Routes.JOURNAL) { JournalScreen(nav) }
                composable(Routes.POIS) { PoiScreen(nav) }
                composable(Routes.DOWNLOADS) { DownloadsScreen(nav) }
            }
        }
        if (tabIndex >= 0 && tabIndex != 0) {
            BottomNavBar(
                tabs = listOf(
                    NavTab(stringResource(R.string.tab_map), TetIcons.Map),
                    NavTab(stringResource(R.string.tab_tracks), TetIcons.Route),
                    NavTab(stringResource(R.string.tab_logbook), TetIcons.Book),
                ),
                selected = tabIndex,
                onSelect = { i -> nav.go(listOf(Routes.MAP, Routes.TRACKS, Routes.LOGBOOK)[i], tab = true) },
                modifier = Modifier.navigationBarsPadding(),
            )
        }
    }
}

fun NavHostController.go(route: String, clear: Boolean = false, tab: Boolean = false) = navigate(route) {
    launchSingleTop = true
    if (clear) popUpTo(0) { inclusive = true }
    if (tab) {
        popUpTo(Routes.TRACKS) { saveState = true }
        restoreState = true
    }
}
