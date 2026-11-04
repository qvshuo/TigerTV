package art.anjing.tigertv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val Content = Color(0xFFF0F0F4)
private val TvColors = darkColorScheme(
    primary = Content, onPrimary = Color(0xFF1C1C22),
    primaryContainer = Color(0xFF36363E), onPrimaryContainer = Content,
    inversePrimary = Color(0xFF62626E),
    secondary = Color(0xFFC6C6D0), onSecondary = Color(0xFF26262E),
    secondaryContainer = Color(0xFF303038), onSecondaryContainer = Content,
    tertiary = Color(0xFFCCCCD4), onTertiary = Color(0xFF282830),
    tertiaryContainer = Color(0xFF383840), onTertiaryContainer = Content,
    background = Color(0xFF141416), onBackground = Content,
    surface = Color(0xFF141416), onSurface = Content,
    surfaceVariant = Color(0xFF28282E), onSurfaceVariant = Color(0xFFC4C4CC),
    inverseSurface = Content, inverseOnSurface = Color(0xFF202024),
    border = Color(0xFF8C8C96), borderVariant = Color(0xFF44444C)
)

@Composable
fun TigerTVTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TvColors, content = content)
}
