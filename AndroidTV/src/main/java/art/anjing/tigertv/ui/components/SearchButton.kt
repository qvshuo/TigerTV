package art.anjing.tigertv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import art.anjing.tigertv.R

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchButton(isSearching: Boolean, onClick: () -> Unit) {
    GlassButton(
        onClick = onClick,
        enabled = !isSearching,
        modifier = Modifier.height(UiMetrics.controlHeight).semantics {
            stateDescription = if (isSearching) "正在搜索…" else ""
        },
        contentPadding = PaddingValues(horizontal = UiSpacing.lg, vertical = 0.dp)
    ) {
        Text(stringResource(R.string.search_button), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(UiSpacing.sm))
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (isSearching) {
                LoadingIndicator(Modifier.size(UiMetrics.compactLoadingIndicator), color = LocalContentColor.current)
            } else {
                val color = LocalContentColor.current
                Canvas(Modifier.size(UiMetrics.compactLoadingIndicator)) {
                    drawPath(
                        path = Path().apply {
                            moveTo(size.width * 0.2f, size.height * 0.5f)
                            lineTo(size.width * 0.8f, size.height * 0.5f)
                            moveTo(size.width * 0.5f, size.height * 0.2f)
                            lineTo(size.width * 0.8f, size.height * 0.5f)
                            lineTo(size.width * 0.5f, size.height * 0.8f)
                        },
                        color = color,
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            }
        }
    }
}
