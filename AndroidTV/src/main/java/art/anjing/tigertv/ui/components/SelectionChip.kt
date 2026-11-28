package art.anjing.tigertv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ProvideTextStyle
import androidx.tv.material3.SelectableSurfaceDefaults
import androidx.tv.material3.Surface

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SelectionChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    verticalPadding: Dp = 6.dp,
    focusedScale: Float = UiMetrics.focusedScale,
    content: @Composable () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val focusBorder = Border(BorderStroke(2.dp, colors.onSurface))
    Surface(
        selected = selected, onClick = onClick, onLongClick = onLongClick, modifier = modifier, enabled = enabled,
        shape = SelectableSurfaceDefaults.shape(MaterialTheme.shapes.medium),
        colors = SelectableSurfaceDefaults.colors(
            containerColor = colors.surfaceVariant, contentColor = colors.onSurface,
            selectedContainerColor = colors.inverseSurface, selectedContentColor = colors.inverseOnSurface,
            focusedContainerColor = colors.surfaceVariant, focusedContentColor = colors.onSurface,
            focusedSelectedContainerColor = colors.inverseSurface, focusedSelectedContentColor = colors.inverseOnSurface,
        ),
        border = SelectableSurfaceDefaults.border(
            focusedBorder = focusBorder,
            focusedSelectedBorder = Border(BorderStroke(2.dp, colors.inverseOnSurface), inset = 2.dp),
        ),
        scale = SelectableSurfaceDefaults.scale(focusedScale = focusedScale),
    ) {
        Box(Modifier.heightIn(min = UiMetrics.choiceHeight).padding(horizontal = 12.dp, vertical = verticalPadding),
            contentAlignment = Alignment.Center) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge, content)
        }
    }
}
