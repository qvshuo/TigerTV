package art.anjing.tigertv.ui.results

import art.anjing.tigertv.ui.components.MediaCard
import androidx.activity.compose.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import art.anjing.tigertv.R
import art.anjing.tigertv.domain.VideoWork
import art.anjing.tigertv.domain.SearchResult
import art.anjing.tigertv.ui.components.WorkPoster
import art.anjing.tigertv.ui.components.ScreenTitle
import art.anjing.tigertv.ui.components.UiMetrics
import art.anjing.tigertv.ui.components.CardFocusScroll
import art.anjing.tigertv.ui.components.ErrorMessage
import art.anjing.tigertv.ui.components.LoadingIndicator
import art.anjing.tigertv.ui.requestFocusSafely
import art.anjing.tigertv.ui.viewmodel.TigerTVViewModel
import art.anjing.tigertv.data.normalizeCoverUrl

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ResultsScreen(
    viewModel: TigerTVViewModel,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onSelectResult: (SearchResult) -> Unit
) {
    BackHandler(onBack = onBack)
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = UiMetrics.pageHorizontalPadding, vertical = UiMetrics.pageVerticalPadding)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ScreenTitle("${stringResource(R.string.results_title)}: ${viewModel.submittedKeyword}", Modifier.weight(1f))
                IconButton(onClick = onSearch, enabled = !viewModel.isSearching, modifier = Modifier.size(UiMetrics.controlHeight),
                    scale = IconButtonDefaults.scale(focusedScale = 1.02f)) {
                    if (viewModel.isSearching) LoadingIndicator(Modifier.size(UiMetrics.compactLoadingIndicator))
                    else Icon(Icons.Rounded.Search, contentDescription = "搜索")
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            when {
                viewModel.isSearching && viewModel.works.isEmpty() -> Spacer(Modifier.fillMaxSize())
                viewModel.searchErrorMessage != null -> ErrorMessage(
                    message = viewModel.searchErrorMessage ?: stringResource(R.string.error_generic),
                    onRetry = {
                        viewModel.clearSearchError()
                        viewModel.search()
                    }
                )
                viewModel.works.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.error_no_results),
                        style = MaterialTheme.typography.displayMedium
                    )
                }
                else -> ResultsGrid(
                    works = viewModel.works,
                    viewModel = viewModel,
                    restoreSourceKey = viewModel.selectedResult?.coverKey,
                    coverFallbacks = viewModel.coverFallbacks,
                    onLoadCoverFallback = viewModel::loadCoverFallbackIfPossible,
                    onSelectResult = onSelectResult,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ResultsGrid(
    works: List<VideoWork>,
    viewModel: TigerTVViewModel,
    restoreSourceKey: String?,
    coverFallbacks: Map<String, String>,
    onLoadCoverFallback: (SearchResult) -> Unit,
    onSelectResult: (SearchResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusIndex = if (restoreSourceKey == null) 0 else {
        works.indexOfFirst { work -> work.sources.any { it.coverKey == restoreSourceKey } }.coerceAtLeast(0)
    }
    val focusWorkId = works[focusIndex].id
    val cardFocusRequester = remember { FocusRequester() }
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = focusIndex)
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()

    // Navigation must finish and the restored card must attach before requesting focus.
    LaunchedEffect(lifecycleState, focusWorkId) {
        if (lifecycleState == Lifecycle.State.RESUMED) {
            if (gridState.layoutInfo.visibleItemsInfo.none { it.key == focusWorkId }) {
                gridState.scrollToItem(focusIndex)
            }
            snapshotFlow { gridState.layoutInfo.visibleItemsInfo.any { it.key == focusWorkId } }.first { it }
            requestFocusSafely(cardFocusRequester)
        }
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides CardFocusScroll) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(5),
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = UiMetrics.cardGridHorizontalPadding, vertical = UiMetrics.cardGridVerticalPadding),
            horizontalArrangement = Arrangement.spacedBy(UiMetrics.cardGap),
            verticalArrangement = Arrangement.spacedBy(UiMetrics.cardGap)
        ) {
            itemsIndexed(works, key = { _, work -> work.id }) { _, work ->
                WorkCard(
                    work = work,
                    result = viewModel.cardSource(work),
                    poster = viewModel.workCover(work.id),
                    onPosterShown = { viewModel.rememberWorkCover(work.id, it) },
                    coverFallbacks = coverFallbacks,
                    onLoadCoverFallback = onLoadCoverFallback,
                    onSelectResult = onSelectResult,
                    modifier = if (work.id == focusWorkId) Modifier.focusRequester(cardFocusRequester) else Modifier
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun WorkCard(
    work: VideoWork,
    result: SearchResult,
    poster: String?,
    onPosterShown: (String) -> Unit,
    coverFallbacks: Map<String, String>,
    onLoadCoverFallback: (SearchResult) -> Unit,
    onSelectResult: (SearchResult) -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(work.sources) { work.orderedSources.forEach(onLoadCoverFallback) }
    MediaCard(onClick = { onSelectResult(work.defaultSource) }, modifier = modifier) {
        WorkPoster(
            workId = work.id,
            urls = (listOfNotNull(poster) + (listOf(result) + work.orderedSources).flatMap { source ->
                listOfNotNull(normalizeCoverUrl(source.vodPic), normalizeCoverUrl(coverFallbacks[source.coverKey].orEmpty()))
            }).distinct(),
            onShown = onPosterShown,
            title = result.vodName,
            modifier = Modifier.fillMaxWidth().aspectRatio(CoverAspectRatio).clip(MaterialTheme.shapes.small)
        )
        art.anjing.tigertv.ui.components.MediaCardDetails(
            title = if (result.vodYear.isBlank() || result.vodYear == "0") result.vodName else "${result.vodName} (${result.vodYear})",
            footer = result.vodTime
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${work.sources.size} 个资源", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary, maxLines = 1,
                    modifier = Modifier.background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small)
                        .padding(horizontal = 6.dp, vertical = 2.dp))
                Text(result.vodRemarks, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
    }
}


private const val CoverAspectRatio = 2f / 3f
