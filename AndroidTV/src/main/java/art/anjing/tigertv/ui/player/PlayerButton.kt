package art.anjing.tigertv.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import art.anjing.tigertv.ui.components.UiMetrics
import art.anjing.tigertv.ui.components.UiMotion
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults

/** Neutral translucent surfaces, with a larger white primary playback control. */
@Composable
fun PlayerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = UiMetrics.controlHeight,
    compact: Boolean = false,
    prominent: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    content: @Composable RowScope.() -> Unit
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val focus = animateFloatAsState(if (focused && enabled) 1f else 0f, tween(UiMotion.durationMillis), label = "player focus")
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(height).drawWithCache {
            // Smaller artwork keeps the full 56dp remote target and stable focus geometry.
            val inset = if (compact) 6.dp.toPx() else 2.dp.toPx()
            val surface = Size((size.width - inset * 2).coerceAtLeast(0f), (size.height - inset * 2).coerceAtLeast(0f))
            val corner = CornerRadius(surface.height / 2)
            val sheen = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.025f)))
            onDrawBehind {
                val strength = focus.value
                val base = if (prominent) Color.White.copy(alpha = 0.9f) else Color(0xFF202024).copy(alpha = 0.55f)
                val surfaceColor = lerp(base, Color.White, strength)
                drawRoundRect(if (enabled) surfaceColor else surfaceColor.copy(alpha = 0.24f),
                    Offset(inset, inset), surface, corner)
                if (!prominent) drawRoundRect(sheen, Offset(inset, inset), surface, corner, alpha = 1f - strength)
                drawRoundRect(Color.White.copy(alpha = if (enabled) 0.18f + strength * 0.5f else 0.06f),
                    Offset(inset, inset), surface, corner, style = Stroke(1.dp.toPx()))
                if (prominent && strength > 0f) {
                    drawRoundRect(Color.White.copy(alpha = strength * 0.65f), Offset.Zero, size,
                        CornerRadius(size.height / 2), style = Stroke(1.5.dp.toPx()))
                }
            }
        },
        interactionSource = interactions,
        shape = ButtonDefaults.shape(RoundedCornerShape(height / 2)),
        scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f, focusedDisabledScale = 1f),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent, focusedContainerColor = Color.Transparent,
            pressedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
            contentColor = if (prominent) Color(0xFF151518) else Color.White,
            focusedContentColor = Color(0xFF151518), pressedContentColor = Color(0xFF151518),
            disabledContentColor = Color.White.copy(alpha = 0.3f)
        ),
        border = ButtonDefaults.border(border = Border.None, focusedBorder = Border.None,
            pressedBorder = Border.None, disabledBorder = Border.None, focusedDisabledBorder = Border.None),
        contentPadding = PaddingValues(0.dp)
    ) {
        Row(Modifier.fillMaxWidth().height(height).padding(contentPadding), horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically, content = content)
    }
}
