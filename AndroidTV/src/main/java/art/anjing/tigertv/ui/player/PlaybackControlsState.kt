package art.anjing.tigertv.ui.player

/** Remote interaction state, independent of media loading and persisted playback history. */
data class SeekPreview(
    val originMillis: Long,
    val targetMillis: Long,
    val wasPlaying: Boolean,
    val keyCode: Int,
    val startedAtMillis: Long,
    val lastStepAtMillis: Long
)

data class PlaybackControlsState(
    val visible: Boolean = false,
    val speedMenuOpen: Boolean = false,
    val lastInteractionMillis: Long = 0,
    val seek: SeekPreview? = null
) {
    fun show(nowMillis: Long) = copy(visible = true, lastInteractionMillis = nowMillis)
    fun hide() = copy(visible = false, speedMenuOpen = false, seek = null)

    fun adjustSeek(
        direction: Int, positionMillis: Long, durationMillis: Long, playing: Boolean,
        keyCode: Int, nowMillis: Long, repeated: Boolean
    ): PlaybackControlsState {
        if (durationMillis <= 0) return show(nowMillis)
        val preview = seek ?: SeekPreview(positionMillis, positionMillis, playing, keyCode, nowMillis, nowMillis)
        if (repeated && nowMillis - preview.lastStepAtMillis < 120) return this
        val heldMillis = if (repeated) nowMillis - preview.startedAtMillis else 0
        val step = when {
            heldMillis >= 4_000 -> 60_000L
            heldMillis >= 1_500 -> 30_000L
            else -> 10_000L
        }
        // Leave room before the end so seeking cannot accidentally complete the episode.
        val maximum = (durationMillis - 1_000).coerceAtLeast(0)
        val target = (preview.targetMillis + direction * step).coerceIn(0, maximum)
        return show(nowMillis).copy(seek = preview.copy(targetMillis = target, keyCode = keyCode, lastStepAtMillis = nowMillis))
    }

    fun shouldAutoHide(nowMillis: Long, playing: Boolean): Boolean =
        visible && playing && seek == null && !speedMenuOpen && nowMillis - lastInteractionMillis >= 5_000
}

fun formatPlaybackTime(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1_000
    val hours = seconds / 3_600
    val minutes = seconds / 60 % 60
    val remainder = seconds % 60
    val clock = "${minutes.toString().padStart(2, '0')}:${remainder.toString().padStart(2, '0')}"
    return if (hours > 0) "$hours:$clock" else clock
}
