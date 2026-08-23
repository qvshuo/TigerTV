package art.anjing.tigertv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import art.anjing.tigertv.data.ConfigDataSource
import art.anjing.tigertv.data.MacCMSApiClient
import art.anjing.tigertv.data.PlaybackUrlResolver
import art.anjing.tigertv.data.TigerTVRepository
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
    // 使用 applicationContext 构建长生命周期依赖（ConfigDataSource/SearchHistoryStore），
    // 避免被 ViewModel 间接持有 Activity 导致 Activity 无法回收。
    // Activity context 仅用于短期内 ExoPlayer 等生命周期与 Activity 绑定的对象。
    val context = LocalContext.current
    val appContext = context.applicationContext
    val repository = remember {
        TigerTVRepository(
            ConfigDataSource(appContext),
            MacCMSApiClient(),
            PlaybackUrlResolver(),
            coverUrlCacheDir = appContext.cacheDir
        )
    }
    val viewModel: TigerTVViewModel = viewModel(
        factory = TigerTVViewModelFactory(repository, appContext)
    )

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
                onBack = { navController.popBackStack() },
                onSelectResult = { result ->
                    viewModel.selectResult(result)
                    navController.navigate(Screen.Episodes.route)
                }
            )
        }
        composable(Screen.Episodes.route) {
            EpisodesScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onPlay = { index ->
                    viewModel.selectEpisode(index)
                    navController.navigate(Screen.Player.route)
                }
            )
        }
        composable(Screen.Player.route) {
            PlayerScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
