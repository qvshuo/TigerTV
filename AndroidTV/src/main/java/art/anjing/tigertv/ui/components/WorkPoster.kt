package art.anjing.tigertv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage

/** Retain the first successful cover as incremental sources arrive. */
@Composable
internal fun WorkPoster(
    workId: String,
    urls: List<String>,
    title: String,
    modifier: Modifier = Modifier,
    onShown: (String) -> Unit,
) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        var loadedUrl by rememberSaveable(workId) { mutableStateOf<String?>(null) }
        var failedUrls by remember(workId) { mutableStateOf(emptySet<String>()) }
        val url = loadedUrl ?: urls.firstOrNull { it !in failedUrls }
        var imageReady by remember(url) { mutableStateOf(false) }
        if (!imageReady) CoverPlaceholder(title)
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { imageReady = true; loadedUrl = url; onShown(url) },
                onError = {
                    imageReady = false
                    failedUrls = failedUrls + url
                    if (loadedUrl == url) loadedUrl = null
                }
            )
        }
    }
}

@Composable
private fun CoverPlaceholder(title: String) {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title.take(1).ifEmpty { "?" },
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}
