package art.anjing.tigertv.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import art.anjing.tigertv.R

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    GlassButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = UiSpacing.lg, vertical = 0.dp)
    ) {
        Box(Modifier.height(UiMetrics.controlHeight), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.back),
                maxLines = 1,
                style = MaterialTheme.typography.labelLarge.copy(
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)
                )
            )
        }
    }
}
