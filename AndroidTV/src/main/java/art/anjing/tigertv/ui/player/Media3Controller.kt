package art.anjing.tigertv.ui.player

import art.anjing.tigertv.core.productDefaults

import android.content.Context
import androidx.core.net.toUri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.session.MediaSession
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import art.anjing.tigertv.core.USER_AGENT
import art.anjing.tigertv.core.normalizeUrl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.source.MediaLoadData

data class PlayerSnapshot(
    val positionMillis: Long = 0,
    val durationMillis: Long = 0,
    val bufferedMillis: Long = 0,
    val playWhenReady: Boolean = false,
    val ready: Boolean = false,
    val buffering: Boolean = false,
    val ended: Boolean = false,
    val seekable: Boolean = false,
    val speed: Float = 1f
)

@androidx.annotation.OptIn(UnstableApi::class)
class Media3Controller(context: Context) {
    private interface PlaybackListener : Player.Listener, AnalyticsListener

    private val appContext = context.applicationContext
    @Volatile private var hlsSession: HlsPlaybackSession? = null
    private val upstream = DefaultDataSource.Factory(appContext, DefaultHttpDataSource.Factory().setUserAgent(USER_AGENT))
    private val dataSource = androidx.media3.datasource.DataSource.Factory { HlsAdDataSource(upstream.createDataSource(), hlsSession) }
    val player = ExoPlayer.Builder(appContext)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
        .build()
    private val mediaSession = MediaSession.Builder(appContext, player).setId(java.util.UUID.randomUUID().toString()).build()
    val playbackTimeline: art.anjing.tigertv.core.MediaTimeline? get() = hlsSession?.timeline

    var snapshot by mutableStateOf(PlayerSnapshot())
        private set
    var hasVideoFrame by mutableStateOf(false)
        private set
    private var resumeOnForeground = false
    private var inBackground = false
    private var pendingLoad: (() -> Unit)? = null
    private var listener: PlaybackListener? = null
    private var onPosition: ((Long) -> Unit)? = null
    private var lastSavedPosition: Long? = null
    private var didStart = false
    private var restoring = false
    private var ended = false
    private var closed = false

    fun load(url: String, positionMillis: Long, title: String, onStarted: () -> Unit, onPosition: (Long) -> Unit, onError: (String) -> Unit, filterAds: Boolean = productDefaults.filterAds, speed: Float = 1f) {
        if (closed) return
        unload()
        if (inBackground) {
            pendingLoad = { load(url, positionMillis, title, onStarted, onPosition, onError, filterAds, speed) }
            return
        }
        val uri = normalizeUrl(url).toUri()
        val valid = when (uri.scheme?.lowercase()) {
            "http", "https" -> !uri.host.isNullOrBlank()
            "file" -> uri.path?.startsWith("/") == true
            else -> false
        }
        if (!valid) {
            onError("无效播放地址")
            return
        }
        val mayFilter = filterAds && mayFilterHls(uri)
        val currentSession = HlsPlaybackSession(uri, filterAds)
        player.setPlaybackSpeed(speed)
        hlsSession = currentSession
        this.onPosition = onPosition
        restoring = true
        listener = object : PlaybackListener {
            override fun onEvents(player: Player, events: Player.Events) { if (listener === this) refresh() }

            override fun onDownstreamFormatChanged(eventTime: AnalyticsListener.EventTime, mediaLoadData: MediaLoadData) {
                if (listener !== this || mediaLoadData.trackType == C.TRACK_TYPE_AUDIO) return
                val manifest = player.currentManifest as? HlsManifest ?: return
                val variant = manifest.multivariantPlaylist.variants.firstOrNull { it.format == mediaLoadData.trackFormat }
                if (variant != null) currentSession.selectVideoVariant(variant.url)
            }

            override fun onRenderedFirstFrame() {
                if (listener === this) hasVideoFrame = true
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (listener !== this) return
                if ((state == Player.STATE_READY || state == Player.STATE_ENDED) && restoring) {
                    val duration = player.duration
                    if (mayFilter) {
                        val effective = currentSession.timeline?.videoToEffective(positionMillis)?.timeMs ?: positionMillis
                        if (effective > 0) player.seekTo(if (duration != C.TIME_UNSET && effective >= duration) 0 else effective)
                    } else if (duration != C.TIME_UNSET && positionMillis >= duration) player.seekTo(0)
                    restoring = false
                }
                if (state == Player.STATE_ENDED && didStart && !ended) {
                    ended = true
                    lastSavedPosition = 0
                    onPosition(0)
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (listener !== this) return
                if (isPlaying && !restoring && !didStart) {
                    didStart = true
                    onStarted()
                } else if (!isPlaying) savePosition()
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (listener !== this) return
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    ended = false
                    savePosition()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                if (listener !== this) return
                savePosition()
                onError(error.message ?: "播放失败")
            }
        }.also {
            player.addListener(it)
            if (mayFilter) player.addAnalyticsListener(it)
        }
        try {
            val metadata = MediaMetadata.Builder().setTitle(title).build()
            player.setMediaItem(MediaItem.Builder().setUri(uri).setMediaMetadata(metadata).build(), if (mayFilter) 0 else positionMillis.coerceAtLeast(0))
            player.prepare()
        } catch (error: Exception) {
            unload()
            onError(error.message ?: "播放失败")
        }
    }

    fun refresh() {
        if (closed) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
        snapshot = PlayerSnapshot(
            positionMillis = player.currentPosition.coerceAtLeast(0), durationMillis = duration,
            bufferedMillis = player.bufferedPosition.coerceAtLeast(0), playWhenReady = player.playWhenReady && !ended,
            ready = player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_ENDED,
            buffering = player.playbackState == Player.STATE_BUFFERING || restoring,
            ended = ended, seekable = duration > 0 && player.isCurrentMediaItemSeekable,
            speed = player.playbackParameters.speed
        )
    }
    fun togglePlayback() {
        if (closed) return
        if (player.playWhenReady && !ended) pause() else {
            if (ended) player.seekTo(0)
            play()
        }
        refresh()
    }
    fun seekTo(positionMillis: Long) {
        if (closed) return
        if (snapshot.seekable) player.seekTo(positionMillis.coerceIn(0, (snapshot.durationMillis - 1_000).coerceAtLeast(0)))
        refresh()
    }
    fun setSpeed(speed: Float) {
        if (closed) return
        player.setPlaybackSpeed(speed)
        refresh()
    }
    fun onBackground() {
        if (closed || inBackground) return
        inBackground = true
        resumeOnForeground = player.playWhenReady && !ended
        savePosition()
        player.pause()
    }
    fun onForeground() {
        if (closed || !inBackground) return
        inBackground = false
        val resume = resumeOnForeground
        resumeOnForeground = false
        val load = pendingLoad
        pendingLoad = null
        load?.invoke()
        if (resume && player.mediaItemCount > 0) play()
    }

    fun playbackProgressPercent(savedPositionMillis: Long): Int? {
        if (ended) return 100
        if (savedPositionMillis == 0L) return 0
        val duration = player.duration.takeIf { it > 0 } ?: return null
        return (100.0 * player.currentPosition / duration).toInt().coerceIn(0, 100)
    }

    fun savePosition() {
        if (closed || !didStart || restoring || ended) return
        val position = hlsSession?.timeline?.effectiveToVideo(player.currentPosition.coerceAtLeast(0)) ?: player.currentPosition.coerceAtLeast(0)
        if (position == lastSavedPosition) return
        lastSavedPosition = position
        onPosition?.invoke(position)
    }
    fun play() {
        if (closed) return
        // Loads requested after ON_PAUSE retain playback intent until ON_RESUME.
        if (inBackground) resumeOnForeground = true else player.play()
    }
    fun pause() {
        if (closed) return
        resumeOnForeground = false
        savePosition()
        player.pause()
    }
    fun unload() {
        if (closed) return
        pendingLoad = null
        savePosition()
        listener?.let {
            player.removeListener(it)
            player.removeAnalyticsListener(it)
        }
        listener = null
        onPosition = null
        lastSavedPosition = null
        resumeOnForeground = false
        didStart = false
        restoring = false
        ended = false
        player.stop()
        player.clearMediaItems()
        hlsSession = null
        hasVideoFrame = false
        snapshot = PlayerSnapshot(speed = player.playbackParameters.speed)
    }
    fun close() {
        if (closed) return
        unload()
        mediaSession.release()
        player.release()
        closed = true
    }
}
