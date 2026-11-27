package art.anjing.tigertv.ui.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.util.LruCache
import androidx.media3.common.Player
import art.anjing.tigertv.core.DanmakuComment
import art.anjing.tigertv.core.DanmakuPhase
import art.anjing.tigertv.core.DanmakuTrack
import art.anjing.tigertv.core.MediaTimeline
import art.anjing.tigertv.core.danmakuRenderRules

/** One draw loop; positions derive from the actual Media3 clock, never wall time. */
internal class DanmakuView(context: Context) : View(context) {
    private val render = danmakuRenderRules
    private val lifetime = render.lifetimeMs
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 20f, resources.displayMetrics)
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        strokeWidth = render.stroke * resources.displayMetrics.density
    }
    private val loadingPaint = Paint(paint).apply { textSize *= render.statusFontRatio }
    private data class Item(val comment: DanmakuComment, val text: String, val width: Float, val lane: Int)
    private val widths = LruCache<String, Float>(256)
    private val lastInLane = arrayOfNulls<Item>(render.lanes)
    private val wake = Runnable { invalidate() }
    internal var drawCount = 0L; private set
    private val active = mutableListOf<Item>()
    private var comments: List<DanmakuComment> = emptyList()
    private var track: DanmakuTrack? = null
    private var cursor = 0
    private var contentWidthForLanes = 0f
    private var lastTime = Long.MIN_VALUE
    private var loadingLabel: String? = null
    private var loadingLabelWidth = 0f
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
    fun bind(media: Player, data: DanmakuTrack?, enabled: Boolean, loading: DanmakuPhase? = null, message: String? = null, timeline: () -> MediaTimeline?) {
        var changed = false
        if (player !== media) {
            if (isAttachedToWindow) player?.removeListener(listener)
            player = media
            if (isAttachedToWindow) media.addListener(listener)
            reset()
            changed = true
        }
        if (track !== data) {
            track = data
            comments = data?.comments.orEmpty()
            reset(); changed = true
        }
        clockTimeline = timeline
        val status = (loading?.label ?: message)?.takeIf { enabled }
        if (loadingLabel != status) {
            loadingLabel = status
            loadingLabelWidth = status?.let(loadingPaint::measureText) ?: 0f
            contentDescription = status
            changed = true
        }
        val nextVisibility = if (status != null || enabled && comments.isNotEmpty()) VISIBLE else GONE
        if (visibility != nextVisibility) { visibility = nextVisibility; if (visibility == GONE) drawnCount = 0; removeCallbacks(wake); changed = true }
        if (changed) invalidate()
    }
    private fun drawOutlined(canvas: Canvas, text: String, x: Float, y: Float, color: Int, paint: Paint = this.paint) {
        paint.style = Paint.Style.STROKE
        paint.color = Color.BLACK
        canvas.drawText(text, x, y, paint)
        paint.style = Paint.Style.FILL
        paint.color = 0xFF000000.toInt() or color
        canvas.drawText(text, x, y, paint)
    }

    private fun reset() {
        active.clear(); lastInLane.fill(null); drawnCount = 0
        lastTime = Long.MIN_VALUE; cursor = 0; removeCallbacks(wake)
    }
    private fun lowerBound(time: Long): Int {
        var lo = 0; var hi = comments.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (comments[mid].timeMs < time) lo = mid + 1 else hi = mid }
        return lo
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { reset() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        removeCallbacks(wake)
        val media = player ?: return
        drawCount++
        val position = media.currentPosition.coerceAtLeast(0)
        // A filtered player's position is already effective time: never subtract ads again.
        val now = clockTimeline?.invoke()?.effectiveToReference(position) ?: position
        lastClockMs = now
        if (now < lastTime || lastTime == Long.MIN_VALUE || now - lastTime > 1_500) {
            active.clear(); lastInLane.fill(null); cursor = lowerBound(now - 2 * lifetime)
        }
        val video = media.videoSize
        val aspect = if (video.height > 0) video.width * video.pixelWidthHeightRatio / video.height else width.toFloat() / height.coerceAtLeast(1)
        val contentWidth = minOf(width.toFloat(), height * aspect)
        val contentHeight = minOf(height.toFloat(), width / aspect)
        val danmakuHeight = contentHeight * render.areaRatio
        val size = contentHeight * render.fontRatio
        if (size <= 0f) return
        if (size != paint.textSize || contentWidthForLanes != contentWidth) {
            if (size != paint.textSize) widths.evictAll()
            paint.textSize = size
            loadingPaint.textSize = size * render.statusFontRatio
            loadingLabelWidth = loadingLabel?.let(loadingPaint::measureText) ?: 0f
            contentWidthForLanes = contentWidth
            active.clear(); lastInLane.fill(null); cursor = lowerBound(now - 2 * lifetime)
        }
        val left = (width - contentWidth) / 2
        val top = (height - contentHeight) / 2
        loadingLabel?.let { label ->
            val x = (width - loadingLabelWidth) / 2
            val y = top + danmakuHeight / 2 - (loadingPaint.ascent() + loadingPaint.descent()) / 2
            drawOutlined(canvas, label, x, y, 0xFFFFFF, loadingPaint)
            return
        }
        val laneHeight = paint.textSize * render.lineHeight
        val lanes = (danmakuHeight / laneHeight).toInt().coerceIn(1, render.lanes)
        val gap = paint.textSize * render.gapEm
        while (cursor < comments.size && comments[cursor].timeMs <= now) {
            val comment = comments[cursor++]
            if (active.size >= render.visibleLimit) {
                active.removeAll { comment.timeMs - it.comment.timeMs >= lifetime }
                if (active.size >= render.visibleLimit) continue
            }
            val text = comment.text.take(100)
            val textWidth = widths.get(text) ?: paint.measureText(text).also { widths.put(text, it) }
            val lane = (0 until lanes).firstOrNull { lane ->
                val previous = lastInLane[lane]
                previous == null || render.fits(contentWidth, previous.width, textWidth, comment.timeMs - previous.comment.timeMs, gap)
            }
            if (lane == null) continue
            val item = Item(comment, text, textWidth, lane)
            active.add(item); lastInLane[lane] = item
        }
        active.removeAll { now - it.comment.timeMs >= lifetime }
        drawnCount = active.size
        val checkpoint = canvas.save()
        canvas.clipRect(left, top, left + contentWidth, top + danmakuHeight)
        for (item in active) {
            val x = left + contentWidth - (now - item.comment.timeMs).toFloat() / lifetime * (contentWidth + item.width)
            val y = top + paint.textSize * 1.3f + item.lane * laneHeight
            drawOutlined(canvas, item.text, x, y, item.comment.color)
        }
        canvas.restoreToCount(checkpoint)
        lastTime = now
        if (media.isPlaying && visibility == VISIBLE) {
            if (active.isNotEmpty()) postInvalidateOnAnimation()
            else if (cursor < comments.size) {
                val delay = ((comments[cursor].timeMs - now).coerceAtLeast(1) / media.playbackParameters.speed).toLong().coerceAtLeast(1)
                postDelayed(wake, delay)
            }
        }
    }
    fun release() { removeCallbacks(wake); player?.removeListener(listener); player = null; active.clear(); lastInLane.fill(null); comments = emptyList(); track = null; widths.evictAll() }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        player?.addListener(listener)
        reset()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(wake)
        player?.removeListener(listener)
        super.onDetachedFromWindow()
    }
}
