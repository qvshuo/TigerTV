package art.anjing.tigertv.ui.results

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.tv.material3.Border
import art.anjing.tigertv.ui.components.tvGlass
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.SubcomposeAsyncImage
import art.anjing.tigertv.R
import art.anjing.tigertv.domain.VideoWork
import art.anjing.tigertv.domain.SearchResult
import art.anjing.tigertv.ui.components.BackButton
import art.anjing.tigertv.ui.components.ErrorMessage
import art.anjing.tigertv.ui.components.SearchButton
import art.anjing.tigertv.ui.components.TvCardShape
import art.anjing.tigertv.ui.requestFocusSafely
import art.anjing.tigertv.ui.viewmodel.TigerTVViewModel
import art.anjing.tigertv.util.normalizeCoverUrl

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ResultsScreen(
    viewModel: TigerTVViewModel,
    onBack: () -> Unit,
    onSelectResult: (SearchResult) -> Unit
) {
    BackHandler(onBack = onBack)
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
                BackButton(onClick = onBack)
                Text(
                    text = "${stringResource(R.string.results_title)}: ${viewModel.submittedKeyword}",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                SearchButton(isSearching = viewModel.isSearching) { viewModel.search() }
            }
            Spacer(modifier = Modifier.height(24.dp))

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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ResultsGrid(
    works: List<VideoWork>,
    restoreSourceKey: String?,
    coverFallbacks: Map<String, String>,
    onLoadCoverFallback: (SearchResult) -> Unit,
    onSelectResult: (SearchResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusWorkId = remember {
        works.firstOrNull { work -> work.sources.any { it.coverKey == restoreSourceKey } }?.id ?: works.first().id
    }
    val cardFocusRequester = remember { FocusRequester() }

    // Request focus on entry only; incremental snapshots must not steal D-pad focus.
    LaunchedEffect(Unit) {
        if (works.isNotEmpty()) {
            requestFocusSafely(cardFocusRequester)
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val columns = derivedColumnCount(maxWidth, maxHeight)
        val cellWidth = (maxWidth - GridContentPadding * 2 - ColumnSpacing * (columns - 1)) / columns
        val cardWidth = minOf(cellWidth, ((maxHeight - GridVerticalPadding * 2 - CardCaptionHeight) * CoverAspectRatio).coerceIn(140.dp, 220.dp))
        val gridWidth = cardWidth * columns + ColumnSpacing * (columns - 1) + GridContentPadding * 2
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.width(gridWidth).fillMaxHeight().align(Alignment.TopCenter),
            contentPadding = PaddingValues(horizontal = GridContentPadding, vertical = GridVerticalPadding),
            horizontalArrangement = Arrangement.spacedBy(ColumnSpacing),
            verticalArrangement = Arrangement.spacedBy(RowSpacing)
        ) {
            itemsIndexed(works, key = { _, work -> work.id }) { _, work ->
                WorkCard(
                    work = work,
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
    coverFallbacks: Map<String, String>,
    onLoadCoverFallback: (SearchResult) -> Unit,
    onSelectResult: (SearchResult) -> Unit,
    modifier: Modifier = Modifier
) {
    val result = work.defaultSource
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val focus = animateFloatAsState(if (focused) 1f else 0f, tween(160), label = "card glass focus")
    LaunchedEffect(work.sources) { work.sources.forEach(onLoadCoverFallback) }
    Card(
        onClick = { onSelectResult(result) },
        modifier = modifier.fillMaxWidth(),
        interactionSource = interactions,
        shape = CardDefaults.shape(TvCardShape),
        scale = CardDefaults.scale(focusedScale = 1.04f),
        border = CardDefaults.border(border = Border.None, focusedBorder = Border.None),
        colors = CardDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface, focusedContentColor = MaterialTheme.colorScheme.onSurface)
    ) {
        Column(
            modifier = Modifier.tvGlass(focus, 18.dp).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CoverImage(
                workId = work.id,
                urls = work.orderedSources.mapNotNull { source ->
                    normalizeCoverUrl(source.vodPic) ?: normalizeCoverUrl(coverFallbacks[source.coverKey].orEmpty())
                }.distinct(),
                title = result.vodName,
                modifier = Modifier.fillMaxWidth().aspectRatio(CoverAspectRatio).clip(RoundedCornerShape(10.dp))
            )
            Text(result.vodName, style = MaterialTheme.typography.titleMedium, minLines = 2, maxLines = 2,
                modifier = Modifier.height(56.dp), overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${work.sources.size} 个资源", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary, maxLines = 1,
                    modifier = Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp))
                Text(result.vodRemarks, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Text(result.vodTime.ifBlank { result.vodYear }, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f), maxLines = 1,
                modifier = Modifier.height(18.dp), overflow = TextOverflow.Ellipsis)
        }
    }
}

private val GridContentPadding = 8.dp
private val GridVerticalPadding = 16.dp
private val CardCaptionHeight = 122.dp
private val ColumnSpacing = 16.dp
private val RowSpacing = 16.dp
private const val CoverAspectRatio = 2f / 3f

private fun derivedColumnCount(availableWidth: Dp, availableHeight: Dp): Int {
    val minWidth = if (availableHeight < 360.dp) 180.dp else 200.dp
    return floor((availableWidth + ColumnSpacing) / (minWidth + ColumnSpacing)).toInt().coerceIn(2, 4)
}

/**
 * 封面图：加载中显示占位背景，失败或无 URL 时回落到标题首字占位图。
 * 不同站点封面横竖版不一，统一 Crop 裁切到固定纵横比避免布局抖动。
 */
@Composable
private fun CoverImage(
    workId: String,
    urls: List<String>,
    title: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val ordered = urls
        var index by remember(workId, ordered) { mutableIntStateOf(0) }
        val url = ordered.getOrNull(index)
        if (url == null) {
            CoverPlaceholder(title)
        } else {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { CoverPlaceholder(title) },
                error = { CoverPlaceholder(title) },
                onError = { if (ordered.getOrNull(index) == url) index++ }
            )
        }
    }
}

@Composable
private fun CoverPlaceholder(title: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.06f),
                        Color.Black.copy(alpha = 0.18f)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title.take(1).ifEmpty { "?" },
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}
