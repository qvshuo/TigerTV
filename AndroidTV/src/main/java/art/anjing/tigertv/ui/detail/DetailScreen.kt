package art.anjing.tigertv.ui.detail

import androidx.activity.compose.BackHandler

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.*
import coil3.compose.AsyncImage
import art.anjing.tigertv.R
import art.anjing.tigertv.domain.EpisodeLink
import art.anjing.tigertv.domain.FetchResponse
import art.anjing.tigertv.domain.SearchResult
import art.anjing.tigertv.ui.components.UiSpacing
import art.anjing.tigertv.ui.components.UiMetrics
import art.anjing.tigertv.ui.components.SelectionChip
import art.anjing.tigertv.ui.components.ErrorMessage
import art.anjing.tigertv.ui.components.ActionButton
import art.anjing.tigertv.ui.components.LoadingOverlay
import art.anjing.tigertv.ui.requestFocusSafely
import art.anjing.tigertv.ui.viewmodel.TigerTVViewModel
import art.anjing.tigertv.data.normalizeCoverUrl

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun DetailScreen(viewModel: TigerTVViewModel, onBack: () -> Unit, onPlay: (Int) -> Unit) {
    BackHandler(onBack = onBack)
    val result = viewModel.selectedResult
    val response = viewModel.fetchResponse?.takeIf { it.site == result?.site && it.vodId == result.vodId }
    val sources = viewModel.availableSources
    val episodes = viewModel.episodes
    val preferredSource = sources.indexOfFirst { it.coverKey == result?.coverKey }.coerceAtLeast(0)
    val preferredLine = viewModel.selectedPlaybackSourceIndex
    val preferredEpisode = viewModel.resumeEpisodeIndex
    val currentEpisode = episodes.getOrNull(preferredEpisode)
    val episodeLabelHeight = with(LocalDensity.current) { MaterialTheme.typography.labelLarge.lineHeight.toDp() * 2 }
    val episodeCellHeight = maxOf(UiMetrics.controlHeight, episodeLabelHeight + UiSpacing.md)
    val selectedEpisode = viewModel.selectedEpisodeIndex
    val hasResumePosition = viewModel.resumePositionMillis > 0
    var retainedDetail by remember { mutableStateOf(response) }
    var retainedEpisode by remember { mutableStateOf(currentEpisode) }
    LaunchedEffect(response, currentEpisode) {
        if (response != null) {
            retainedDetail = response
            retainedEpisode = currentEpisode
        }
    }
    val overviewDetail = response ?: retainedDetail
    val overviewEpisode = if (response != null) currentEpisode else retainedEpisode
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val playFocus = remember { FocusRequester() }
    val sourceFocus = remember { FocusRequester() }
    val lineFocus = remember { FocusRequester() }
    val episodeFocus = remember { FocusRequester() }
    var entryFocusRequested by remember { mutableStateOf(false) }
    var restoreRetryFocus by remember { mutableStateOf(false) }
    var sourceViewport by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val sourceRowIndex = if (result != null) 2 else 1
    val hasLines = response?.playSources?.isNotEmpty() == true

    var focusJump by remember { mutableStateOf<Job?>(null) }
    fun jump(index: Int, target: FocusRequester) {
        focusJump?.cancel()
        focusJump = scope.launch {
            gridState.scrollToItem(index)
            requestFocusSafely(target)
        }
    }

    fun focusPlay() = jump(0, playFocus)
    fun focusSources() { if (sources.isNotEmpty()) jump(sourceRowIndex - 1, sourceFocus) }
    fun focusLines() = jump(sourceRowIndex + 1, lineFocus)

    // Only re-anchor when a new response changes the content below the focused resource.
    LaunchedEffect(viewModel.isFetching, response) {
        val (index, offset) = sourceViewport ?: return@LaunchedEffect
        gridState.requestScrollToItem(index, offset)
        if (!viewModel.isFetching) sourceViewport = null
    }

    LaunchedEffect(viewModel.isFetching, response?.site, response?.vodId, episodes.isNotEmpty()) {
        if (!entryFocusRequested && !viewModel.isFetching && viewModel.fetchErrorMessage == null) {
            entryFocusRequested = true
            if (episodes.isNotEmpty()) focusPlay()
            else if (sources.isNotEmpty()) focusSources()
        }
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides DetailFocusScroll) {
        Column(Modifier.fillMaxSize().onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown) {
                when (it.key) {
                    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight -> {
                        sourceViewport = null
                        restoreRetryFocus = false
                    }
                }
            }
            false
        }.padding(horizontal = UiMetrics.pageHorizontalPadding, vertical = UiMetrics.pageVerticalPadding)) {
            BoxWithConstraints(Modifier.weight(1f)) {
                val viewportHeight = maxHeight
                fun focusEpisodes() = jump(sourceRowIndex + 4 + preferredEpisode, episodeFocus)
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = EpisodeMinWidth),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = ChoiceInset, vertical = UiSpacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(ChoiceGap),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (result != null) {
                        item(key = "overview", span = { GridItemSpan(maxLineSpan) }) {
                            DetailOverview(result, overviewDetail, response?.vodPic, viewModel.coverFallbacks[result.coverKey],
                                overviewEpisode, !viewModel.isFetching && response != null && currentEpisode != null,
                                hasResumePosition, { onPlay(preferredEpisode) },
                                { if (gridState.firstVisibleItemIndex != 0 || gridState.firstVisibleItemScrollOffset != 0) {
                                    scope.launch { gridState.scrollToItem(0) }
                                } }, playFocus,
                                ::focusSources)
                        }
                    }
                    item(key = "source-label", span = { GridItemSpan(maxLineSpan) }) { SectionTitle("资源") }
                    item(key = "sources", span = { GridItemSpan(maxLineSpan) }) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth().padding(vertical = UiSpacing.sm)
                                .focusProperties {
                                    onExit = {
                                        val destination = when (requestedFocusDirection) {
                                            FocusDirection.Up -> if (episodes.isNotEmpty()) ::focusPlay else null
                                            FocusDirection.Down -> if (hasLines) ::focusLines else if (episodes.isNotEmpty()) ::focusEpisodes else null
                                            else -> null
                                        }
                                        if (requestedFocusDirection == FocusDirection.Up || destination != null) {
                                            cancelFocusChange()
                                            destination?.invoke()
                                        }
                                    }
                                }
                                .focusGroup(),
                            horizontalArrangement = Arrangement.spacedBy(ChoiceGap),
                            verticalArrangement = Arrangement.spacedBy(ChoiceGap)
                        ) {
                            sources.forEachIndexed { index, source ->
                                key(source.coverKey) {
                                    DetailChoice(source.site, source.coverKey == result?.coverKey,
                                        modifier = if (index == preferredSource) Modifier.focusRequester(sourceFocus) else Modifier) {
                                        if (source.coverKey != result?.coverKey) {
                                            restoreRetryFocus = false
                                            sourceViewport = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "sources" }
                                                ?.let { it.index to -it.offset.y }
                                            viewModel.selectResult(source)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item(key = "line-label", span = { GridItemSpan(maxLineSpan) }) { SectionTitle("线路") }
                    item(key = "lines", span = { GridItemSpan(maxLineSpan) }) {
                        if (viewModel.isFetching) {
                            LoadingOverlay(stringResource(R.string.loading), Modifier.fillMaxWidth().height(UiMetrics.controlHeight + UiSpacing.sm * 2))
                        } else if (viewModel.fetchErrorMessage != null) {
                            ErrorMessage(message = viewModel.fetchErrorMessage ?: stringResource(R.string.error_generic),
                                onRetry = {
                                    restoreRetryFocus = true
                                    viewModel.retryFetch()
                                    focusSources()
                                }, compact = true, focusRetry = !entryFocusRequested || restoreRetryFocus)
                        } else if (response != null && hasLines) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth().padding(vertical = UiSpacing.sm)
                                    .focusProperties {
                                        onExit = {
                                            val destination = when (requestedFocusDirection) {
                                                FocusDirection.Up -> ::focusSources
                                                FocusDirection.Down -> if (episodes.isNotEmpty()) ::focusEpisodes else null
                                                else -> null
                                            }
                                            if (destination != null) {
                                                cancelFocusChange()
                                                destination()
                                            }
                                        }
                                    }
                                    .focusGroup(),
                                horizontalArrangement = Arrangement.spacedBy(ChoiceGap),
                                verticalArrangement = Arrangement.spacedBy(ChoiceGap),
                            ) {
                                response.playSources.forEachIndexed { index, source ->
                                    key(source.id) {
                                        DetailChoice(source.name, index == preferredLine,
                                            modifier = if (index == preferredLine) Modifier.focusRequester(lineFocus) else Modifier) {
                                            viewModel.selectPlaybackSource(index)
                                        }
                                    }
                                }
                            }
                        } else {
                            Box(Modifier.fillMaxWidth().height(UiMetrics.controlHeight + UiSpacing.sm * 2), contentAlignment = Alignment.CenterStart) {
                                if (viewModel.fetchErrorMessage == null) Text("暂无线路")
                            }
                        }
                    }
                    item(key = "episode-label", span = { GridItemSpan(maxLineSpan) }) { SectionTitle("选集") }
                    when {
                        viewModel.isFetching || viewModel.fetchErrorMessage != null || episodes.isEmpty() ->
                            item(key = "episode-status", span = { GridItemSpan(maxLineSpan) }) {
                                // Retain scroll room when loading or empty content replaces the episodes.
                                Box(Modifier.fillMaxWidth().heightIn(min = maxOf(UiMetrics.loadingRegion, viewportHeight))) {
                                    if (!viewModel.isFetching && viewModel.fetchErrorMessage == null) Text("暂无播放内容")
                                }
                            }
                        else -> itemsIndexed(episodes, key = { index, _ -> "episode-$index" }) { index, episode ->
                            EpisodeCard(episode, index == selectedEpisode, episodeCellHeight,
                                modifier = if (index == preferredEpisode) Modifier.focusRequester(episodeFocus) else Modifier) { onPlay(index) }
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.onDpad(key: Key, action: (() -> Unit)?): Modifier = onPreviewKeyEvent {
    if (action != null && it.type == KeyEventType.KeyDown && it.key == key) {
        action()
        true
    } else false
}

@Composable
private fun DetailOverview(result: SearchResult, detail: FetchResponse?, poster: String?, fallback: String?,
    episode: EpisodeLink?, canPlay: Boolean, hasResumePosition: Boolean, onPlay: () -> Unit, onPlayFocus: () -> Unit,
    playFocus: FocusRequester, onPlayDown: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = UiSpacing.lg),
        horizontalArrangement = Arrangement.spacedBy(UiSpacing.lg),
        verticalAlignment = Alignment.Top
    ) {
        // Only the selected site's own detail/list/fallback URLs are eligible here.
        val urls = listOfNotNull(poster, result.vodPic, fallback).mapNotNull(::normalizeCoverUrl).distinct()
        SourcePoster(result, urls, Modifier.width(UiMetrics.posterWidth).height(UiMetrics.posterHeight))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(UiSpacing.sm)) {
            Text(detail?.vodName?.ifBlank { result.vodName } ?: result.vodName,
                style = MaterialTheme.typography.headlineMedium)
            val metadata = listOf(detail?.vodYear ?: result.vodYear, detail?.typeName ?: result.typeName, detail?.vodArea.orEmpty()).filter { it.isNotBlank() }
            if (metadata.isNotEmpty()) Text(metadata.joinToString(" · "), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ActionButton(onClick = onPlay, enabled = canPlay,
                height = 64.dp,
                colors = ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary),
                contentPadding = PaddingValues(horizontal = UiSpacing.lg, vertical = 0.dp),
                modifier = Modifier.padding(vertical = UiSpacing.sm).focusRequester(playFocus).onDpad(Key.DirectionDown, onPlayDown)
                    .onFocusChanged { if (it.isFocused) onPlayFocus() }) {
                Icon(painterResource(R.drawable.player_play_arrow), contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(UiSpacing.sm))
                val action = if (hasResumePosition) "继续播放" else "播放"
                Text(episode?.name?.takeIf { it.isNotBlank() }?.let { "$action · $it" } ?: action,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            }
            if (!detail?.vodContent.isNullOrBlank()) {
                Text(detail.vodContent, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Row(Modifier.fillMaxWidth().padding(top = UiSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun DetailChoice(title: String, selected: Boolean, modifier: Modifier = Modifier, maxLines: Int = 1, onClick: () -> Unit) {
    SelectionChip(selected = selected, onClick = onClick,
        modifier = modifier.width(IntrinsicSize.Max).heightIn(min = UiMetrics.controlHeight)) {
        Row(Modifier.fillMaxWidth().heightIn(min = UiMetrics.controlHeight - 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f, fill = false), textAlign = TextAlign.Center,
                maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SourcePoster(result: SearchResult, urls: List<String>, modifier: Modifier) {
    var index by remember(result.coverKey, urls) { mutableIntStateOf(0) }
    val url = urls.getOrNull(index)
    var loaded by remember(url) { mutableStateOf(false) }
    Box(modifier.clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (!loaded) PosterPlaceholder(result.vodName)
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(), onSuccess = { loaded = true },
            onError = { if (urls.getOrNull(index) == url) index++ })
    }
}

@Composable
private fun PosterPlaceholder(title: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(title.take(1), style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodeCard(episode: EpisodeLink, isSelected: Boolean, height: Dp, modifier: Modifier = Modifier, onPlay: () -> Unit) {
    SelectionChip(selected = isSelected, onClick = onPlay,
        modifier = modifier.fillMaxWidth().height(height)) {
        Box(Modifier.fillMaxWidth().height(height - UiSpacing.md), contentAlignment = Alignment.Center) {
            Text(episode.name.ifBlank { "未命名剧集" }, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private val EpisodeMinWidth = 120.dp
private val ChoiceInset = UiSpacing.md
private val ChoiceGap = 20.dp

// Source updates must not re-center controls that are already visible.
@OptIn(ExperimentalFoundationApi::class)
private object DetailFocusScroll : BringIntoViewSpec
