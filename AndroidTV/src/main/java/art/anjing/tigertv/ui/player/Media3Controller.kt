package art.anjing.tigertv.ui.player

import android.content.Context
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
import androidx.media3.exoplayer.ExoPlayer

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
    private val appContext = context.applicationContext
    private val dataSource = DefaultDataSource.Factory(appContext, DefaultHttpDataSource.Factory().setUserAgent(USER_AGENT))
    val player = ExoPlayer.Builder(appContext)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
        .build()
    private val mediaSession = MediaSession.Builder(appContext, player).setId(java.util.UUID.randomUUID().toString()).build()
    var snapshot by mutableStateOf(PlayerSnapshot())
        private set
    private var resumeOnForeground = false
    private var inBackground = false
    private var listener: Player.Listener? = null
    private var onPosition: ((Long) -> Unit)? = null
    private var lastSavedPosition: Long? = null
    private var didStart = false
    private var restoring = false
    private var ended = false
    private var closed = false

    fun load(url: String, positionMillis: Long, title: String, onStarted: () -> Unit, onPosition: (Long) -> Unit, onError: (String) -> Unit) {
        unload()
        this.onPosition = onPosition
        restoring = true
        listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { refresh() }

            override fun onPlaybackStateChanged(state: Int) {
                if ((state == Player.STATE_READY || state == Player.STATE_ENDED) && restoring) {
                    val duration = player.duration
                    if (duration != C.TIME_UNSET && positionMillis >= duration) player.seekTo(0)
                    restoring = false
                }
                if (state == Player.STATE_ENDED && didStart) {
                    ended = true
                    lastSavedPosition = 0
                    onPosition(0)
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && !restoring && !didStart) {
                    didStart = true
                    onStarted()
                } else if (!isPlaying) savePosition()
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    ended = false
                    savePosition()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                savePosition()
                onError(error.message ?: "播放失败")
            }
        }.also(player::addListener)
        val metadata = MediaMetadata.Builder().setTitle(title).build()
        player.setMediaItem(MediaItem.Builder().setUri(url).setMediaMetadata(metadata).build(), positionMillis.coerceAtLeast(0))
        player.prepare()
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
        if (resume && player.mediaItemCount > 0) play()
    }

    fun savePosition() {
        if (closed || !didStart || restoring || ended) return
        val position = player.currentPosition.coerceAtLeast(0)
        if (position == lastSavedPosition) return
        lastSavedPosition = position
        onPosition?.invoke(position)
    }
    fun play() {
        if (closed) return
        // Resolution can finish after ON_PAUSE; defer its playback intent until ON_RESUME.
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
        savePosition()
        listener?.let(player::removeListener)
        listener = null
        onPosition = null
        lastSavedPosition = null
        resumeOnForeground = false
        didStart = false
        restoring = false
        ended = false
        player.stop()
        player.clearMediaItems()
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
