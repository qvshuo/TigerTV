package art.anjing.tigertv.ui.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import androidx.media3.common.Player
import art.anjing.tigertv.core.DanmakuComment
import art.anjing.tigertv.core.DanmakuTrack
import art.anjing.tigertv.core.MediaTimeline

/** One draw loop; positions derive from the actual Media3 clock, never wall time. */
internal class DanmakuView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 24f, resources.displayMetrics)
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        strokeWidth = 3 * resources.displayMetrics.density
    }
    private data class Item(val comment: DanmakuComment, val text: String, val width: Float, val lane: Int)
    private val active = mutableListOf<Item>()
    private var comments: List<DanmakuComment> = emptyList()
    private var track: DanmakuTrack? = null
    private var cursor = 0
    private var lastTime = Long.MIN_VALUE
    private var offset = 0L
    private var clockTimeline: (() -> MediaTimeline?)? = null
    private var player: Player? = null
    internal val commentCount: Int get() = comments.size
    internal var drawnCount = 0
        private set
    internal var lastClockMs = 0L
        private set
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { invalidate() }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            reset(); invalidate()
        }
    }
    fun bind(media: Player, data: DanmakuTrack?, enabled: Boolean, offsetMs: Long, timeline: () -> MediaTimeline?) {
        if (player !== media) { player?.removeListener(listener); player = media; media.addListener(listener); reset() }
        if (track !== data) {
            track = data
            comments = data?.comments.orEmpty().filter { it.mode == 1 }.sortedBy { it.timeMs }
            reset()
        }
        if (offset != offsetMs) { offset = offsetMs; reset() }
        clockTimeline = timeline
        visibility = if (enabled && data != null) VISIBLE else GONE
        invalidate()
    }
    private fun reset() { active.clear(); lastTime = Long.MIN_VALUE; cursor = 0 }
    private fun lowerBound(time: Long): Int {
        var lo = 0; var hi = comments.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (comments[mid].timeMs < time) lo = mid + 1 else hi = mid }
        return lo
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { reset() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val media = player ?: return
        val position = media.currentPosition.coerceAtLeast(0)
        // A filtered player's position is already effective time: never subtract ads again.
        val now = (clockTimeline?.invoke()?.effectiveToReference(position) ?: position) + offset
        lastClockMs = now
        if (now < lastTime || lastTime == Long.MIN_VALUE || now - lastTime > 1_500) {
            active.clear(); cursor = lowerBound(now - LIFETIME)
        }
        val video = media.videoSize
        val aspect = if (video.height > 0) video.width * video.pixelWidthHeightRatio / video.height else width.toFloat() / height.coerceAtLeast(1)
        val contentWidth = minOf(width.toFloat(), height * aspect)
        val contentHeight = minOf(height.toFloat(), width / aspect)
        val left = (width - contentWidth) / 2
        val top = (height - contentHeight) / 2
        val laneHeight = paint.textSize * 1.5f
        val lanes = (contentHeight * 0.25f / laneHeight).toInt().coerceIn(1, 4)
        val gap = paint.textSize
        while (cursor < comments.size && comments[cursor].timeMs <= now) {
            val comment = comments[cursor++]
            active.removeAll { comment.timeMs - it.comment.timeMs >= LIFETIME }
            if (active.size >= 12) continue
            val text = comment.text.take(100)
            val textWidth = paint.measureText(text)
            val lane = (0 until lanes).firstOrNull { lane ->
                active.filter { it.lane == lane }.all { previous ->
                    val elapsed = comment.timeMs - previous.comment.timeMs
                    val previousRight = contentWidth - elapsed.toFloat() / LIFETIME * (contentWidth + previous.width) + previous.width
                    val newLeftAtPreviousEnd = contentWidth - (LIFETIME - elapsed).toFloat() / LIFETIME * (contentWidth + textWidth)
                    previousRight < contentWidth - gap && newLeftAtPreviousEnd >= gap
                }
            } ?: continue
            active.add(Item(comment, text, textWidth, lane))
        }
        active.removeAll { now - it.comment.timeMs >= LIFETIME }
        drawnCount = active.size
        val checkpoint = canvas.save()
        canvas.clipRect(left, top, left + contentWidth, top + contentHeight * 0.25f)
        for (item in active) {
            val x = left + contentWidth - (now - item.comment.timeMs).toFloat() / LIFETIME * (contentWidth + item.width)
            val y = top + paint.textSize * 1.3f + item.lane * laneHeight
            paint.style = Paint.Style.STROKE; paint.color = Color.BLACK
            canvas.drawText(item.text, x, y, paint)
            paint.style = Paint.Style.FILL; paint.color = 0xFF000000.toInt() or item.comment.color
            canvas.drawText(item.text, x, y, paint)
        }
        canvas.restoreToCount(checkpoint)
        lastTime = now
        // Limit text repaints to about 30 fps; position still comes from the media clock.
        if (media.isPlaying && visibility == VISIBLE && comments.isNotEmpty()) postInvalidateDelayed(32)
    }
    fun release() { player?.removeListener(listener); player = null; active.clear(); comments = emptyList(); track = null }
    override fun onDetachedFromWindow() { release(); super.onDetachedFromWindow() }
    private companion object { const val LIFETIME = 8_000L }
}
