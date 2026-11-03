package art.anjing.tigertv

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import art.anjing.tigertv.core.*
import art.anjing.tigertv.data.TigerTVRepository
import art.anjing.tigertv.ui.player.Media3Controller
import art.anjing.tigertv.ui.player.PlayerScreen
import art.anjing.tigertv.ui.theme.TigerTVTheme
import art.anjing.tigertv.ui.viewmodel.TigerTVViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** A real ExoPlayer, Compose focus tree and Kotlin core, with deterministic API fixtures. */
@RunWith(AndroidJUnit4::class)
class PlayerRemoteTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val shown = mutableStateOf(true)
    private val store = ViewModelStore()
    private lateinit var model: TigerTVViewModel
    private lateinit var video: File
    private var exits = 0

    @Before fun preparePlayer() {
        val context = instrumentation.targetContext
        video = File(context.cacheDir, "remote-control-test.mp4")
        instrumentation.context.assets.open("player-test.mp4").use { input -> video.outputStream().use(input::copyTo) }
        val files = ConcurrentHashMap<String, String>().apply {
            put("source", """{"api_site":{"test":{"name":"🎬测试","api":"https://fixture.test/api"}}}""")
        }
        val storage = object : Storage {
            override fun read(path: String) = files[path]
            override fun writeAtomic(path: String, text: String) { files[path] = text }
        }
        val mediaUrl = video.toURI().toString()
        val response = """{"code":1,"list":[{"vod_id":1,"vod_name":"遥控器测试","vod_play_from":"主线","vod_play_url":"第一集${'$'}$mediaUrl#第二集${'$'}$mediaUrl"}]}"""
        val core = CoreService("cache", "source", storage, configureClient(HttpClient(MockEngine { respond(response) })))
        compose.runOnIdle {
            model = TigerTVViewModel(TigerTVRepository(core), context)
            store.put("player", model)
            model.onKeywordChange("遥控器测试")
            model.search()
        }
        compose.waitUntil(10_000) { model.works.isNotEmpty() && !model.isSearching }
        compose.runOnIdle { model.selectResult(model.works.single().sources.single()) }
        compose.waitUntil(10_000) { model.fetchResponse != null && !model.isFetching }
        compose.runOnIdle { model.selectEpisode(0) }
        compose.waitUntil(10_000) { model.resolvedPlaybackUrl != null }
        compose.setContent {
            TigerTVTheme {
                if (shown.value) PlayerScreen(model) { exits++; shown.value = false }
            }
        }
        compose.waitUntil(15_000) { model.works.single().playback.isNotEmpty() }
        compose.waitForIdle()
    }

    @After fun cleanup() {
        compose.runOnIdle { shown.value = false }
        compose.waitForIdle()
        compose.runOnIdle { store.clear() }
        if (::video.isInitialized) video.delete()
    }

    private fun remote(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        compose.waitForIdle()
    }

    @Test fun pauseNavigationSpeedAndLayeredBackUseTheRemote() {
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithContentDescription("播放").assertExists()
        compose.onNodeWithContentDescription("上一集").assertIsNotEnabled()
        compose.onNodeWithText("选集").assertDoesNotExist()
        compose.onNodeWithText("设置").assertDoesNotExist()
        // Pause must survive more than the normal control timeout.
        SystemClock.sleep(5_300)
        compose.onNodeWithContentDescription("播放").assertExists()
        remote(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithContentDescription("下一集").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText("倍速 1.0×").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("播放倍速").assertExists()
        remote(KeyEvent.KEYCODE_DPAD_RIGHT)
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("倍速 1.25×").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        remote(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("倍速 1.25×").assertIsFocused()
        assertEquals(0, exits)
        remote(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithContentDescription("播放").assertDoesNotExist()
        assertEquals(0, exits)
        remote(KeyEvent.KEYCODE_BACK)
        assertEquals(1, exits)
    }

    @Test fun nativePlaybackKeepsManualPauseAcrossBackgroundAndResetsCompletedProgress() {
        lateinit var controller: Media3Controller
        val positions = mutableListOf<Long>()
        var starts = 0
        compose.runOnIdle {
            controller = Media3Controller(instrumentation.targetContext)
            controller.load(video.toURI().toString(), 30_000, "原生播放器测试",
                onStarted = { starts++ }, onPosition = { positions += it }, onError = { error(it) })
            controller.play()
        }
        try {
            compose.waitUntil(10_000) { starts == 1 && controller.snapshot.ready }
            compose.runOnIdle {
                controller.pause()
                controller.onBackground()
                controller.onForeground()
                assertFalse(controller.player.playWhenReady)
                assertTrue(positions.last() >= 30_000)
                controller.onBackground()
                controller.load(video.toURI().toString(), 30_000, "后台完成的加载",
                    onStarted = { starts++ }, onPosition = { positions += it }, onError = { error(it) })
                controller.play()
                assertFalse("Late resolution must not play in background", controller.player.playWhenReady)
                controller.onForeground()
                assertTrue(controller.player.playWhenReady)
            }
            compose.waitUntil(10_000) { starts == 2 && controller.snapshot.ready }
            compose.runOnIdle {
                controller.setSpeed(1.5f)
                controller.seekTo(60_000)
                controller.play()
                controller.onBackground()
                controller.onForeground()
                assertTrue(controller.player.playWhenReady)
                assertEquals(1.5f, controller.snapshot.speed)
                controller.seekTo(119_000)
            }
            compose.waitUntil(10_000) { controller.snapshot.ended }
            compose.runOnIdle {
                controller.savePosition()
                assertEquals(0L, positions.last())
                controller.togglePlayback()
            }
            compose.waitUntil(10_000) { controller.snapshot.playWhenReady && !controller.snapshot.ended }
        } finally {
            compose.runOnIdle { controller.close() }
        }
    }

    @Test fun shortSeekAndCancelledHoldKeepTheSavedEpisodeAndPosition() {
        // Hidden controls stop polling; the first seek must still use the live player position.
        SystemClock.sleep(5_300)
        remote(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithContentDescription("播放进度").assertIsFocused()
        compose.waitUntil(5_000) {
            (model.works.single().playbackPositions["🎬测试"]?.get("0")?.get("第一集") ?: 0) >= 15_000
        }
        val before = model.works.single().playbackPositions["🎬测试"]!!.getValue("0").getValue("第一集")
        val down = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0))
        SystemClock.sleep(200)
        instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 1))
        remote(KeyEvent.KEYCODE_BACK)
        instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, 0))
        remote(KeyEvent.KEYCODE_MEDIA_PAUSE)
        val after = model.works.single().playbackPositions["🎬测试"]!!.getValue("0").getValue("第一集")
        assertTrue("Cancelled seek changed the actual position: $before -> $after", after < before + 5_000)
        remote(KeyEvent.KEYCODE_DPAD_RIGHT)
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { model.selectedEpisodeIndex == 1 && !model.isResolvingPlayback }
        assertEquals("主线", model.fetchResponse!!.playSources[model.selectedPlaybackSourceIndex].name)
        assertEquals("🎬测试", model.selectedResult!!.site)
        assertTrue(model.works.single().playbackPositions["🎬测试"]!!.getValue("0").getValue("第一集") >= 10_000)
    }
}
