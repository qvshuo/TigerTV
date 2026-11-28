package art.anjing.tigertv.ui.player

import android.content.Intent
import androidx.core.net.toUri
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import art.anjing.tigertv.debug.SeekProbeActivity
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test

/** Opt-in real CDN test. No fixture or historical ad position is substituted for current media. */
@androidx.annotation.OptIn(UnstableApi::class)
class LiveHlsPlaybackTest {
    @Test fun currentPlaylistPlaysAcrossRemovedGroups() {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("media_url")
        val site = args.getString("media_site")
        assumeNotNull(url, site)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, SeekProbeActivity::class.java).setData(url!!.toUri())
            .putExtra("start_ms", 0L).putExtra("end_ms", 1L)
        ActivityScenario.launch<SeekProbeActivity>(intent).use { scenario ->
            fun <T> onPlayer(block: (Media3Controller) -> T): T {
                var value: T? = null
                scenario.onActivity { value = block(it.controller!!) }
                @Suppress("UNCHECKED_CAST") return value as T
            }
            fun await(label: String, timeout: Long = 30_000, condition: (Media3Controller) -> Boolean) {
                val end = android.os.SystemClock.elapsedRealtime() + timeout
                while (android.os.SystemClock.elapsedRealtime() < end) {
                    if (onPlayer { assertNull(it.player.playerError); condition(it) }) return
                    Thread.sleep(100)
                }
                fail("Timed out: $label")
            }
            if (args.getString("expect_filter") == "false") {
                onPlayer { it.play() }
                await("original CDN playback") { it.player.isPlaying }
                onPlayer { it.player.seekTo(120_000) }
                await("original CDN seek") { it.player.isPlaying && it.player.currentPosition > 121_000 }
                onPlayer {
                    assertNull(it.playbackTimeline)
                    assertTrue(it.player.videoDecoderCounters!!.renderedOutputBufferCount > 0)
                    assertTrue(it.player.audioDecoderCounters!!.renderedOutputBufferCount > 0)
                    it.pause()
                }
                val paused = onPlayer { it.player.currentPosition }
                Thread.sleep(1_000)
                assertTrue(kotlin.math.abs(onPlayer { it.player.currentPosition } - paused) < 100)
                onPlayer { it.setSpeed(2f); it.play() }
                await("original CDN rate") { it.player.currentPosition > paused + 1_000 }
                android.util.Log.i("TigerTVLive", "source=$site filtered=false audioVideoRendered=true seekPauseRate=true")
                return
            }
            await("current filtered media") { it.player.playbackState == Player.STATE_READY && it.playbackTimeline != null }
            val timeline = onPlayer { it.playbackTimeline!! }
            val intervals = timeline.ads
            assertTrue(intervals.isNotEmpty())
            // Use only intervals recognized from this exact response. Check every retained join.
            val duration = onPlayer { it.player.duration }
            for (ad in intervals) {
                val boundary = timeline.videoToEffective(ad.endMs).timeMs
                // A removed tail has no following scene: check the retained end separately.
                if (boundary + 2_000 >= duration) continue
                onPlayer { it.player.seekTo((boundary - 1_200).coerceAtLeast(0)); it.play() }
                await("join playback") { it.player.isPlaying && it.player.currentPosition >= boundary + 1_200 }
                onPlayer {
                    assertTrue(it.player.videoDecoderCounters!!.renderedOutputBufferCount > 0)
                    assertTrue(it.player.audioDecoderCounters!!.renderedOutputBufferCount > 0)
                    assertEquals(ad.endMs + 1_000, timeline.effectiveToVideo(boundary + 1_000))
                }
            }
            onPlayer { it.pause() }
            val paused = onPlayer { it.player.currentPosition }
            Thread.sleep(1_000)
            assertTrue(kotlin.math.abs(onPlayer { it.player.currentPosition } - paused) < 100)
            onPlayer { it.setSpeed(1.5f); it.play() }
            await("rate playback") { it.player.currentPosition >= paused + 1_000 }
            onPlayer { assertEquals(1.5f, it.player.playbackParameters.speed) }
            android.util.Log.i("TigerTVLive", "source=$site groups=${intervals.size} removedMs=${intervals.sumOf { it.endMs - it.startMs }} durationMs=$duration audioVideoRendered=true pauseAndRate=true")
        }
    }
}
