package art.anjing.tigertv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import art.anjing.tigertv.domain.HotSearchItem
import art.anjing.tigertv.ui.components.MediaCard
import art.anjing.tigertv.ui.components.MediaCardDetails
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

@Composable
internal fun HotSearchGrid(items: List<HotSearchItem>, modifier: Modifier = Modifier, onSearch: (String) -> Unit) {
    val context = LocalContext.current.applicationContext
    var loadedRows by remember(items) { mutableIntStateOf(0) }
    val rows = remember(items) { items.chunked(5) }
    val requests = remember(items, context) {
        items.associate { item -> item.id to ImageRequest.Builder(context)
            .data(item.poster)
            .size(320, 480)
            .httpHeaders(NetworkHeaders.Builder().set("Referer", "https://m.douban.com/").build())
            .build()
        }
    }
    // Finish each poster row before starting the next; leaving home cancels this effect.
    LaunchedEffect(items) {
        for ((index, row) in rows.withIndex()) {
            coroutineScope {
                row.map { item -> async { SingletonImageLoader.get(context).execute(requests.getValue(item.id)) } }.awaitAll()
            }
            loadedRows = index + 1
        }
    }
    Column(modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        rows.forEachIndexed { index, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { item ->
                    MediaCard(onClick = { onSearch(item.title) }, modifier = Modifier.weight(1f)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                            Text(item.title.take(1), style = MaterialTheme.typography.displayMedium)
                            if (index < loadedRows) AsyncImage(model = requests.getValue(item.id), contentDescription = item.title,
                                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                        MediaCardDetails(
                            title = if (item.year.isBlank()) item.title else "${item.title} (${item.year})",
                            footer = item.status
                        ) {
                            Text(item.rating?.let { "豆瓣 %.1f · %d人".format(it, item.ratingCount) } ?: "暂无评分",
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
