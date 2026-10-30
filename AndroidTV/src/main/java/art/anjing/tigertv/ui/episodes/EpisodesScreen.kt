package art.anjing.tigertv.ui.episodes

import androidx.activity.compose.BackHandler

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import coil3.compose.SubcomposeAsyncImage
import art.anjing.tigertv.R
import art.anjing.tigertv.domain.EpisodeLink
import art.anjing.tigertv.domain.FetchResponse
import art.anjing.tigertv.domain.SearchResult
import art.anjing.tigertv.ui.components.tvGlass
import art.anjing.tigertv.ui.components.UiSpacing
import art.anjing.tigertv.ui.components.UiMetrics
import art.anjing.tigertv.ui.components.ErrorMessage
import art.anjing.tigertv.ui.components.GlassButton
import art.anjing.tigertv.ui.components.BackButton
import art.anjing.tigertv.ui.components.LoadingOverlay
import art.anjing.tigertv.ui.requestFocusSafely
import art.anjing.tigertv.ui.viewmodel.TigerTVViewModel
import art.anjing.tigertv.util.normalizeCoverUrl

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EpisodesScreen(viewModel: TigerTVViewModel, onBack: () -> Unit, onPlay: (Int) -> Unit) {
    BackHandler(onBack = onBack)
    val result = viewModel.selectedResult
    val response = viewModel.fetchResponse?.takeIf { it.site == result?.site && it.vodId == result.vodId }
    val sources = viewModel.availableSources
    val episodes = viewModel.episodes
    val bookmark = viewModel.playbackBookmark
    val preferredSource = sources.indexOfFirst { it.site == bookmark?.site }.coerceAtLeast(0)
    val preferredLine = response?.playSources?.indexOfFirst { it.id == bookmark?.line && result?.site == bookmark.site }?.coerceAtLeast(0) ?: 0
    val preferredEpisode = viewModel.resumeEpisodeIndex
    val gridState = rememberLazyGridState()
    val sourceRow = rememberLazyListState()
    val lineRow = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val backFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val sourceFocus = remember { FocusRequester() }
    val lineFocus = remember { FocusRequester() }
    val episodeFocus = remember { FocusRequester() }
    var entryFocusRequested by remember { mutableStateOf(false) }
    val sourceRowIndex = if (result != null) 2 else 1
    val hasLines = response?.playSources?.isNotEmpty() == true

    fun focusPlay() { scope.launch {
        gridState.animateScrollToItem(0)
        requestFocusSafely(playFocus)
    } }
    fun focusSources() { scope.launch {
        if (sources.isEmpty()) return@launch
        gridState.animateScrollToItem(sourceRowIndex - 1)
        sourceRow.scrollToItem(preferredSource)
        requestFocusSafely(sourceFocus)
    } }
    fun focusLines() { scope.launch {
        gridState.animateScrollToItem(sourceRowIndex + 1)
        lineRow.scrollToItem(preferredLine)
        requestFocusSafely(lineFocus)
    } }
    fun focusEpisodes() { scope.launch {
        gridState.animateScrollToItem(sourceRowIndex + (if (hasLines) 4 else 2) + preferredEpisode)
        requestFocusSafely(episodeFocus)
    } }

    LaunchedEffect(viewModel.isFetching, response?.site, response?.vodId, episodes.isNotEmpty()) {
        if (!entryFocusRequested && !viewModel.isFetching && viewModel.fetchErrorMessage == null) {
            entryFocusRequested = true
            if (episodes.isNotEmpty()) focusPlay()
            else if (sources.isNotEmpty()) focusSources()
            else requestFocusSafely(backFocus)
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = UiSpacing.xl, vertical = UiSpacing.lg)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onClick = onBack, modifier = Modifier.focusRequester(backFocus))
            Text(result?.site.orEmpty(), modifier = Modifier.weight(1f).padding(start = UiSpacing.md),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(UiSpacing.md))
        // Reveal lazy rows before requesting their remembered control.
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 132.dp),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(UiSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(UiSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(UiSpacing.md)
        ) {
            if (result != null) {
                item(key = "overview-${result.coverKey}", span = { GridItemSpan(maxLineSpan) }) {
                    DetailOverview(result, response, viewModel.coverFallbacks[result.coverKey],
                        preferredEpisode, episodes, onPlay,
                        { scope.launch { gridState.animateScrollToItem(0) } }, playFocus, ::focusSources)
                }
            }
            item(key = "source-label", span = { GridItemSpan(maxLineSpan) }) { SectionTitle("资源") }
            item(key = "sources", span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(state = sourceRow,
                    modifier = Modifier.onDpad(Key.DirectionUp, if (episodes.isNotEmpty()) ::focusPlay else null)
                        .onDpad(Key.DirectionDown, if (hasLines) ::focusLines else if (episodes.isNotEmpty()) ::focusEpisodes else null),
                    horizontalArrangement = Arrangement.spacedBy(UiSpacing.sm), contentPadding = PaddingValues(UiSpacing.sm)) {
                    itemsIndexed(sources, key = { _, source -> source.coverKey }) { index, source ->
                        DetailChoice(source.site, source.coverKey == result?.coverKey,
                            modifier = if (index == preferredSource) Modifier.focusRequester(sourceFocus) else Modifier) {
                            viewModel.selectResult(source)
                        }
                    }
                }
            }
            when {
                viewModel.isFetching -> item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                    LoadingOverlay(stringResource(R.string.loading), Modifier.fillMaxWidth().height(UiMetrics.loadingRegion))
                }
                viewModel.fetchErrorMessage != null -> item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                    ErrorMessage(message = viewModel.fetchErrorMessage ?: stringResource(R.string.error_generic), onRetry = viewModel::retryFetch)
                }
                response != null -> {
                    if (hasLines) {
                        item(key = "line-label", span = { GridItemSpan(maxLineSpan) }) { SectionTitle("线路") }
                        item(key = "lines", span = { GridItemSpan(maxLineSpan) }) {
                            LazyRow(state = lineRow,
                                modifier = Modifier.onDpad(Key.DirectionUp, ::focusSources)
                                    .onDpad(Key.DirectionDown, if (episodes.isNotEmpty()) ::focusEpisodes else null),
                                horizontalArrangement = Arrangement.spacedBy(UiSpacing.sm), contentPadding = PaddingValues(UiSpacing.sm)) {
                                itemsIndexed(response.playSources, key = { _, source -> source.id }) { index, source ->
                                    DetailChoice(source.name, index == viewModel.selectedPlaybackSourceIndex,
                                        modifier = if (index == preferredLine) Modifier.focusRequester(lineFocus) else Modifier) {
                                        viewModel.selectPlaybackSource(index)
                                    }
                                }
                            }
                        }
                    }
                    item(key = "episode-label", span = { GridItemSpan(maxLineSpan) }) { SectionTitle("选集") }
                    if (episodes.isEmpty()) {
                        item(key = "empty", span = { GridItemSpan(maxLineSpan) }) { Text("暂无剧集") }
                    }
                    itemsIndexed(episodes, key = { index, _ -> "episode-$index" }) { index, episode ->
                        EpisodeCard(episode, index == viewModel.selectedEpisodeIndex,
                            modifier = if (index == preferredEpisode) Modifier.focusRequester(episodeFocus) else Modifier) { onPlay(index) }
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
private fun DetailOverview(result: SearchResult, detail: FetchResponse?, fallback: String?,
    resumeIndex: Int, episodes: List<EpisodeLink>, onPlay: (Int) -> Unit, onPlayFocus: () -> Unit, playFocus: FocusRequester, onPlayDown: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .tvGlass()
            .padding(UiSpacing.lg),
        horizontalArrangement = Arrangement.spacedBy(UiSpacing.lg),
        verticalAlignment = Alignment.Top
    ) {
        // Only the selected site's own detail/list/fallback URLs are eligible here.
        val urls = listOfNotNull(detail?.vodPic, result.vodPic, fallback).mapNotNull(::normalizeCoverUrl).distinct()
        SourcePoster(result, urls, Modifier.width(UiMetrics.posterWidth).height(UiMetrics.posterHeight))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(UiSpacing.sm)) {
            Text(detail?.vodName?.ifBlank { result.vodName } ?: result.vodName,
                style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            val metadata = listOf(detail?.vodYear ?: result.vodYear, detail?.typeName ?: result.typeName, detail?.vodArea.orEmpty()).filter { it.isNotBlank() }
            if (metadata.isNotEmpty()) Text(metadata.joinToString(" · "), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(UiSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                if (!detail?.vodLang.isNullOrBlank()) Text(detail.vodLang, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val status = detail?.vodRemarks ?: result.vodRemarks
                if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(12.dp)).padding(horizontal = UiSpacing.sm, vertical = UiSpacing.xs))
            }
            episodes.getOrNull(resumeIndex)?.let { episode ->
                GlassButton(onClick = { onPlay(resumeIndex) },
                    radius = 28.dp, height = UiMetrics.controlHeight,
                    contentPadding = PaddingValues(horizontal = UiSpacing.lg, vertical = 0.dp),
                    modifier = Modifier.padding(vertical = UiSpacing.sm).focusRequester(playFocus).onDpad(Key.DirectionDown, onPlayDown)
                        .onFocusChanged { if (it.isFocused) onPlayFocus() }) {
                    val iconColor = LocalContentColor.current
                    Canvas(Modifier.size(16.dp)) {
                        drawPath(Path().apply {
                            moveTo(size.width * 0.2f, 0f)
                            lineTo(size.width, size.height * 0.5f)
                            lineTo(size.width * 0.2f, size.height)
                            close()
                        }, iconColor)
                    }
                    Spacer(Modifier.width(UiSpacing.sm))
                    Text("播放 · ${episode.name}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium.copy(
                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)
                        ))
                }
            }
            if (!detail?.vodDirector.isNullOrBlank()) Credit("导演", detail.vodDirector)
            if (!detail?.vodActor.isNullOrBlank()) Credit("主演", detail.vodActor)
            if (!detail?.vodContent.isNullOrBlank()) {
                Credit("简介", detail.vodContent, maxLines = Int.MAX_VALUE)
            }
        }
    }
}

@Composable
private fun Credit(label: String, value: String, maxLines: Int = 2) {
    Row(horizontalArrangement = Arrangement.spacedBy(UiSpacing.sm), verticalAlignment = Alignment.Top) {
        Text(label, modifier = Modifier.width(32.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f))
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
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
private fun DetailChoice(title: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    GlassButton(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(horizontal = UiSpacing.md, vertical = 0.dp)) {
        Box(Modifier.height(UiMetrics.controlHeight), contentAlignment = Alignment.Center) {
            Text((if (selected) "✓ " else "") + title, maxLines = 1)
        }
    }
}

@Composable
private fun SourcePoster(result: SearchResult, urls: List<String>, modifier: Modifier) {
    var index by remember(result.coverKey, urls) { mutableIntStateOf(0) }
    val url = urls.getOrNull(index)
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (url == null) PosterPlaceholder(result.vodName)
        else SubcomposeAsyncImage(model = url, contentDescription = result.vodName, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(), loading = { PosterPlaceholder(result.vodName) }, error = { PosterPlaceholder(result.vodName) },
            onError = { if (urls.getOrNull(index) == url) index++ })
    }
}

@Composable
private fun PosterPlaceholder(title: String) {
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.06f), Color.Black.copy(alpha = 0.18f)))), contentAlignment = Alignment.Center) {
        Text(title.take(1), style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodeCard(episode: EpisodeLink, isSelected: Boolean, modifier: Modifier = Modifier, onPlay: () -> Unit) {
    GlassButton(onClick = onPlay, modifier = modifier.fillMaxWidth(), height = 64.dp,
        contentPadding = PaddingValues(horizontal = UiSpacing.md, vertical = UiSpacing.sm)) {
        Text((if (isSelected) "✓ " else "") + episode.name, style = MaterialTheme.typography.bodyLarge,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
