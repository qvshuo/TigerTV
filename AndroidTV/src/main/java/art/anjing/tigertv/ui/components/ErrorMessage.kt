package art.anjing.tigertv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import art.anjing.tigertv.ui.requestFocusSafely
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import art.anjing.tigertv.R

@Composable
fun ErrorMessage(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    compact: Boolean = false,
    focusRetry: Boolean = true
) {
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(message) { if (focusRetry && onRetry != null) requestFocusSafely(retryFocus) }
    val retry: @Composable () -> Unit = {
        if (onRetry != null) {
            GlassButton(onClick = onRetry, modifier = Modifier.focusRequester(retryFocus)) {
                Text(stringResource(R.string.retry))
            }
        }
    }
    if (compact) {
        Row(modifier.fillMaxWidth().height(UiMetrics.controlHeight + UiSpacing.sm * 2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(UiSpacing.md)) {
            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error)
            retry()
        }
    } else {
        Column(modifier = modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
            if (onRetry != null) Spacer(Modifier.height(UiSpacing.md))
            retry()
        }
    }
}
