package art.anjing.tigertv.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import art.anjing.tigertv.R
import art.anjing.tigertv.domain.EpisodeLink
import art.anjing.tigertv.domain.FetchResponse
import art.anjing.tigertv.domain.SearchResult
import art.anjing.tigertv.domain.displaySourceName
import art.anjing.tigertv.domain.isCatalogSource
import art.anjing.tigertv.ui.components.UiSpacing
import art.anjing.tigertv.ui.components.UiMetrics
import art.anjing.tigertv.ui.components.ScreenTitle
import art.anjing.tigertv.ui.components.SelectionChip
import art.anjing.tigertv.ui.components.ErrorMessage
import art.anjing.tigertv.ui.components.ActionButton
import art.anjing.tigertv.ui.components.primaryActionColors
import art.anjing.tigertv.ui.components.LoadingIndicator
import art.anjing.tigertv.ui.requestFocusSafely
import art.anjing.tigertv.ui.viewmodel.TigerTVViewModel
import art.anjing.tigertv.data.normalizeCoverUrl

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DetailScreen(viewModel: TigerTVViewModel, onBack: () -> Unit, onPlay: (Int) -> Unit) {
    BackHandler(onBack = onBack)
    val result = viewModel.selectedResult
    val response = viewModel.fetchResponse?.takeIf { it.site == result?.site && it.vodId == result.vodId }
    val sources = viewModel.availableSources
    val episodes = viewModel.episodes
    LaunchedEffect(sources) { viewModel.preloadDetails(sources) }
    fun isEmpty(source: SearchResult) = viewModel.cachedDetail(source)?.playSources?.isEmpty() == true
    val preferredEpisode = viewModel.resumeEpisodeIndex
    val currentEpisode = episodes.getOrNull(preferredEpisode)
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
    val focusSource = result?.takeUnless(::isEmpty) ?: sources.firstOrNull { !isEmpty(it) }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val playFocus = remember { FocusRequester() }
    val sourceFocus = remember { FocusRequester() }
    var entryFocusRequested by remember { mutableStateOf(false) }
    var restoreRetryFocus by remember { mutableStateOf(false) }
    var sourceViewport by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // Only re-anchor when a new response changes the content below the focused resource.
    LaunchedEffect(viewModel.isFetching, response) {
        val (index, offset) = sourceViewport ?: return@LaunchedEffect
        gridState.requestScrollToItem(index, offset)
        if (!viewModel.isFetching) sourceViewport = null
    }

    LaunchedEffect(viewModel.isFetching, response?.site, response?.vodId, episodes.isNotEmpty()) {
        if (!entryFocusRequested && !viewModel.isFetching && viewModel.fetchErrorMessage == null) {
            entryFocusRequested = true
            if (episodes.isNotEmpty()) requestFocusSafely(playFocus)
            else if (sources.isNotEmpty()) requestFocusSafely(sourceFocus)
        }
    }

    val focusInset = with(LocalDensity.current) { UiSpacing.sm.toPx() }
    val focusScroll = remember(focusInset) { DetailFocusScroll(focusInset) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides focusScroll) {
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(UiSpacing.md)) {
                ScreenTitle(overviewDetail?.vodName.orEmpty().ifBlank { result?.vodName.orEmpty() }, Modifier.weight(1f))
                IconButton(onClick = onBack, modifier = Modifier.size(UiMetrics.controlHeight),
                    colors = IconButtonDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedContentColor = MaterialTheme.colorScheme.onSurface),
                    border = IconButtonDefaults.border(focusedBorder = Border(BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface))),
                    scale = IconButtonDefaults.scale(focusedScale = UiMetrics.focusedScale)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                }
            }
            Spacer(Modifier.height(UiSpacing.choiceColumn))
            BoxWithConstraints(Modifier.weight(1f)) {
                val viewportHeight = maxHeight
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = ChoiceInset, end = ChoiceInset, top = UiSpacing.sm, bottom = UiSpacing.lg),
                    horizontalArrangement = Arrangement.spacedBy(ChoiceGap),
                    verticalArrangement = Arrangement.spacedBy(UiSpacing.choiceRow)
                ) {
                    if (result != null) {
                        item(key = "overview", span = { GridItemSpan(maxLineSpan) }) {
                            DetailOverview(result, overviewDetail, response?.vodPic, viewModel.coverFallbacks[result.coverKey],
                                overviewEpisode, !viewModel.isFetching && response != null && currentEpisode != null,
                                hasResumePosition, { onPlay(preferredEpisode) }, playFocus, if (focusSource != null) {
                                    {
                                        scope.launch {
                                            if (gridState.layoutInfo.visibleItemsInfo.none { it.key == "sources" }) {
                                                gridState.scrollToItem(1)
                                            }
                                            requestFocusSafely(sourceFocus)
                                        }
                                    }
                                } else null)
                        }
                    }
                    item(key = "sources", span = { GridItemSpan(maxLineSpan) }) {
                        val matched = sources.groupBy { it.site }.mapValues { (_, matches) ->
                            matches.firstOrNull { it.coverKey == result?.coverKey } ?: matches.first()
                        }
                        val (recommended, other) = viewModel.configuredSites.partition { it.recommendation != null }
                        val otherNames = if (viewModel.configuredSites.isEmpty()) matched.keys.filter(::isCatalogSource)
                            else other.map { it.name }
                        Column(verticalArrangement = Arrangement.spacedBy(UiSpacing.choiceRow)) {
                            SectionTitle("资源")
                            for ((title, sites) in listOf("推荐资源" to recommended.map { it.name }, "其他资源" to otherNames)) {
                                SectionTitle(title)
                                val columns = 6
                                sites.chunked(columns).forEach { row ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ChoiceGap)) {
                                        row.forEach { site ->
                                            key(site) {
                                                val source = matched[site]
                                                val active = source != null && source.coverKey == result?.coverKey
                                                val busy = if (source == null) viewModel.isSiteSearching(site)
                                                    else (viewModel.isFetching && active) || viewModel.isDetailLoading(source)
                                                ResourceChoice(displaySourceName(site), active,
                                                    loading = busy, enabled = source != null && !isEmpty(source),
                                                    modifier = Modifier.weight(1f).then(
                                                        if (source != null && source.coverKey == focusSource?.coverKey) Modifier.focusRequester(sourceFocus) else Modifier
                                                    )) {
                                                    if (source != null && !active) {
                                                        restoreRetryFocus = false
                                                        sourceViewport = gridState.layoutInfo.visibleItemsInfo
                                                            .firstOrNull { it.key == "sources" }?.let { it.index to -it.offset.y }
                                                        viewModel.selectResult(source)
                                                    }
                                                }
                                            }
                                        }
                                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }
                        }
                    }
                    item(key = "episode-label", span = { GridItemSpan(maxLineSpan) }) { SectionTitle("选集") }
                    if (viewModel.isFetching || viewModel.fetchErrorMessage != null || episodes.isEmpty()) {
                        item(key = "episode-status", span = { GridItemSpan(maxLineSpan) }) {
                            // Keep the focused resource at its current viewport position during a request.
                            Box(Modifier.fillMaxWidth().heightIn(min = viewportHeight)) {
                                when {
                                    viewModel.isFetching -> Unit
                                    viewModel.fetchErrorMessage != null -> ErrorMessage(
                                        message = viewModel.fetchErrorMessage ?: stringResource(R.string.error_generic),
                                        onRetry = {
                                            restoreRetryFocus = true
                                            viewModel.retryFetch()
                                        }, compact = true, focusRetry = !entryFocusRequested || restoreRetryFocus)
                                    else -> Text("暂无剧集")
                                }
                            }
                        }
                    } else {
                        itemsIndexed(episodes, key = { index, _ -> "episode-$index" }) { index, episode ->
                            EpisodeCard(episode.name.ifBlank { "第 ${index + 1} 集" }, index == selectedEpisode) {
                                onPlay(index)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailOverview(result: SearchResult, detail: FetchResponse?, poster: String?, fallback: String?,
    episode: EpisodeLink?, canPlay: Boolean, hasResumePosition: Boolean, onPlay: () -> Unit,
    playFocus: FocusRequester, onResources: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = UiSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(UiSpacing.lg),
        verticalAlignment = Alignment.Top
    ) {
        val urls = listOfNotNull(poster, result.vodPic, fallback).mapNotNull(::normalizeCoverUrl).distinct()
        SourcePoster(result, urls, Modifier.width(UiMetrics.posterWidth).height(UiMetrics.posterHeight))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(UiSpacing.sm)) {
            val year = detail?.vodYear?.takeIf { it.isNotBlank() && it != "0" } ?: result.vodYear
            val metadata = listOf(year.takeUnless { it == "0" }.orEmpty(),
                detail?.typeName?.ifBlank { result.typeName } ?: result.typeName,
                detail?.vodArea.orEmpty()).filter { it.isNotBlank() }
            if (metadata.isNotEmpty()) Text(metadata.joinToString(" · "), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ActionButton(onClick = onPlay, enabled = canPlay,
                height = UiMetrics.compactControlHeight,
                colors = primaryActionColors(),
                contentPadding = PaddingValues(horizontal = UiSpacing.lg, vertical = 0.dp),
                modifier = Modifier.padding(vertical = UiSpacing.sm).focusRequester(playFocus).onPreviewKeyEvent {
                    if (onResources != null && it.key == Key.DirectionDown && it.type == KeyEventType.KeyDown) {
                        onResources()
                        true
                    } else false
                }) {
                Icon(painterResource(R.drawable.player_play_arrow), contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(UiSpacing.sm))
                val action = if (hasResumePosition) "继续播放" else "播放"
                Text(episode?.name?.takeIf { it.isNotBlank() }?.let { "$action · $it" } ?: action,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(24.dp + UiSpacing.sm))
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
internal fun ResourceChoice(title: String, selected: Boolean, modifier: Modifier = Modifier,
    loading: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    SelectionChip(selected = selected, onClick = onClick, enabled = enabled,
        focusedScale = UiMetrics.subtleFocusScale,
        modifier = modifier.fillMaxWidth().padding(vertical = ChoiceVerticalInset)) {
        Row(Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(UiSpacing.xs, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center)
            if (loading) LoadingIndicator(Modifier.size(12.dp))
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
private fun EpisodeCard(label: String, isSelected: Boolean, onPlay: () -> Unit) {
    SelectionChip(selected = isSelected, onClick = onPlay,
        focusedScale = UiMetrics.subtleFocusScale,
        modifier = Modifier.fillMaxWidth().padding(vertical = ChoiceVerticalInset)) {
        Text(label, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private val ChoiceInset = UiSpacing.md
private val ChoiceGap = UiSpacing.choiceColumn
private val ChoiceVerticalInset = 2.dp

@OptIn(ExperimentalFoundationApi::class)
private class DetailFocusScroll(private val inset: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        if (size + 2 * inset > containerSize) return super.calculateScrollDistance(offset, size, containerSize)
        return when {
            offset < inset -> offset - inset
            offset + size > containerSize - inset -> offset + size - containerSize + inset
            else -> 0f
        }
    }
}
