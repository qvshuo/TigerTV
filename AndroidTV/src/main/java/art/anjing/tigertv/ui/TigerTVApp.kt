package art.anjing.tigertv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import art.anjing.tigertv.ui.episodes.EpisodesScreen
import art.anjing.tigertv.ui.home.HomeScreen
import art.anjing.tigertv.ui.player.PlayerScreen
import art.anjing.tigertv.ui.results.ResultsScreen
import art.anjing.tigertv.ui.viewmodel.TigerTVViewModel
import art.anjing.tigertv.ui.viewmodel.TigerTVViewModelFactory

sealed class Screen(val route: String) {
    data object Home : Screen("home")
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
    NavHost(navController = navController, startDestination = Screen.Home.route) {
        composable(Screen.Home.route) {
            HomeScreen(
                viewModel = viewModel,
                onSearch = {
                    navController.navigate(Screen.Results.route)
                }
            )
        }
        composable(Screen.Results.route) {
            ResultsScreen(
                viewModel = viewModel,
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
        composable(Screen.Episodes.route,
            exitTransition = { if (targetState.destination.route == Screen.Player.route) ExitTransition.None else null },
            popEnterTransition = { if (initialState.destination.route == Screen.Player.route) EnterTransition.None else null }
        ) {
            EpisodesScreen(
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
        // Video uses its own Surface; alpha transitions expose an empty frame on teardown.
        composable(Screen.Player.route,
            enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None }, popExitTransition = { ExitTransition.None }
        ) {
            PlayerScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
