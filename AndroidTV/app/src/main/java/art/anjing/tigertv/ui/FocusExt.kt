package art.anjing.tigertv.ui

import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.delay

/**
 * 请求焦点，并在 FocusRequester 尚未附着（目标尚未组合/布局完成）时自动重试。
 *
 * 关键修复：旧实现只 `yield()` 一次就 `requestFocus()`。在 TV 的 LazyVerticalGrid 里，
 * 首屏卡片是惰性组合的——`yield()` 一帧后首项 composable 往往还没完成布局，
 * FocusRequester 没附着，`requestFocus()` 抛 IllegalStateException 被 runCatching 吞掉，
 * 结果没有任何卡片获得焦点，D-pad 上下左右自然"失效"（方向导航必须有焦点锚点）。
 *
 * 这里改为短间隔轮询重试，给惰性列表足够时间完成首项组合与附着；最多重试 8 次（约 240ms）。
 */
suspend fun requestFocusSafely(focusRequester: FocusRequester) {
    repeat(MAX_ATTEMPTS) {
        val attached = runCatching { focusRequester.requestFocus() }.isSuccess
        if (attached) return
        delay(RETRY_INTERVAL_MILLIS)
    }
}

private const val MAX_ATTEMPTS = 8
private const val RETRY_INTERVAL_MILLIS = 30L