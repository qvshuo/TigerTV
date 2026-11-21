package art.anjing.tigertv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.compose.rememberNavController
import art.anjing.tigertv.ui.detail.DetailScreen
import art.anjing.tigertv.ui.home.HomeScreen
import art.anjing.tigertv.ui.home.SearchScreen
import art.anjing.tigertv.ui.settings.SettingsScreen
import art.anjing.tigertv.ui.player.PlayerScreen
import art.anjing.tigertv.ui.results.ResultsScreen
import art.anjing.tigertv.ui.viewmodel.TigerTVViewModel
import art.anjing.tigertv.ui.viewmodel.TigerTVViewModelFactory

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Search : Screen("search")
    data object Settings : Screen("settings")
    data object Results : Screen("results")
    data object Episodes : Screen("episodes")
    data object Player : Screen("player")
}

@Composable
fun TigerTVApp(
    navController: NavHostController = rememberNavController()
) {
    // Create the network service only when a new ViewModel is needed, not on Activity recreation.
    val appContext = LocalContext.current.applicationContext
    val viewModel: TigerTVViewModel = viewModel(
        factory = remember(appContext) { TigerTVViewModelFactory(appContext) }
    )

    TigerTVNavHost(viewModel, navController)
}

@Composable
internal fun TigerTVNavHost(viewModel: TigerTVViewModel, navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
    ) {
        composable(Screen.Home.route) {
            HomeScreen(viewModel,
                onSearch = { navController.navigate(Screen.Search.route) },
                onSettings = { navController.navigate(Screen.Settings.route) },
                onResults = { navController.navigate(Screen.Results.route) },
                onRecent = {
                    viewModel.openRecentPlayback(it)
                    navController.navigate(Screen.Episodes.route)
                })
        }
        composable(Screen.Search.route) {
            SearchScreen(viewModel, onResults = { navController.navigate(Screen.Results.route) })
        }
        // This destination covers the viewport and keeps any current player session alive underneath.
        dialog(Screen.Settings.route, dialogProperties = DialogProperties(usePlatformDefaultWidth = false)) {
            SettingsScreen()
        }
        composable(Screen.Results.route) {
            ResultsScreen(
                viewModel = viewModel,
                onSearch = {
                    if (!viewModel.isSearching) {
                        viewModel.clearResults()
                        navController.popBackStack()
                        if (navController.currentDestination?.route != Screen.Search.route) {
                            navController.navigate(Screen.Search.route)
                        }
                    }
                },
                onBack = {
                    viewModel.clearResults()
                    navController.popBackStack()
                },
                onSelectResult = { result ->
                    viewModel.selectResult(result)
                    navController.navigate(Screen.Episodes.route)
                }
            )
        }
        composable(Screen.Episodes.route) {
            val detailReady = !viewModel.isFetching && viewModel.fetchResponse != null
            LaunchedEffect(detailReady) {
                if (detailReady) viewModel.takeExactRecentEpisode()?.let { index ->
                    viewModel.selectEpisode(index)
                    navController.navigate(Screen.Player.route) {
                        popUpTo(Screen.Episodes.route) { inclusive = true }
                    }
                }
            }
            DetailScreen(
                viewModel = viewModel,
                onBack = {
                    viewModel.leaveDetail()
                    navController.popBackStack()
                },
                onPlay = { index ->
                    viewModel.selectEpisode(index)
                    navController.navigate(Screen.Player.route)
                }
            )
        }
        composable(Screen.Player.route) {
            val directResume = remember { navController.previousBackStackEntry?.destination?.route == Screen.Home.route }
            PlayerScreen(
                viewModel = viewModel,
                onSettings = { navController.navigate(Screen.Settings.route) },
                onExitCleanup = if (directResume) viewModel::leaveDetail else viewModel::clearPlayback,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
