package art.anjing.tigertv.ui.results

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.SubcomposeAsyncImage
import art.anjing.tigertv.R
import art.anjing.tigertv.domain.SearchResult
import art.anjing.tigertv.ui.components.ErrorMessage
import art.anjing.tigertv.ui.components.LoadingOverlay
import art.anjing.tigertv.ui.components.TvCardShape
import art.anjing.tigertv.ui.components.tvCardFocusedBorder
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
                Text(
                    text = "${stringResource(R.string.results_title)}: ${viewModel.submittedKeyword}",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(24.dp))

            when {
                viewModel.isSearching -> LoadingOverlay()
                viewModel.searchErrorMessage != null -> ErrorMessage(
                    message = viewModel.searchErrorMessage ?: stringResource(R.string.error_generic),
                    onRetry = {
                        viewModel.clearSearchError()
                        viewModel.search()
                    }
                )
                viewModel.results.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.error_no_results),
                        style = MaterialTheme.typography.displayMedium
                    )
                }
                else -> ResultsGrid(
                    results = viewModel.results,
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
    results: List<SearchResult>,
    coverFallbacks: Map<String, String>,
    onLoadCoverFallback: (SearchResult) -> Unit,
    onSelectResult: (SearchResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstCardFocusRequester = remember { FocusRequester() }

    // 每当 results 变更（新搜索 / 重入屏导致 List 实例变化）即恢复首项焦点：
    // 去掉一次性 `focused` 标志——其在二次搜索时不会复位，导致焦点丢失。
    LaunchedEffect(results) {
        if (results.isNotEmpty()) {
            requestFocusSafely(firstCardFocusRequester)
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 竖版海报 2:3，列数必须同时受宽度和高度约束：
        // 只按宽度自适应会让一行 4 张的海报高度超过电视垂直显示范围。
        LazyVerticalGrid(
            columns = GridCells.Fixed(derivedColumnCount(maxWidth, maxHeight)),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(GridContentPadding),
            horizontalArrangement = Arrangement.spacedBy(ColumnSpacing),
            verticalArrangement = Arrangement.spacedBy(RowSpacing)
        ) {
            itemsIndexed(results) { index, result ->
                val focusModifier = if (index == 0) {
                    Modifier.focusRequester(firstCardFocusRequester)
                } else {
                    Modifier
                }
                // 仅空封面卡片进入组合（即可见）时触发 detail 兜底请求；已取到兜底则跳过。
                LaunchedEffect(result.site, result.vodId) {
                    if (result.vodPic.isBlank() && coverFallbacks[result.coverKey] == null) {
                        onLoadCoverFallback(result)
                    }
                }
                Card(
                    onClick = { onSelectResult(result) },
                    modifier = focusModifier
                        .fillMaxWidth(),
                    shape = CardDefaults.shape(shape = TvCardShape),
                    border = tvCardFocusedBorder(),
                    colors = CardDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column {
                        CoverImage(
                            url = result.vodPic.ifBlank { coverFallbacks[result.coverKey].orEmpty() },
                            title = result.vodName,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(CoverAspectRatio)
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = result.vodName,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = result.site,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (result.vodRemarks.isNotEmpty()) {
                                    Text(
                                        text = result.vodRemarks,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 卡片文字区（标题 + 站点/remarks 行 + 内边距）的高度预算。 */
private val TextBlockHeight = 84.dp
private val GridContentPadding = 8.dp
private val ColumnSpacing = 16.dp
private val RowSpacing = 16.dp

/** 海报宽高比（width/height），与三端统一的 2:3 竖版封面一致。 */
private const val CoverAspectRatio = 2f / 3f

/** 目标一屏完整可见的行数：既不浪费纵向空间，也保证卡片不被裁切。 */
private const val TargetVisibleRows = 2

private const val MinColumns = 2
private const val MaxColumns = 6

/**
 * 列数由可用宽高共同推导：按 [TargetVisibleRows] 行反推每张卡片的最大宽度
 * （(行高预算 - 文字区) × 2/3），再算出该宽度下能放几列。
 * 屏幕越高列数越多，任何分辨率下每张海报卡都完整可见、不超出垂直范围。
 */
private fun derivedColumnCount(availableWidth: Dp, availableHeight: Dp): Int {
    val rowBudget = (
        availableHeight -
            GridContentPadding * 2 -
            RowSpacing * (TargetVisibleRows - 1)
        ) / TargetVisibleRows
    val maxCardWidthFromHeight =
        ((rowBudget - TextBlockHeight) * CoverAspectRatio).coerceAtLeast(120.dp)
    val columns = floor(
        (availableWidth - GridContentPadding * 2 + ColumnSpacing) /
            (maxCardWidthFromHeight + ColumnSpacing)
    ).toInt()
    return columns.coerceIn(MinColumns, MaxColumns)
}

/**
 * 封面图：加载中显示占位背景，失败或无 URL 时回落到标题首字占位图。
 * 不同站点封面横竖版不一，统一 Crop 裁切到固定纵横比避免布局抖动。
 */
@Composable
private fun CoverImage(
    url: String,
    title: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        // 非 ASCII 路径必须百分号编码后再交给 Coil（与 macOS percentEncodedURL 对齐），
        // 否则部分站点中文路径封面静默 404。
        val resolvedUrl = normalizeCoverUrl(url)
        if (resolvedUrl == null) {
            CoverPlaceholder(title)
        } else {
            SubcomposeAsyncImage(
                model = resolvedUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { CoverPlaceholder(title) },
                error = { CoverPlaceholder(title) }
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
