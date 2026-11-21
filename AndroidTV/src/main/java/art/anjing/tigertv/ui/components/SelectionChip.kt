package art.anjing.tigertv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.FilterChipDefaults

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SelectionChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    FilterChip(selected = selected, onClick = onClick, modifier = modifier,
        scale = FilterChipDefaults.scale(focusedScale = 1f), content = content)
}
