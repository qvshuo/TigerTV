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
        val episodeLabel = args.getString("episode_label") ?: "第1集"
        val work = args.getString("work_title")!!
        val site = args.getString("media_site")!!
        val titleId = args.getString("danmu_title_id")?.toLongOrNull()
        val episodeId = args.getString("danmu_episode_id")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("danmaku", 0)
        preferences.edit().clear().putString("server", service).commit()
        val liveHttp = platformHttpClient()
        val liveNetwork = Network(liveHttp)
        val files = ConcurrentHashMap<String, String>().apply { put("source", """{"api_site":{"live":{"name":"$site","api":"https://fixture.test/api"}}}""") }
        val storage = object : Storage {
            override fun read(path: String) = files[path]
            override fun writeAtomic(path: String, text: String) { files[path] = text }
        }
        val response = """{"code":1,"list":[{"vod_id":1,"vod_name":"$work","vod_play_from":"HLS","vod_play_url":"$episodeLabel${'$'}$mediaUrl#第2集${'$'}$nextMediaUrl"}]}"""
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
            compose.runOnIdle {
                model.selectEpisode(0)
                if (titleId != null) {
                    val session = checkNotNull(model.playbackSession)
                    preferences.edit().putLong(danmakuPreferenceKey(service!!, session.workId, session.sourceKey), titleId).apply()
                }
            }
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
            fun awaitRequest() {
                compose.waitUntil(45_000) {
                    compose.onNodeWithTag("danmaku-options").fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "就绪"
                }
            }
            compose.waitUntil(35_000) { main { overlay().commentCount > 0 } }
            openMenu()
            if (titleId != null) option("danmaku-title-$titleId").assertIsSelected()
            if (episodeId != null) option("danmaku-episode-$episodeId").assertIsSelected()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            main { player().seekTo(120_000); player().play() }
            compose.waitUntil(25_000) { main { player().isPlaying && overlay().drawnCount > 0 } }
            val count = main { overlay().commentCount }
            assertTrue(count > 0)
            main { assertTrue(kotlin.math.abs(overlay().lastClockMs - player().currentPosition) < 200); player().pause() }
            Thread.sleep(300)
            val paused = main { overlay().lastClockMs }
            Thread.sleep(700)
            assertEquals(paused, main { overlay().lastClockMs })
            val seekAt = args.getString("seek_millis")?.toLongOrNull() ?: 310_000L
            main { player().seekTo(seekAt); player().setPlaybackSpeed(1.5f); player().play() }
            Thread.sleep(100)
            val buffering = main { player().playbackState == Player.STATE_BUFFERING }
            val bufferClock = main { overlay().lastClockMs }
            Thread.sleep(150)
            val bufferFrozen = buffering && main { player().playbackState == Player.STATE_BUFFERING }
            if (bufferFrozen) assertEquals(bufferClock, main { overlay().lastClockMs })
            compose.waitUntil(25_000) { main { player().isPlaying && player().currentPosition > seekAt + 1_000 && overlay().drawnCount > 0 } }
            main { assertTrue(kotlin.math.abs(overlay().lastClockMs - player().currentPosition) < 200) }
            Thread.sleep(1_000)
            main { assertNull(player().playerError); assertTrue(overlay().drawnCount <= 12) }
            main {
                val counters = (player() as androidx.media3.exoplayer.ExoPlayer).videoDecoderCounters!!
                counters.ensureUpdated()
                android.util.Log.i("TigerTVDanmakuLive", "renderedVideoFrames=${counters.renderedOutputBufferCount} droppedVideoFrames=${counters.droppedBufferCount}")
            }
            val soakSeconds = args.getString("soak_seconds")?.toIntOrNull()?.coerceIn(0, 600) ?: 0
            if (soakSeconds > 0) {
                val before = main { player().currentPosition }
                Thread.sleep(soakSeconds * 1_000L)
                main {
                    assertNull(player().playerError)
                    val advanced = player().currentPosition - before
                    assertTrue("Playback stalled during soak", advanced >= soakSeconds * 750L)
                    assertTrue(kotlin.math.abs(overlay().lastClockMs - player().currentPosition) < 300 || overlay().drawnCount == 0)
                    val counters = (player() as androidx.media3.exoplayer.ExoPlayer).videoDecoderCounters!!
                    counters.ensureUpdated()
                    android.util.Log.i("TigerTVDanmakuLive", "soakSeconds=$soakSeconds advancedMs=$advanced rendered=${counters.renderedOutputBufferCount} dropped=${counters.droppedBufferCount}")
                }
            }
            val output = File(context.getExternalFilesDir(null), "danmaku-live-${work}.png")
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap -> output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
            android.util.Log.i("TigerTVDanmakuLive", "work=$work scrollingComments=$count pause=true seek=true speed=true rendered=true bufferingObserved=$bufferFrozen")
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
            main { preferences.edit().putString("server", service + "/missing").apply() }
            awaitRequest()
            compose.onNodeWithTag("danmaku-options").performScrollToNode(hasText("无法加载弹幕"))
            compose.onNodeWithText("无法加载弹幕").assertExists()
            val errorPosition = main { player().currentPosition }
            compose.waitUntil(10_000) { main { assertNull(player().playerError); player().currentPosition > errorPosition + 500 } }
            android.util.Log.i("TigerTVDanmakuLive", "work=$work disabledStopsDrawing=true episodeCleared=true apiFailureVideoContinues=true")
        } catch (failure: Throwable) {
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(context.getExternalFilesDir(null), "danmaku-failed.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            throw failure
        } finally {
            compose.runOnIdle { store.clear(); preferences.edit().clear().apply() }
            liveHttp.close()
        }
    }
}
