package art.anjing.tigertv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.remember
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import art.anjing.tigertv.data.normalizeCoverUrl
import art.anjing.tigertv.domain.HotSearchItem
import art.anjing.tigertv.ui.components.MediaCard
import art.anjing.tigertv.ui.components.UiMetrics
import art.anjing.tigertv.ui.components.MediaCardDetails
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest

internal fun LazyListScope.hotSearchRows(
    titles: List<HotSearchItem>, restoreCardId: String?, cardFocus: FocusRequester,
    onSearch: (HotSearchItem) -> Unit,
) {
    items(titles.chunked(5), key = { "hot-${it.first().id}" }) { row ->
        val context = LocalContext.current.applicationContext
        Row(Modifier.fillMaxWidth().padding(horizontal = UiMetrics.cardGridHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(UiMetrics.cardGap)) {
            row.forEach { item ->
                val request = remember(item.poster, context) {
                    ImageRequest.Builder(context).data(normalizeCoverUrl(item.poster)).size(320, 480)
                        .httpHeaders(NetworkHeaders.Builder().set("Referer", "https://m.douban.com/").build()).build()
                }
                MediaCard(onClick = { onSearch(item) }, modifier = Modifier.weight(1f)
                    .then(if (item.id == restoreCardId) Modifier.focusRequester(cardFocus) else Modifier)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                        Text(item.title.take(1), style = MaterialTheme.typography.displayMedium)
                        AsyncImage(model = request, contentDescription = null,
                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    MediaCardDetails(
                        title = if (item.year.isBlank()) item.title else "${item.title} (${item.year})",
                        footer = item.status
                    ) {
                        Text(item.rating?.let { "评分 %.1f · %d人".format(it, item.ratingCount) } ?: "暂无评分",
                            style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}
