package art.anjing.tigertv.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Typography

private fun tvTextStyle(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)
)

val Typography = Typography(
    displayLarge = tvTextStyle(46, 56, FontWeight.Bold),
    displayMedium = tvTextStyle(32, 40, FontWeight.Bold),
    headlineLarge = tvTextStyle(24, 32, FontWeight.SemiBold),
    titleMedium = tvTextStyle(18, 24, FontWeight.SemiBold),
    bodyLarge = tvTextStyle(16, 24),
    bodyMedium = tvTextStyle(14, 20),
    bodySmall = tvTextStyle(12, 18),
    labelLarge = tvTextStyle(14, 20, FontWeight.Medium)
)
