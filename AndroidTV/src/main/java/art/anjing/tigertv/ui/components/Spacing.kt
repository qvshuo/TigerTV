package art.anjing.tigertv.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

object UiSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val choiceColumn = 12.dp
    val choiceRow = choiceColumn / 2
}

object UiMetrics {
    val pageHorizontalPadding = 48.dp
    val pageVerticalPadding = 20.dp
    val cardGridHorizontalPadding = 12.dp
    val cardGridVerticalPadding = 16.dp
    val cardGap = 16.dp
    val compactLoadingIndicator = 20.dp
    val loadingIndicator = 32.dp
    val loadingRegion = 160.dp
    val controlHeight = 56.dp
    val compactControlHeight = 48.dp
    val posterWidth = 128.dp
    val posterHeight = 192.dp
}


@Composable
internal fun ScreenTitle(title: String, modifier: Modifier = Modifier) {
    Box(modifier.heightIn(min = UiMetrics.controlHeight), contentAlignment = Alignment.CenterStart) {
        Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
