package art.anjing.tigertv.ui

import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.yield

/**
 * Requests focus on [focusRequester] once the current coroutine yields, so the
 * composable has a chance to be laid out. Safe to call from a [LaunchedEffect];
 * swallows the [IllegalStateException] that FocusRequester can throw if the target
 * is not attached yet.
 */
suspend fun requestFocusSafely(focusRequester: FocusRequester) {
    yield()
    runCatching { focusRequester.requestFocus() }
}