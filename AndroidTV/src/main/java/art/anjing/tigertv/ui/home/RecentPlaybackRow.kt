package art.anjing.tigertv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import art.anjing.tigertv.data.normalizeCoverUrl
import art.anjing.tigertv.domain.RecentPlayback
import art.anjing.tigertv.ui.components.MediaCard
import art.anjing.tigertv.ui.components.UiMetrics
import art.anjing.tigertv.ui.requestFocusSafely
import coil3.compose.AsyncImage

@Composable
internal fun RecentPlaybackRow(
    items: List<RecentPlayback>,
    restoreId: String?,
    onFocusRestored: () -> Unit,
    onSelect: (RecentPlayback) -> Unit,
) {
    val row = rememberLazyListState()
    val target = remember { FocusRequester() }
    LaunchedEffect(restoreId, items) {
        val index = items.indexOfFirst { it.workId == restoreId }
        if (index < 0) return@LaunchedEffect
        if (row.layoutInfo.visibleItemsInfo.none { it.key == restoreId }) row.scrollToItem(index)
        requestFocusSafely(target)
        onFocusRestored()
    }

    Text("最近播放", style = MaterialTheme.typography.titleLarge)
    LazyRow(state = row, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(horizontal = UiMetrics.cardGridHorizontalPadding, vertical = 16.dp)) {
        items(items, key = { it.workId }) { item ->
            MediaCard(onClick = { onSelect(item) }, modifier = Modifier.width(226.dp)
                .then(if (restoreId == item.workId) Modifier.focusRequester(target) else Modifier)
                .testTag("recent-card")) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.width(44.dp).aspectRatio(2f / 3f).clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                        Text(item.source.vodName.take(1), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        AsyncImage(model = normalizeCoverUrl(item.source.vodPic), contentDescription = null,
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.source.vodName.ifBlank { "未命名作品" }, style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val progress = item.progressPercent?.takeIf { it > 0 }?.let { "已看 $it%" }
                            ?: item.positionMillis.takeIf { it >= 1_000 }?.let {
                                val seconds = it / 1_000
                                val minutes = seconds / 60
                                "已看 $minutes:${(seconds % 60).toString().padStart(2, '0')}"
                            }
                        Text(listOfNotNull(item.bookmark.episode.takeIf { it.isNotBlank() }, progress)
                            .joinToString(" · ").ifBlank { "继续播放" },
                            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                item.progressPercent?.let { percent ->
                    Box(Modifier.fillMaxWidth().padding(top = 2.dp).height(3.dp)
                        .clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant)
                        .semantics { progressBarRangeInfo = ProgressBarRangeInfo(percent.toFloat(), 0f..100f) }) {
                        Box(Modifier.fillMaxWidth(percent / 100f).height(3.dp).background(MaterialTheme.colorScheme.primary))
                    }
                }
            }
        }
    }
}
