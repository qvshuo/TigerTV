package art.anjing.tigertv.ui.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import art.anjing.tigertv.core.PlayerController

class Media3Controller(context: Context) : PlayerController {
    val player = ExoPlayer.Builder(context).build()
    override fun load(url: String) { player.setMediaItem(MediaItem.fromUri(url)); player.prepare() }
    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun seek(positionMillis: Long) = player.seekTo(positionMillis.coerceAtLeast(0))
    override fun setVolume(volume: Float) { player.volume = volume.coerceIn(0f, 1f) }
    override fun close() = player.release()
}
