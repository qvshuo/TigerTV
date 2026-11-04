package art.anjing.tigertv

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.Surface
import art.anjing.tigertv.core.*
import art.anjing.tigertv.data.TigerTVRepository
import art.anjing.tigertv.ui.episodes.EpisodesScreen
import art.anjing.tigertv.ui.Screen
import art.anjing.tigertv.ui.TigerTVNavHost
import art.anjing.tigertv.ui.player.Media3Controller
import art.anjing.tigertv.ui.player.PlayerScreen
import art.anjing.tigertv.ui.theme.TigerTVTheme
import art.anjing.tigertv.ui.viewmodel.TigerTVViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A real ExoPlayer, Compose focus tree and Kotlin core, with deterministic API fixtures. */
@RunWith(AndroidJUnit4::class)
class PlayerRemoteTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val shown = mutableStateOf(true)
    private val detailShown = mutableStateOf(false)
    private val navigationShown = mutableStateOf(false)
    private lateinit var navigation: NavHostController
    private lateinit var rootView: View
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
        val response = """{"code":1,"list":[{"vod_id":1,"vod_name":"遥控器测试","vod_play_from":"主线${'$'}${'$'}${'$'}备用线路","vod_play_url":"第一集${'$'}$mediaUrl#第二集${'$'}$mediaUrl${'$'}${'$'}${'$'}第一集${'$'}$mediaUrl#第二集${'$'}$mediaUrl"}]}"""
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
                Surface(Modifier.fillMaxSize()) {
                    rootView = LocalView.current
                    if (shown.value) {
                        if (navigationShown.value) {
                            val nav = rememberNavController()
                            navigation = nav
                            TigerTVNavHost(model, nav)
                            LaunchedEffect(nav) {
                                nav.navigate(Screen.Results.route)
                                nav.navigate(Screen.Episodes.route)
                                nav.navigate(Screen.Player.route)
                            }
                        } else if (detailShown.value) EpisodesScreen(model, onBack = { exits++ }, onPlay = { model.selectEpisode(it) })
                        else PlayerScreen(model) { exits++; shown.value = false }
                    }
                }
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


    @Test fun failureAfterPlaybackIsClearedFocusesRetryAndBackExits() {
        compose.runOnIdle { model.clearPlayback() }
        compose.waitForIdle()
        compose.runOnIdle { model.setPlaybackError("Fixture failure while already blocked") }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("重试") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("重试").assertIsFocused()
        captureWhenRequested("playback-error")
        remote(KeyEvent.KEYCODE_BACK)
        assertEquals(1, exits)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun playbackFailureRetriesTheSameSourceLineAndEpisode() {
        compose.runOnIdle {
            model.selectPlaybackSource(1)
            model.selectEpisode(1)
        }
        compose.waitUntil(10_000) {
            model.resolvedPlaybackUrl != null && !model.isResolvingPlayback &&
                model.works.single().playback["🎬测试"]?.line == "1"
        }
        compose.runOnIdle { model.setPlaybackError("Fixture native playback failure") }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("重试") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("重试").assertIsFocused()
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onNodeWithText("返回").assertDoesNotExist()
        listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN).forEach {
            remote(it)
            compose.onNodeWithText("重试").assertIsFocused()
        }
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { model.playbackErrorMessage == null && model.resolvedPlaybackUrl != null && !model.isResolvingPlayback }
        compose.waitUntil(10_000) {
            var ready = false
            compose.runOnIdle { ready = findPlayerView(rootView)?.player?.playbackState == androidx.media3.common.Player.STATE_READY }
            ready
        }
        assertEquals("🎬测试", model.selectedResult?.site)
        assertEquals(1, model.selectedPlaybackSourceIndex)
        assertEquals(1, model.selectedEpisodeIndex)
        compose.onNodeWithText("重试").assertDoesNotExist()
        remote(KeyEvent.KEYCODE_MEDIA_PAUSE)
        compose.onNodeWithTag("player-play").assertIsFocused()
    }

    @Test fun pauseNavigationSpeedAndLayeredBackUseTheRemote() {
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("player-play").assertExists()
        compose.onNodeWithTag("player-play").assertContentDescriptionEquals("播放")
        compose.onNodeWithTag("player-previous").assertIsNotEnabled().assertContentDescriptionEquals("上一集")
        compose.onNodeWithText("选集").assertDoesNotExist()
        compose.onNodeWithText("设置").assertDoesNotExist()
        // Pause must survive more than the normal control timeout.
        SystemClock.sleep(5_300)
        compose.onNodeWithTag("player-play").assertExists()
        compose.onNodeWithTag("player-timeline").performSemanticsAction(SemanticsActions.SetProgress) { it(0.25f) }
        compose.waitUntil(5_000) {
            val position = model.works.single().playbackPositions["🎬测试"]?.get("0")?.get("第一集") ?: 0
            position in 29_000L..31_000L
        }
        compose.onNodeWithTag("player-play").assertExists()
        // Native seek completion can restore focus after the saved position updates.
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("player-play") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        captureWhenRequested("transport")
        remote(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("player-next").assertIsFocused()
        captureWhenRequested("focus")
        remote(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("player-speed").assertIsFocused().assertContentDescriptionEquals("倍速 1.0×")
        compose.onNodeWithText("倍速").assertIsDisplayed()
        compose.onNodeWithText("倍速 1.0×").assertDoesNotExist()
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("播放倍速").assertDoesNotExist()
        listOf("0.5×", "0.75×", "1.0×", "1.25×", "1.5×", "2.0×").forEach { rate ->
            val label = compose.onNodeWithText(rate, useUnmergedTree = true)
            label.assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertFalse("Speed label must fit the compact menu: $rate", layouts.single().hasVisualOverflow)
        }
        compose.onNodeWithText("1.0×").assertIsFocused()
        captureWhenRequested("speed-menu")
        remote(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText("1.0×").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithText("0.75×").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("1.25×").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("player-speed").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("1.25×").assertIsSelected()
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("1.5×").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("2.0×").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("player-speed").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithText("1.25×").assertIsFocused()
        remote(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("player-speed").assertIsFocused()
        assertEquals(0, exits)
        remote(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("player-play").assertDoesNotExist()
        assertEquals(0, exits)
        remote(KeyEvent.KEYCODE_BACK)
        assertEquals(1, exits)
    }

    @Test fun detailResourceLineAndEpisodeFocusRemainConnected() {
        compose.runOnIdle { detailShown.value = true }
        compose.onNodeWithText("播放 · 第一集").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNode(hasText("🎬测试") and hasClickAction()).assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("主线").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText("备用线路").assertIsFocused().assertIsNotSelected()
        compose.onNodeWithText("主线").assertIsSelected()
        captureWhenRequested("detail")
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("备用线路").assertIsSelected()
        compose.onNodeWithText("主线").assertIsDisplayed().assertIsNotSelected()
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("第一集").assertIsFocused()
        assertEquals(1, model.selectedPlaybackSourceIndex)
        assertNull("Changing line must not start playback", model.resolvedPlaybackUrl)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun backKeepsTheVideoUntilNavigationThenReleasesItOnce() {
        compose.runOnIdle { navigationShown.value = true }
        compose.waitUntil(10_000) {
            var ready = false
            compose.runOnIdle {
                ready = ::navigation.isInitialized && navigation.currentDestination?.route == Screen.Player.route &&
                    (findPlayerView(rootView)?.player?.currentPosition ?: 0) >= 750
            }
            ready
        }
        var retainedItems = -1
        var pausedAtNavigation = false
        var retainedUrl = false
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            if (destination.route == Screen.Episodes.route) {
                val player = findPlayerView(rootView)?.player
                retainedItems = player?.mediaItemCount ?: -1
                pausedAtNavigation = player?.playWhenReady == false
                retainedUrl = model.resolvedPlaybackUrl != null
            }
        }
        compose.runOnIdle { navigation.addOnDestinationChangedListener(listener) }
        try {
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithTag("player-play").assertExists()
            remote(KeyEvent.KEYCODE_BACK)
            compose.onNodeWithTag("player-play").assertDoesNotExist()
            remote(KeyEvent.KEYCODE_BACK)
            compose.runOnIdle {
                assertEquals(Screen.Episodes.route, navigation.currentDestination?.route)
                assertEquals("Exit cleared the video before navigation", 1, retainedItems)
                assertTrue("Exit must save and pause first", pausedAtNavigation)
                assertTrue("Playback state was cleared before the view disappeared", retainedUrl)
                assertNull(model.resolvedPlaybackUrl)
                assertNull(findPlayerView(rootView))
                assertTrue(model.works.single().playbackPositions["🎬测试"]!!.getValue("0").getValue("第一集") > 0)
            }
            compose.onNodeWithText("播放 · 第一集").assertExists()
        } finally {
            compose.runOnIdle { navigation.removeOnDestinationChangedListener(listener) }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun findPlayerView(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findPlayerView(view.getChildAt(index))?.let { return it }
        }
        return null
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
        compose.onNodeWithTag("player-timeline").assertIsFocused().assertContentDescriptionEquals("播放进度")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
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

internal fun captureWhenRequested(name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val requested = InstrumentationRegistry.getArguments().getString("screenshots")
    if (requested != "true" && name !in requested.orEmpty().split(",")) return
    instrumentation.waitForIdleSync()
    SystemClock.sleep(300)
    val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
    try {
        File(instrumentation.targetContext.filesDir, "player-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    } finally {
        bitmap.recycle()
    }
}
