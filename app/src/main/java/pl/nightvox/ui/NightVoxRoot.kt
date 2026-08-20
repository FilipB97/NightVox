package pl.nightvox.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import pl.nightvox.ui.calibration.CalibrationScreen
import pl.nightvox.ui.calibration.CalibrationViewModel
import pl.nightvox.ui.clip.ClipDetailScreen
import pl.nightvox.ui.clip.ClipsScreen
import pl.nightvox.ui.clip.ClipsViewModel
import pl.nightvox.ui.home.HomeScreen
import pl.nightvox.ui.home.HomeViewModel
import pl.nightvox.ui.sessions.SessionDetailScreen
import pl.nightvox.ui.sessions.SessionsScreen
import pl.nightvox.ui.sessions.SessionsViewModel
import pl.nightvox.ui.settings.SettingsScreen
import pl.nightvox.ui.settings.SettingsViewModel

object Routes {
    const val HOME = "home"
    const val SESSIONS = "sessions"
    const val CLIPS = "clips"
    const val SETTINGS = "settings"
    const val CALIBRATION = "calibration"
    const val SESSION_DETAIL = "session/{sessionId}"
    const val CLIP_DETAIL = "clip/{clipId}"

    fun sessionDetail(id: String) = "session/$id"
    fun clipDetail(id: String) = "clip/$id"
}

/*
 * Przejścia między ekranami.
 *
 * Zakładki z dolnego paska są równorzędne — nie ma między nimi „w przód” ani „wstecz”, więc
 * domyślne wsuwanie całego ekranu z boku czyta się jak nawigacja w głąb i wygląda źle przy
 * nieruchomym pasku. Zakładki dostają samo przenikanie, a wejście w szczegóły (sesja, klip,
 * kalibracja) delikatny ruch poziomy, który niesie kierunek.
 *
 * Czasy są krótkie: to aplikacja włączana po ciemku tuż przed snem, animacje mają nie
 * przeszkadzać.
 */
private const val FADE_IN_MS = 160
private const val FADE_OUT_MS = 110
private const val SLIDE_MS = 240

private val tabEnter: EnterTransition = fadeIn(tween(FADE_IN_MS, easing = FastOutSlowInEasing))
private val tabExit: ExitTransition = fadeOut(tween(FADE_OUT_MS, easing = FastOutSlowInEasing))

private fun AnimatedContentTransitionScope<*>.detailEnter(): EnterTransition =
    slideInHorizontally(tween(SLIDE_MS, easing = FastOutSlowInEasing)) { width -> width / 5 } +
        fadeIn(tween(FADE_IN_MS))

private fun AnimatedContentTransitionScope<*>.detailPopExit(): ExitTransition =
    slideOutHorizontally(tween(SLIDE_MS, easing = FastOutSlowInEasing)) { width -> width / 5 } +
        fadeOut(tween(FADE_OUT_MS))

private data class TabItem(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem(Routes.HOME, "Noc", Icons.Filled.Mic),
    TabItem(Routes.SESSIONS, "Sesje", Icons.Filled.NightsStay),
    TabItem(Routes.CLIPS, "Klipy", Icons.Filled.GraphicEq),
    TabItem(Routes.SETTINGS, "Ustawienia", Icons.Filled.Settings),
)

@Composable
fun NightVoxRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in tabs.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = backStackEntry?.destination?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateToTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding),
            enterTransition = { tabEnter },
            exitTransition = { tabExit },
            popEnterTransition = { tabEnter },
            popExitTransition = { tabExit },
        ) {
            composable(Routes.HOME) {
                val vm: HomeViewModel = viewModel(factory = NightVoxViewModelFactory)
                HomeScreen(
                    viewModel = vm,
                    onOpenCalibration = { navController.navigate(Routes.CALIBRATION) },
                    onOpenSettings = { navController.navigateToTab(Routes.SETTINGS) },
                )
            }
            composable(Routes.SESSIONS) {
                val vm: SessionsViewModel = viewModel(factory = NightVoxViewModelFactory)
                SessionsScreen(
                    viewModel = vm,
                    onOpenSession = { navController.navigate(Routes.sessionDetail(it)) },
                )
            }
            composable(Routes.CLIPS) {
                val vm: ClipsViewModel = viewModel(factory = NightVoxViewModelFactory)
                ClipsScreen(
                    viewModel = vm,
                    onOpenClip = { navController.navigate(Routes.clipDetail(it)) },
                )
            }
            composable(Routes.SETTINGS) {
                val vm: SettingsViewModel = viewModel(factory = NightVoxViewModelFactory)
                SettingsScreen(
                    viewModel = vm,
                    onOpenCalibration = { navController.navigate(Routes.CALIBRATION) },
                )
            }
            composable(
                Routes.CALIBRATION,
                enterTransition = { detailEnter() },
                exitTransition = { tabExit },
                popEnterTransition = { tabEnter },
                popExitTransition = { detailPopExit() },
            ) {
                val vm: CalibrationViewModel = viewModel(factory = NightVoxViewModelFactory)
                CalibrationScreen(viewModel = vm, onBack = { navController.popBackStack() })
            }
            composable(
                Routes.SESSION_DETAIL,
                enterTransition = { detailEnter() },
                exitTransition = { tabExit },
                popEnterTransition = { tabEnter },
                popExitTransition = { detailPopExit() },
            ) { entry ->
                val sessionId = entry.arguments?.getString("sessionId").orEmpty()
                val vm: SessionsViewModel = viewModel(factory = NightVoxViewModelFactory)
                SessionDetailScreen(
                    sessionId = sessionId,
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    onOpenClip = { navController.navigate(Routes.clipDetail(it)) },
                )
            }
            composable(
                Routes.CLIP_DETAIL,
                enterTransition = { detailEnter() },
                exitTransition = { tabExit },
                popEnterTransition = { tabEnter },
                popExitTransition = { detailPopExit() },
            ) { entry ->
                val clipId = entry.arguments?.getString("clipId").orEmpty()
                val vm: ClipsViewModel = viewModel(factory = NightVoxViewModelFactory)
                ClipDetailScreen(
                    clipId = clipId,
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    // Podmiana, nie dokładanie: przy przeglądaniu stu klipów „wstecz" ma wracać
                    // do listy, a nie odtwarzać całą trasę klip po klipie.
                    onOpenClip = { next ->
                        navController.navigate(Routes.clipDetail(next)) {
                            popUpTo(Routes.CLIP_DETAIL) { inclusive = true }
                        }
                    },
                )
            }
        }
    }
}

private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
