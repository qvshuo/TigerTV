package art.anjing.tigertv.ui.components

import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Interlude-style cached sheen and rim; focus updates only redraw the surface. */
fun Modifier.tvGlass(focus: State<Float>? = null, radius: Dp = 24.dp): Modifier = graphicsLayer {
    shape = RoundedCornerShape(radius)
    clip = true
}.drawWithCache {
    val corner = CornerRadius(radius.toPx())
    val rim = Brush.linearGradient(listOf(
        Color.White.copy(alpha = 0.42f), Color.White.copy(alpha = 0.07f),
        Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.24f)
    ))
    val sheen = Brush.linearGradient(
        0f to Color.White.copy(alpha = 0.12f),
        0.45f to Color.Transparent,
        0.8f to Color.Black.copy(alpha = 0.025f),
        1f to Color.White.copy(alpha = 0.035f),
        end = Offset(size.width * 0.5f, size.height)
    )
    val inset = 1.dp.toPx()
    val rimSize = Size((size.width - inset * 2).coerceAtLeast(0f), (size.height - inset * 2).coerceAtLeast(0f))
    onDrawWithContent {
        val strength = focus?.value ?: 0f
        drawRoundRect(Color(0xFF151A23).copy(alpha = 0.72f + strength * 0.1f), cornerRadius = corner)
        drawContent()
        drawRoundRect(sheen, cornerRadius = corner, alpha = 0.6f + strength * 0.4f)
        drawRoundRect(rim, topLeft = Offset(inset, inset), size = rimSize, cornerRadius = corner,
            style = Stroke(1.dp.toPx()), alpha = 1f - strength)
        if (strength > 0f) {
            drawRoundRect(Color.White, topLeft = Offset(inset, inset), size = rimSize,
                cornerRadius = corner, style = Stroke(2.dp.toPx()), alpha = strength * 0.85f)
        }
    }
}

fun Modifier.tvGlassBackdrop(): Modifier = drawWithCache {
    val glow = Brush.radialGradient(
        listOf(Color(0xFF2C415D).copy(alpha = 0.65f), Color.Transparent),
        center = Offset(size.width * 0.12f, size.height * 0.05f), radius = size.width * 0.85f
    )
    val reflection = Brush.radialGradient(
        listOf(Color(0xFF36516E).copy(alpha = 0.25f), Color.Transparent),
        center = Offset(size.width * 0.95f, size.height), radius = size.width * 0.65f
    )
    onDrawBehind {
        drawRect(Color(0xFF0E1117))
        drawRect(glow)
        drawRect(reflection)
    }
}
