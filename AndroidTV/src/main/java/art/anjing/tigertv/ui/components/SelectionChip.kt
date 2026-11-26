package art.anjing.tigertv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.FilterChipDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ProvideTextStyle

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SelectionChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val focusBorder = Border(BorderStroke(2.5.dp, colors.onSurface))
    FilterChip(selected = selected, onClick = onClick, modifier = modifier.onFocusChanged { focused = it.isFocused }, enabled = enabled,
        colors = FilterChipDefaults.colors(
            selectedContainerColor = colors.inverseSurface, selectedContentColor = colors.inverseOnSurface,
            focusedContainerColor = Color.Transparent, focusedContentColor = colors.onSurface,
            focusedSelectedContainerColor = colors.inverseSurface, focusedSelectedContentColor = colors.inverseOnSurface,
        ),
        border = FilterChipDefaults.border(focusedBorder = focusBorder, focusedSelectedBorder = focusBorder),
        scale = FilterChipDefaults.scale(focusedScale = 1.05f),
    ) {
        if (focused) ProvideTextStyle(TextStyle(fontWeight = FontWeight.Bold), content)
        else content()
    }
}
