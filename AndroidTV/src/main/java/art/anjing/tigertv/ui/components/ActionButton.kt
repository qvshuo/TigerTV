package art.anjing.tigertv.ui.components

import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonColors
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme

@Composable
fun ActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    height: Dp = UiMetrics.controlHeight,
    colors: ButtonColors = ButtonDefaults.colors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    Button(onClick = onClick, modifier = modifier.width(IntrinsicSize.Max), onLongClick = onLongClick,
        enabled = enabled, scale = ButtonDefaults.scale(focusedScale = UiMetrics.focusedScale), colors = colors,
        contentPadding = PaddingValues(0.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = height).padding(contentPadding),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
internal fun primaryActionColors(): ButtonColors {
    val colors = MaterialTheme.colorScheme
    return ButtonDefaults.colors(
        containerColor = colors.primary, contentColor = colors.onPrimary,
        focusedContainerColor = colors.primary, focusedContentColor = colors.onPrimary,
    )
}
