package art.anjing.tigertv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import art.anjing.tigertv.ui.components.WorkPoster
import art.anjing.tigertv.ui.components.UiMetrics
import art.anjing.tigertv.ui.requestFocusSafely

@Composable
internal fun RecentPlaybackRow(
    items: List<RecentPlayback>,
    workCover: (String) -> String?,
    onCoverShown: (String, String) -> Unit,
    restoreId: String?,
    onFocusRestored: () -> Unit,
    onSelect: (RecentPlayback) -> Unit,
) {
    val visible = items.take(3)
    val target = remember { FocusRequester() }
    LaunchedEffect(restoreId, visible) {
        val index = visible.indexOfFirst { it.workId == restoreId }
        if (restoreId == null) return@LaunchedEffect
        if (index >= 0) requestFocusSafely(target)
        onFocusRestored()
    }

    Text("继续观看", style = MaterialTheme.typography.titleLarge)
    Row(Modifier.fillMaxWidth().padding(horizontal = UiMetrics.cardGridHorizontalPadding, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        visible.forEach { item ->
            key(item.workId) {
                MediaCard(onClick = { onSelect(item) }, modifier = Modifier.weight(1f)
                    .then(if (restoreId == item.workId) Modifier.focusRequester(target) else Modifier)
                    .testTag("recent-card")) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        WorkPoster(item.workId, listOfNotNull(workCover(item.workId), normalizeCoverUrl(item.cardSource.vodPic),
                            normalizeCoverUrl(item.source.vodPic)).distinct(), item.cardSource.vodName,
                            Modifier.width(44.dp).aspectRatio(2f / 3f).clip(MaterialTheme.shapes.small),
                            onShown = { onCoverShown(item.workId, it) })
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.cardSource.vodName.ifBlank { "未命名作品" }, style = MaterialTheme.typography.titleMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(item.bookmark.episode.ifBlank { "第 ${(item.bookmark.index ?: 0) + 1} 集" },
                                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    run {
                        val percent = item.progressPercent ?: 0
                        Box(Modifier.fillMaxWidth().padding(top = 2.dp).height(3.dp)
                            .clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant)
                            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(percent.toFloat(), 0f..100f) }) {
                            Box(Modifier.fillMaxWidth(percent / 100f).height(3.dp).background(MaterialTheme.colorScheme.primary))
                        }
                    }
                }
            }
        }
        repeat(3 - visible.size) { Spacer(Modifier.weight(1f)) }
    }
}
