package art.anjing.tigertv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
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
import art.anjing.tigertv.ui.requestFocusSafely
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest

@Composable
internal fun HotSearchGrid(
    items: List<HotSearchItem>, modifier: Modifier = Modifier,
    restoreCardId: String?, cardFocus: FocusRequester, onFocusRestored: () -> Unit,
    onSearch: (HotSearchItem) -> Unit,
) {
    LaunchedEffect(restoreCardId) {
        if (restoreCardId != null && items.any { it.id == restoreCardId }) {
            requestFocusSafely(cardFocus)
            onFocusRestored()
        }
    }
    val context = LocalContext.current.applicationContext
    val rows = remember(items) { items.chunked(5) }
    val requests = remember(items, context) {
        items.associate { item -> item.id to ImageRequest.Builder(context)
            .data(normalizeCoverUrl(item.poster))
            .size(320, 480)
            .httpHeaders(NetworkHeaders.Builder().set("Referer", "https://m.douban.com/").build())
            .build()
        }
    }
    Column(modifier.fillMaxWidth().padding(horizontal = UiMetrics.cardGridHorizontalPadding)
        .padding(top = 16.dp, bottom = UiMetrics.cardGridVerticalPadding), verticalArrangement = Arrangement.spacedBy(UiMetrics.cardGap)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(UiMetrics.cardGap)) {
                row.forEach { item ->
                    MediaCard(onClick = { onSearch(item) }, modifier = Modifier.weight(1f)
                        .then(if (item.id == restoreCardId) Modifier.focusRequester(cardFocus) else Modifier)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                            Text(item.title.take(1), style = MaterialTheme.typography.displayMedium)
                            AsyncImage(model = requests.getValue(item.id), contentDescription = null,
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
}
