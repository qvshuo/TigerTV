package art.anjing.tigertv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val GlassColors = darkColorScheme(
    primary = Color(0xFF78B7FF), onPrimary = Color(0xFF071727),
    secondary = Color(0xFFB8C7DA), tertiary = Color(0xFF94BDE5),
    background = Color(0xFF101217), surface = Color(0xFF191D25),
    surfaceVariant = Color(0xFF252B35), onSurface = Color(0xFFF3F5FA),
    onSurfaceVariant = Color(0xFFBCC4D2)
)

@Composable
fun TigerTVTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = GlassColors, typography = Typography, content = content)
}
