package art.anjing.tigertv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    focusOnly: Boolean = false,
    radius: Dp = 16.dp,
    height: Dp = UiMetrics.controlHeight,
    contentPadding: PaddingValues = PaddingValues(horizontal = UiSpacing.lg, vertical = UiSpacing.sm),
    content: @Composable RowScope.() -> Unit
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val focus = animateFloatAsState(if (focused && enabled) 1f else 0f, tween(UiMotion.durationMillis), label = "glass focus")
    Button(
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
        modifier = modifier.height(height).tvGlass(focus, radius, focusOnly),
        interactionSource = interactions,
        shape = ButtonDefaults.shape(RoundedCornerShape(radius)),
        scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f, focusedDisabledScale = 1f),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent, focusedContainerColor = Color.Transparent,
            pressedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
            contentColor = if (focusOnly) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            focusedContentColor = MaterialTheme.colorScheme.onSurface,
            pressedContentColor = MaterialTheme.colorScheme.onSurface,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        ),
        border = ButtonDefaults.border(border = Border.None, focusedBorder = Border.None,
            pressedBorder = Border.None, disabledBorder = Border.None, focusedDisabledBorder = Border.None),
        contentPadding = PaddingValues(0.dp)
    ) {
        Row(Modifier.height(height).padding(contentPadding),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically, content = content)
    }
}
