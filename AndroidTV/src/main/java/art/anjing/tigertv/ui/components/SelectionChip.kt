package art.anjing.tigertv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.FilterChipDefaults
import androidx.tv.material3.MaterialTheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SelectionChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val focusBorder = Border(BorderStroke(1.dp, colors.onSurface), shape = FilterChipDefaults.ContainerShape)
    FilterChip(selected = selected, onClick = onClick, modifier = modifier,
        colors = FilterChipDefaults.colors(
            focusedContainerColor = Color.Transparent,
            focusedContentColor = colors.onSurface,
            selectedContainerColor = colors.primary,
            selectedContentColor = colors.onPrimary,
            focusedSelectedContainerColor = colors.primary,
            focusedSelectedContentColor = colors.onPrimary),
        border = FilterChipDefaults.border(focusedBorder = focusBorder,
            selectedBorder = Border.None, focusedSelectedBorder = focusBorder),
        content = content)
}
