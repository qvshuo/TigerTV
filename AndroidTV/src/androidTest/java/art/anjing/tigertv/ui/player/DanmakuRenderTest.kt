package art.anjing.tigertv.ui.player

import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import art.anjing.tigertv.core.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Real Media3 clock with a small explicit fixture for idle scheduling, not a performance sample. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DanmakuRenderTest {
    @get:Rule val compose = createComposeRule()
    @Test fun silentWindowsSleepAndWakeWithMediaSpeed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir,"danmaku-clock.mp4")
        instrumentation.context.assets.open("player-test.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        lateinit var media: Media3Controller
        lateinit var view: DanmakuView
        val data = DanmakuTrack(120_000,listOf(DanmakuComment(60_000,"即将出现",1,0xFFFFFF)))
        fun <T> main(block:()->T):T { var value:T?=null;instrumentation.runOnMainSync { value=block() }; @Suppress("UNCHECKED_CAST") return value as T }
        try {
            main {
                media=Media3Controller(context)
                media.load(file.toURI().toString(),0,"时钟测试",site="fixture",onStarted={},onPosition={},onError={error(it)})
            }
            compose.setContent { AndroidView(factory={DanmakuView(it).also { v -> view=v;v.bind(media.player,data,true,0){null} }},modifier=Modifier.fillMaxSize()) }
            compose.waitUntil(10_000){main{media.player.playbackState==androidx.media3.common.Player.STATE_READY}}
            main{media.player.seekTo(0);media.player.play()}
            Thread.sleep(300)
            val before=main{view.drawCount}
            Thread.sleep(700)
            assertTrue("A distant future comment must not cause a frame loop",main{view.drawCount}-before<=6)
            main{media.player.seekTo(59_000);media.player.setPlaybackSpeed(2f);media.player.play()}
            compose.waitUntil(4_000){main{view.drawnCount>0}}
            assertTrue(main{view.estimatedCapacityPerMinute}>0)
            main{media.player.pause()}
            Thread.sleep(250)
            val frozen=main{view.lastClockMs}
            val paints=main{view.drawCount}
            Thread.sleep(500)
            assertEquals(frozen,main{view.lastClockMs})
            assertTrue(main{view.drawCount}-paints<=1)
            main{view.bind(media.player,data,true,1000){null}}
            compose.waitUntil(2_000){main{kotlin.math.abs(view.lastClockMs-media.player.currentPosition-1000)<100}}
            main{view.bind(media.player,DanmakuTrack(120_000,emptyList()),true,0){null}}
            compose.waitUntil(2_000){main{view.drawnCount==0&&view.commentCount==0}}
            main{view.bind(media.player,data,false,0){null}}
            assertEquals(View.GONE,main{view.visibility})
        } finally { main{view.release();media.close()};file.delete() }
    }
}
