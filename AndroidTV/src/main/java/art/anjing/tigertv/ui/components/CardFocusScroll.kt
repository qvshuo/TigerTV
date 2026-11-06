package art.anjing.tigertv.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec

// Center focused cards so native scaling has room above and below the layout bounds.
@OptIn(ExperimentalFoundationApi::class)
internal object CardFocusScroll : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        offset - (containerSize - size).coerceAtLeast(0f) / 2f
}
