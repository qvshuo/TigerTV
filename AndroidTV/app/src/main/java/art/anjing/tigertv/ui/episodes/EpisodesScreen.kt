package art.anjing.tigertv.ui.episodes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import art.anjing.tigertv.R
import art.anjing.tigertv.domain.EpisodeLink
import art.anjing.tigertv.ui.components.ErrorMessage
import art.anjing.tigertv.ui.components.LoadingOverlay
import art.anjing.tigertv.ui.components.TvCardShape
import art.anjing.tigertv.ui.components.tvCardFocusedBorder
import art.anjing.tigertv.ui.requestFocusSafely
import art.anjing.tigertv.ui.viewmodel.TigerTVViewModel

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EpisodesScreen(
    viewModel: TigerTVViewModel,
    onBack: () -> Unit,
    onPlay: (Int) -> Unit
) {
    val result = viewModel.selectedResult
    val response = viewModel.fetchResponse

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(onClick = onBack) {
                    Text(stringResource(R.string.back))
                }
                Column {
                    Text(
                        text = result?.vodName ?: stringResource(R.string.episodes_title),
                        style = MaterialTheme.typography.headlineLarge
                    )
                    result?.site?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))

            when {
                viewModel.isFetching -> LoadingOverlay(stringResource(R.string.loading))
                viewModel.fetchErrorMessage != null -> ErrorMessage(
                    message = viewModel.fetchErrorMessage ?: stringResource(R.string.error_generic),
                    onRetry = {
                        viewModel.clearFetchError()
                        viewModel.retryFetch()
                    }
                )
                response == null || response.vodPlayUrl.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.error_no_results),
                        style = MaterialTheme.typography.displayMedium
                    )
                }
                else -> EpisodesGrid(
                    episodes = response.vodPlayUrl,
                    selectedEpisodeIndex = viewModel.selectedEpisodeIndex,
                    onPlay = onPlay,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodesGrid(
    episodes: List<EpisodeLink>,
    selectedEpisodeIndex: Int,
    onPlay: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val firstCardFocusRequester = remember { FocusRequester() }

    // 每当 episodes 变更即恢复首项焦点，去掉一次性 `focused` 标志避免二次重入时焦点丢失。
    LaunchedEffect(episodes) {
        if (episodes.isNotEmpty()) {
            requestFocusSafely(firstCardFocusRequester)
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 144.dp),
        modifier = modifier,
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        itemsIndexed(episodes) { index, episode ->
            val isSelected = index == selectedEpisodeIndex
            val focusModifier = if (index == 0) {
                Modifier.focusRequester(firstCardFocusRequester)
            } else {
                Modifier
            }
            Card(
                onClick = { onPlay(index) },
                modifier = focusModifier
                    .fillMaxWidth()
                    .height(76.dp),
                shape = CardDefaults.shape(shape = TvCardShape),
                border = tvCardFocusedBorder(),
                // 高亮当前正在播放的剧集，让用户从播放页返回后能立即看到自己的位置。
                colors = CardDefaults.colors(
                    containerColor = if (isSelected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = episode.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
