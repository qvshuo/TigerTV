package art.anjing.tigertv.ui.player

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalView
import androidx.media3.ui.PlayerView
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CompletableDeferred
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
            main { view.bind(media.player, null, true, 0, loading = true) { null } }
            assertEquals(View.VISIBLE, main { view.visibility })
            assertEquals("弹幕加载中...", main { view.contentDescription })
            main { view.bind(media.player, null, false, 0, loading = true) { null } }
            assertEquals(View.GONE, main { view.visibility })
            assertNull(main { view.contentDescription })
        } finally { main{view.release();media.close()};file.delete() }
    }

    @Test fun loadingHintWaitsForTheFirstVideoFrame() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "danmaku-first-frame.mp4")
        instrumentation.context.assets.open("player-test.mp4").use { input -> file.outputStream().use(input::copyTo) }
        val response = CompletableDeferred<Unit>()
        val requestStarted = CompletableDeferred<Unit>()
        val http = configureClient(HttpClient(MockEngine {
            requestStarted.complete(Unit)
            response.await()
            respond("""{"success":true,"animes":[]}""")
        }))
        val media = compose.runOnIdle { Media3Controller(context) }
        lateinit var root: View
        fun overlay(view: View): DanmakuView? {
            if (view is DanmakuView) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) overlay(view.getChildAt(i))?.let { return it }
            return null
        }
        try {
            compose.setContent {
                root = LocalView.current
                val scope = rememberCoroutineScope()
                val state = androidx.compose.runtime.remember(scope) {
                    DanmakuState(scope, { DanmakuClient(Network(http), it) })
                }
                LaunchedEffect(state) {
                    state.configure("https://fixture.test")
                    state.search("测试")
                }
                Box(Modifier.fillMaxSize()) {
                    AndroidView(factory = { PlayerView(it).apply { player = media.player } }, modifier = Modifier.fillMaxSize())
                    DanmakuPlayback(media, state, "测试", false, {}, {})
                }
            }
            compose.waitUntil(5_000) { requestStarted.isCompleted }
            compose.runOnIdle {
                assertFalse(media.hasVideoFrame)
                assertNull(checkNotNull(overlay(root)).contentDescription)
                media.load(file.toURI().toString(), 0, "测试", "fixture", {}, {}, { error(it) })
                media.play()
            }
            compose.waitUntil(10_000) {
                compose.runOnIdle { media.hasVideoFrame && overlay(root)?.contentDescription == "弹幕加载中..." }
            }
            response.complete(Unit)
            compose.waitUntil(5_000) { compose.runOnIdle { overlay(root)?.contentDescription == null } }
        } finally {
            response.complete(Unit)
            compose.runOnIdle { media.close() }
            http.close()
            file.delete()
        }
    }
}
