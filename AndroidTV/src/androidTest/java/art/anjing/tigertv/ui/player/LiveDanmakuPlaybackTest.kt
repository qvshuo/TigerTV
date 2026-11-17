package art.anjing.tigertv.ui.player

import android.view.View
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import art.anjing.tigertv.core.*
import art.anjing.tigertv.data.TigerTVRepository
import art.anjing.tigertv.ui.theme.TigerTVTheme
import art.anjing.tigertv.ui.viewmodel.TigerTVViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Opt-in full production PlayerScreen: real HLS and real danmu_api; only catalog metadata is injected. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LiveDanmakuPlaybackTest {
    @get:Rule val compose = createComposeRule()
    private fun <T : View> find(view: View, type: Class<T>): T? {
        if (type.isInstance(view)) return type.cast(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), type)?.let { return it }
        return null
    }
    @Test fun realCommentsRenderOnFilteredVideo() {
        val args = InstrumentationRegistry.getArguments()
        val mediaUrl = args.getString("media_url")
        val service = args.getString("danmu_url")
        assumeNotNull(mediaUrl, service)
        val nextMediaUrl = args.getString("next_media_url") ?: mediaUrl
        val work = args.getString("work_title")!!
        val site = args.getString("media_site")!!
        val titleId = args.getString("danmu_title_id")!!
        val episodeId = args.getString("danmu_episode_id")!!
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("danmaku", 0).edit().clear().commit()
        val liveHttp = platformHttpClient()
        val liveNetwork = Network(liveHttp)
        val files = ConcurrentHashMap<String, String>().apply { put("source", """{"api_site":{"live":{"name":"$site","api":"https://fixture.test/api"}}}""") }
        val storage = object : Storage {
            override fun read(path: String) = files[path]
            override fun writeAtomic(path: String, text: String) { files[path] = text }
        }
        val response = """{"code":1,"list":[{"vod_id":1,"vod_name":"$work","vod_play_from":"HLS","vod_play_url":"第1集${'$'}$mediaUrl#第2集${'$'}$nextMediaUrl"}]}"""
        val core = CoreService("live-ui", "source", storage, configureClient(HttpClient(MockEngine { request ->
            when (request.url.host) {
                "fixture.test" -> respond(response)
                "m.douban.com" -> respond("""{"subject_collection_items":[]}""")
                else -> respond(liveNetwork.get(request.url.toString()))
            }
        })))
        lateinit var model: TigerTVViewModel
        lateinit var root: View
        val store = ViewModelStore()
        try {
            compose.runOnIdle { model = TigerTVViewModel(TigerTVRepository(core), context); store.put("live", model); model.onKeywordChange(work); model.search() }
            compose.waitUntil(15_000) { model.works.isNotEmpty() && !model.isSearching }
            compose.runOnIdle { model.selectResult(model.works.single().sources.single()) }
            compose.waitUntil(15_000) { model.fetchResponse != null && !model.isFetching }
            compose.runOnIdle { model.selectEpisode(0) }
            compose.setContent { TigerTVTheme { root = LocalView.current; PlayerScreen(model) {} } }
            fun player(): Player = find(root, PlayerView::class.java)!!.player!!
            fun overlay(): DanmakuView = find(root, DanmakuView::class.java)!!
            fun <T> main(block: () -> T): T { var result: T? = null; InstrumentationRegistry.getInstrumentation().runOnMainSync { result = block() }; @Suppress("UNCHECKED_CAST") return result as T }
            compose.waitUntil(30_000) { main { player().isPlaying } }
            fun openQuick() {
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
                compose.waitUntil(5_000) { compose.onAllNodesWithTag("player-speed").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("player-speed").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.waitForIdle()
            }
            fun openMenu() {
                openQuick()
                compose.onNodeWithTag("danmaku-action-4").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.waitUntil(5_000) { compose.onAllNodesWithTag("danmaku-options").fetchSemanticsNodes().isNotEmpty() }
            }
            fun option(tag: String): SemanticsNodeInteraction {
                compose.onNodeWithTag("danmaku-options").performScrollToNode(hasTestTag(tag))
                return compose.onNodeWithTag(tag)
            }
            fun clickOption(tag: String) {
                option(tag).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.waitForIdle()
            }
            fun awaitRequest() {
                compose.waitUntil(45_000) {
                    compose.onNodeWithTag("danmaku-options").fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "就绪"
                }
            }
            openMenu()
            option("danmaku-server").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            option("danmaku-server").performTextReplacement(service!!)
            compose.onNodeWithTag("danmaku-server").performImeAction()
            clickOption("danmaku-save")
            clickOption("danmaku-search")
            awaitRequest()
            clickOption("danmaku-title-$titleId")
            awaitRequest()
            clickOption("danmaku-episode-$episodeId")
            awaitRequest()
            android.util.Log.i("TigerTVDanmakuLive", "work=$work selectedEpisode=$episodeId")
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            main { player().seekTo(120_000); player().play() }
            compose.waitUntil(25_000) { main { player().isPlaying && overlay().drawnCount > 0 } }
            val count = main { overlay().commentCount }
            assertTrue(count > 1_000)
            main { assertTrue(kotlin.math.abs(overlay().lastClockMs - player().currentPosition) < 200); player().pause() }
            Thread.sleep(300)
            val paused = main { overlay().lastClockMs }
            Thread.sleep(700)
            assertEquals(paused, main { overlay().lastClockMs })
            // Adjust reference offset while paused: no video-time movement or repeated ad subtraction.
            openQuick()
            compose.onNodeWithTag("danmaku-action-1").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5_000) { main { kotlin.math.abs(overlay().lastClockMs - player().currentPosition - 1_000) < 100 } }
            main { player().seekTo(310_000); player().setPlaybackSpeed(1.5f); player().play() }
            Thread.sleep(100)
            val buffering = main { player().playbackState == Player.STATE_BUFFERING }
            val bufferClock = main { overlay().lastClockMs }
            Thread.sleep(150)
            val bufferFrozen = buffering && main { player().playbackState == Player.STATE_BUFFERING }
            if (bufferFrozen) assertEquals(bufferClock, main { overlay().lastClockMs })
            compose.waitUntil(25_000) { main { player().isPlaying && player().currentPosition > 311_000 && overlay().drawnCount > 0 } }
            main { assertTrue(kotlin.math.abs(overlay().lastClockMs - player().currentPosition - 1_000) < 200) }
            val pss = mutableListOf<Long>()
            val droppedBefore = main { (player() as androidx.media3.exoplayer.ExoPlayer).videoDecoderCounters?.droppedBufferCount ?: 0 }
            repeat(12) {
                Thread.sleep(5_000)
                main { assertNull(player().playerError); assertTrue(overlay().drawnCount <= 12) }
                pss += android.os.Debug.getPss()
            }
            val dropped = main { ((player() as androidx.media3.exoplayer.ExoPlayer).videoDecoderCounters?.droppedBufferCount ?: 0) - droppedBefore }
            android.util.Log.i("TigerTVDanmakuLive", "work=$work steadyPssKiB=$pss steadyDroppedFrames=$dropped")
            val output = File(context.getExternalFilesDir(null), "danmaku-live-${work}.png")
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap -> output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
            android.util.Log.i("TigerTVDanmakuLive", "work=$work scrollingComments=$count pause=true seek=true speed=true offset=true rendered=true bufferingObserved=$bufferFrozen")
            main { player().pause() }
            openQuick()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals(View.GONE, main { overlay().visibility })
            val disabledClock = main { overlay().lastClockMs }
            Thread.sleep(300)
            assertEquals(disabledClock, main { overlay().lastClockMs })
            main { model.selectEpisode(1) }
            compose.waitUntil(10_000) { main { find(root, DanmakuView::class.java)?.commentCount == 0 } }
            compose.waitUntil(25_000) { main { player().isPlaying } }
            assertEquals(0, main { overlay().drawnCount })
            openMenu()
            option("danmaku-server").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            option("danmaku-server").performTextReplacement(service + "/missing")
            compose.onNodeWithTag("danmaku-server").performImeAction()
            clickOption("danmaku-save")
            clickOption("danmaku-search")
            awaitRequest()
            compose.onNodeWithTag("danmaku-options").performScrollToNode(hasText("弹幕匹配失败，请手动选择"))
            compose.onNodeWithText("弹幕匹配失败，请手动选择").assertExists()
            val errorPosition = main { player().currentPosition }
            compose.waitUntil(10_000) { main { assertNull(player().playerError); player().currentPosition > errorPosition + 500 } }
            android.util.Log.i("TigerTVDanmakuLive", "work=$work disabledStopsDrawing=true episodeCleared=true apiFailureVideoContinues=true")
        } catch (failure: Throwable) {
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(context.getExternalFilesDir(null), "danmaku-failed.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            throw failure
        } finally {
            compose.runOnIdle { store.clear() }
            liveHttp.close()
        }
    }
}
