package art.anjing.tigertv.ui

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import kotlinx.coroutines.delay

/** Lazy controls may not be attached yet; retry without blocking the UI thread. */
suspend fun requestFocusSafely(focusRequester: FocusRequester) {
    repeat(MAX_ATTEMPTS) {
        val focused = runCatching { focusRequester.requestFocus(FocusDirection.Enter) }.getOrDefault(false)
        if (focused) return
        delay(RETRY_INTERVAL_MILLIS)
    }
}

private const val MAX_ATTEMPTS = 8
private const val RETRY_INTERVAL_MILLIS = 30L
