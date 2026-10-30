package art.anjing.tigertv.ui.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import art.anjing.tigertv.core.USER_AGENT
import androidx.media3.exoplayer.ExoPlayer

@androidx.annotation.OptIn(UnstableApi::class)
class Media3Controller(context: Context) {
    private val appContext = context.applicationContext
    private val dataSource = DefaultDataSource.Factory(appContext, DefaultHttpDataSource.Factory().setUserAgent(USER_AGENT))
    val player = ExoPlayer.Builder(appContext)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
        .build()
    fun load(url: String) { player.setMediaItem(MediaItem.fromUri(url)); player.prepare() }
    fun play() = player.play()
    fun pause() = player.pause()
    fun close() = player.release()
}
