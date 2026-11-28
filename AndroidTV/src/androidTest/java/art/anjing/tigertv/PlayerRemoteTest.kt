package art.anjing.tigertv

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import art.anjing.tigertv.domain.episodePositionKey
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
import art.anjing.tigertv.ui.detail.DetailScreen
import art.anjing.tigertv.ui.Screen
import art.anjing.tigertv.ui.TigerTVNavHost
import art.anjing.tigertv.ui.settings.SettingsScreen
import art.anjing.tigertv.ui.player.Media3Controller
import art.anjing.tigertv.ui.player.PlaybackPreferences
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
import org.junit.rules.TestName
import org.junit.rules.TestRule

@RunWith(AndroidJUnit4::class)
class PlayerRemoteTest {
    @get:Rule(order = 0) val preferencesBackup: TestRule = PlaybackPreferencesBackup()
    @get:Rule val compose = createComposeRule()
    @get:Rule val testName = TestName()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val shown = mutableStateOf(true)
    private val detailShown = mutableStateOf(false)
    private val settingsShown = mutableStateOf(false)
    private val navigationShown = mutableStateOf(false)
    private lateinit var navigation: NavHostController
    private lateinit var rootView: View
    private val store = ViewModelStore()
    private lateinit var model: TigerTVViewModel
    private lateinit var video: File
    private var exits = 0
    private val danmakuRequests = java.util.concurrent.atomic.AtomicInteger()

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Before fun preparePlayer() {
        val context = instrumentation.targetContext
        context.getSharedPreferences("danmaku", 0).edit().clear().commit()
        context.getSharedPreferences("playback", 0).edit().clear().commit()
        video = File(context.cacheDir, "remote-control-test.mp4")
        instrumentation.context.assets.open("player-test.mp4").use { input -> video.outputStream().use(input::copyTo) }
        val alternate = if (testName.methodName == "speedIsRestoredForTheSameWorkAfterSwitchingSource") {
            ""","alternate":{"name":"🎬备用资源","api":"https://fixture.test/alternate"}"""
        } else ""
        val files = ConcurrentHashMap<String, String>().apply {
            put("source", """{"api_site":{"test":{"name":"🎬测试","api":"https://fixture.test/api"}$alternate}}""")
        }
        val storage = object : Storage {
            override fun read(path: String) = files[path]
            override fun writeAtomic(path: String, text: String) { files[path] = text }
        }
        val mediaUrl = video.toURI().toString()
        val response = """{"code":1,"list":[{"vod_id":1,"vod_name":"遥控器测试","vod_year":"2026","vod_play_from":"主线${'$'}${'$'}${'$'}备用线路","vod_play_url":"第一集${'$'}$mediaUrl#第二集${'$'}$mediaUrl${'$'}${'$'}${'$'}第一集${'$'}$mediaUrl#第二集${'$'}$mediaUrl"}]}"""
        val core = CoreService("cache", "source", storage, configureClient(HttpClient(MockEngine { request ->
            if (request.url.encodedPath.startsWith("/api/v2/")) {
                assertEquals("Only the saved API address may be used", "fixture.test", request.url.host)
                danmakuRequests.incrementAndGet()
            }
            if (request.url.host == "m.douban.com") respond("""{"subject_collection_items":[]}""")
            else if (request.url.encodedPath == "/api/v2/search/anime") respond("""{"success":true,"animes":[{"animeId":101,"animeTitle":"遥控器测试(2026)【国产剧】","source":"test"},{"animeId":102,"animeTitle":"遥控器测试(2026)【国产剧】","source":"other"}]}""")
            else if (request.url.encodedPath.startsWith("/api/v2/bangumi/")) respond("""{"success":true,"bangumi":{"episodes":[{"episodeId":11,"episodeNumber":"1","episodeTitle":"第1集：再见朋友！出发前…"},{"episodeId":12,"episodeNumber":"2"},{"episodeId":13,"episodeTitle":"抢鲜版：真心话抢先看"},{"episodeId":14,"episodeTitle":"加更版：张萌落泪马天宇拥抱"}]}}""")
            else if (request.url.encodedPath.startsWith("/api/v2/comment/")) respond("""{"comments":[{"p":"300,1,16777215","m":"布局测试弹幕"}]}""")
            else {
                assertEquals("Ordinary playback must not probe through core", "fixture.test", request.url.host)
                respond(response)
            }
        })))
        compose.runOnIdle {
            model = TigerTVViewModel(TigerTVRepository(core), context)
            store.put("player", model)
            model.onKeywordChange("遥控器测试")
            model.search()
        }
        compose.waitUntil(10_000) { model.works.isNotEmpty() && !model.isSearching }
        compose.runOnIdle { model.selectResult(model.works.single().sources.first { it.site == "🎬测试" }) }
        compose.waitUntil(10_000) { model.fetchResponse != null && !model.isFetching }
        compose.runOnIdle { model.selectEpisode(0) }
        compose.waitUntil(10_000) { model.playbackUrl != null }
        compose.setContent {
            TigerTVTheme {
                Surface(Modifier.fillMaxSize()) {
                    rootView = LocalView.current
                    if (shown.value) {
                        if (settingsShown.value) SettingsScreen()
                        else if (navigationShown.value) {
                            val nav = rememberNavController()
                            navigation = nav
                            TigerTVNavHost(model, nav)
                            LaunchedEffect(nav) {
                                nav.navigate(Screen.Results.route)
                                nav.navigate(Screen.Episodes.route)
                                nav.navigate(Screen.Player.route)
                            }
                        } else if (detailShown.value) DetailScreen(model, onBack = { exits++ }, onPlay = { model.selectEpisode(it) })
                        else PlayerScreen(model, onSettings = { settingsShown.value = true }) { exits++; shown.value = false }
                    }
                }
            }
        }
        compose.waitUntil(15_000) { model.works.single().playback.isNotEmpty() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(model.playbackUrl, findPlayerView(rootView)?.player?.currentMediaItem?.localConfiguration?.uri.toString())
        }
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


    @Test fun naturalEndAutoAdvancesAtTheRememberedWorkSpeed() {
        val prefs = instrumentation.targetContext.getSharedPreferences("playback", 0)
        compose.runOnIdle {
            PlaybackPreferences(instrumentation.targetContext).saveSpeed(model.works.single().id, 1.5f)
            val player = findPlayerView(rootView)!!.player!!
            player.setPlaybackSpeed(1.25f)
            player.seekTo(player.duration - 300)
        }
        compose.waitUntil(10_000) { model.selectedEpisodeIndex == 1 }
        compose.waitUntil(10_000) { model.works.single().playback.values.singleOrNull()?.episode == "第二集" }
        compose.runOnIdle {
            val player = findPlayerView(rootView)!!.player!!
            assertEquals(1.5f, player.playbackParameters.speed)
            prefs.edit().putBoolean("autoNext", false).putBoolean("rememberSpeed", false).apply()
            model.selectEpisode(0)
        }
        compose.waitUntil(10_000) { model.works.single().playback.values.singleOrNull()?.episode == "第一集" }
        compose.runOnIdle {
            val player = findPlayerView(rootView)!!.player!!
            assertEquals(1f, player.playbackParameters.speed)
            player.seekTo(player.duration - 300)
        }
        compose.waitUntil(10_000) {
            var ended = false
            instrumentation.runOnMainSync {
                ended = findPlayerView(rootView)?.player?.playbackState == androidx.media3.common.Player.STATE_ENDED
            }
            ended
        }
        kotlinx.coroutines.runBlocking { model.refreshRecentPlayback() }
        compose.runOnIdle {
            assertEquals(0, model.selectedEpisodeIndex)
            val recent = model.recentPlayback.single()
            assertEquals(0L, recent.positionMillis)
            assertEquals(100, recent.progressPercent)
        }
    }

    @Test fun speedIsRestoredForTheSameWorkAfterSwitchingSource() {
        val prefs = PlaybackPreferences(instrumentation.targetContext)
        compose.runOnIdle {
            val work = model.works.single()
            prefs.saveSpeed(work.id, 1.5f)
            model.selectResult(work.sources.single { it.site == "🎬备用资源" })
        }
        compose.waitUntil(10_000) { model.fetchResponse != null && !model.isFetching }
        compose.runOnIdle { model.selectEpisode(0) }
        compose.waitUntil(10_000) { model.works.single().lastPlayedSite == "🎬备用资源" }
        compose.runOnIdle {
            assertEquals(1.5f, findPlayerView(rootView)!!.player!!.playbackParameters.speed)
        }
    }

    @Test fun danmakuMenuAndPerEpisodePreference() {
        compose.runOnIdle { findPlayerView(rootView)!!.player!!.pause() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("player-speed").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("player-speed").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        remote(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("player-danmaku").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("提前 1 秒").assertDoesNotExist()
        compose.onNodeWithText("延后 1 秒").assertDoesNotExist()
        compose.onNodeWithText("选择弹幕").assertExists()
        compose.onNodeWithText("关闭弹幕").assertExists()
        chooseDanmakuAction(0)
        compose.onNodeWithTag("player-danmaku").assertContentDescriptionEquals("弹幕已关闭")
        compose.runOnIdle { model.selectEpisode(1) }
        compose.waitUntil(10_000) { model.selectedEpisodeIndex == 1 }
        compose.runOnIdle { findPlayerView(rootView)!!.player!!.pause() }
        remote(KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithTag("player-danmaku").assertContentDescriptionEquals("弹幕已开启")
        compose.runOnIdle { model.selectEpisode(0) }
        compose.runOnIdle { findPlayerView(rootView)!!.player!!.pause() }
        remote(KeyEvent.KEYCODE_DPAD_UP)
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("弹幕已关闭").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("player-danmaku").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        chooseDanmakuAction(4)
        compose.onNodeWithTag("danmaku-options").assertExists()
        compose.onNodeWithText("完成").assertDoesNotExist()
        compose.onNodeWithText("提前 1 秒").assertDoesNotExist()
        compose.onNodeWithTag("danmaku-open-settings").assertIsFocused()
        compose.onNodeWithTag("danmaku-server").assertDoesNotExist()
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("danmaku-server").assertIsFocused()
        Thread.sleep(500)
        val descriptor = instrumentation.uiAutomation.executeShellCommand("dumpsys input_method")
        val inputState = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        assertTrue("Focusing a field must not open IME", inputState.contains("mInputShown=false"))
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        Thread.sleep(500)
        val editingDescriptor = instrumentation.uiAutomation.executeShellCommand("dumpsys input_method")
        val editingState = android.os.ParcelFileDescriptor.AutoCloseInputStream(editingDescriptor).bufferedReader().use { it.readText() }
        assertTrue("Confirm must allow editing", editingState.contains("mInputShown=true"))
        remote(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("danmaku-options").assertDoesNotExist()
    }

    @Test fun danmakuSettingsKeepFocusedSourcesInsidePanel() {
        compose.runOnIdle { navigationShown.value = true }
        compose.waitUntil(10_000) {
            var ready = false
            instrumentation.runOnMainSync {
                ready = ::navigation.isInitialized && navigation.currentDestination?.route == Screen.Player.route &&
                    findPlayerView(rootView)?.player?.isPlaying == true
            }
            ready
        }
        val media = compose.runOnIdle { findPlayerView(rootView)!!.player!! }
        compose.runOnIdle { findPlayerView(rootView)!!.player!!.pause() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("player-danmaku").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("player-danmaku").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        chooseDanmakuAction(4)
        val panel = compose.onNodeWithTag("danmaku-options")
        compose.onNodeWithTag("danmaku-open-settings").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("danmaku-server").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("", compose.onNodeWithTag("danmaku-server").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("danmaku-server").performTextReplacement("https://fixture.test")
        compose.onNodeWithTag("danmaku-server").performImeAction()
        assertNull(instrumentation.targetContext.getSharedPreferences("danmaku", 0).getString("server", null))
        compose.onNodeWithTag("danmaku-save").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("danmaku-save").assertIsNotEnabled()
        assertEquals("https://fixture.test", instrumentation.targetContext.getSharedPreferences("danmaku", 0).getString("server", ""))
        remote(KeyEvent.KEYCODE_BACK)
        if (compose.onAllNodesWithTag("danmaku-server").fetchSemanticsNodes().isNotEmpty()) {
            remote(KeyEvent.KEYCODE_BACK) // The IME can consume the first Back on TV.
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("danmaku-server").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { assertSame("Settings must preserve the video session", media, findPlayerView(rootView)!!.player) }
        compose.waitUntil(10_000) { danmakuRequests.get() >= 3 }
        compose.runOnIdle { findPlayerView(rootView)!!.player!!.pause() }
        compose.onNodeWithTag("player-danmaku").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("danmaku-action-4").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { compose.onNodeWithTag("danmaku-options").fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "就绪" }
        panel.performScrollToNode(hasTestTag("danmaku-title-101"))
        compose.onNodeWithTag("danmaku-title-101").assertIsSelected()
        compose.onNodeWithText("当前集").assertDoesNotExist()
        panel.performScrollToNode(hasTestTag("danmaku-episode-11"))
        val firstEpisode = compose.onNodeWithTag("danmaku-episode-11").fetchSemanticsNode().boundsInRoot
        val secondEpisode = compose.onNodeWithTag("danmaku-episode-12").fetchSemanticsNode().boundsInRoot
        assertEquals(firstEpisode.left, secondEpisode.left)
        assertTrue(firstEpisode.bottom <= secondEpisode.top)
        for ((id, title) in listOf(
            11 to "第1集：再见朋友！出发前…",
            12 to "第 2 集",
            13 to "抢鲜版：真心话抢先看",
            14 to "加更版：张萌落泪马天宇拥抱",
        )) {
            val tag = "danmaku-episode-$id"
            panel.performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).assertTextContains(title)
        }
        panel.performScrollToNode(hasTestTag("danmaku-query"))
        compose.onNodeWithTag("danmaku-query").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        remote(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("danmaku-search").assertIsFocused()
        compose.onNodeWithTag("danmaku-server").assertDoesNotExist()
        compose.onNodeWithTag("danmaku-search").assertTextContains("搜索")
        compose.onNodeWithTag("danmaku-search").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(10_000) { compose.onNodeWithTag("danmaku-options").fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "就绪" }
        panel.performScrollToNode(hasTestTag("danmaku-title-101"))
        compose.onNodeWithTag("danmaku-title-101").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        val first = compose.onNodeWithTag("danmaku-title-101").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("danmaku-title-102").fetchSemanticsNode().boundsInRoot
        val bounds = panel.fetchSemanticsNode().boundsInRoot
        assertTrue(first.left >= bounds.left)
        assertTrue(first.bottom <= second.top)
        assertTrue(first.right <= bounds.right)
        compose.onNodeWithTag("danmaku-loaded").assertDoesNotExist()
        if (InstrumentationRegistry.getArguments().getString("capture_danmaku") == "true") {
            val capture = instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/tigertv-polish-settings.png")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(capture).use { it.readBytes() }
            android.util.Log.i("TigerTVLayout", "settings-captured")
        }
        panel.performScrollToNode(hasTestTag("danmaku-title-101"))
        compose.onNodeWithTag("danmaku-title-101").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(5_000) { panel.fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "就绪" }
        panel.performScrollToNode(hasTestTag("danmaku-episode-12"))
        compose.onNodeWithTag("danmaku-episode-12").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("danmaku-options").fetchSemanticsNodes().isEmpty() }
        val requestsBeforeToggle = danmakuRequests.get()
        compose.onNodeWithTag("player-danmaku").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        chooseDanmakuAction(0)
        compose.onNodeWithTag("player-danmaku").assertContentDescriptionEquals("弹幕已关闭")
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        chooseDanmakuAction(0)
        compose.onNodeWithTag("player-danmaku").assertContentDescriptionEquals("弹幕已开启")
        assertEquals("Reopening a loaded track must not request another source", requestsBeforeToggle, danmakuRequests.get())
    }

    @Test fun danmakuMenuCanBeReenteredFromItsButton() {
        compose.runOnIdle { findPlayerView(rootView)!!.player!!.pause() }
        val play = compose.onNodeWithTag("player-play")
        val speed = compose.onNodeWithTag("player-speed")
        if (speed.fetchSemanticsNode().boundsInRoot.top >= play.fetchSemanticsNode().boundsInRoot.bottom) {
            play.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            remote(KeyEvent.KEYCODE_DPAD_DOWN)
            speed.assertIsFocused()
            remote(KeyEvent.KEYCODE_DPAD_UP)
            play.assertIsFocused()
        }
        compose.onNodeWithTag("player-danmaku").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("danmaku-action-4").assertIsFocused()
        val toggle = compose.onNodeWithTag("danmaku-action-0")
        val choose = compose.onNodeWithTag("danmaku-action-4")
        assertTrue(toggle.fetchSemanticsNode().boundsInRoot.top < choose.fetchSemanticsNode().boundsInRoot.top)
        remote(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("player-danmaku").assertIsFocused()
    }

    private fun firstEpisodePosition(): Long {
        val work = model.works.single()
        val source = work.sources.first { it.site == "🎬测试" }
        return work.playbackPositions[source.coverKey]?.get("0")?.get(episodePositionKey("第一集", 0)) ?: 0
    }

    private fun chooseDanmakuAction(action: Int) {
        compose.onNodeWithTag("danmaku-action-4").assertIsFocused()
        if (action == 0) remote(KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithTag("danmaku-action-$action").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    @Test fun playbackFailureShowsErrorAndRemoteBackExits() {
        compose.runOnIdle { model.clearPlayback() }
        compose.waitForIdle()
        compose.runOnIdle { model.setPlaybackError("Fixture native playback failure") }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("无法播放")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("无法播放").assertIsDisplayed()
        compose.onNodeWithText("重试").assertDoesNotExist()
        captureWhenRequested("playback-error")
        remote(KeyEvent.KEYCODE_BACK)
        assertEquals(1, exits)
    }

    @Test fun pauseNavigationSpeedAndLayeredBackUseTheRemote() {
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("player-play").assertExists()
        compose.onNodeWithTag("player-play").assertContentDescriptionEquals("播放")
        compose.onNodeWithTag("player-previous").assertIsNotEnabled().assertContentDescriptionEquals("上一集")
        compose.onNodeWithText("选集").assertDoesNotExist()
        compose.onNodeWithText("设置").assertDoesNotExist()
        SystemClock.sleep(5_300)
        compose.onNodeWithTag("player-play").assertExists()
        compose.onNodeWithTag("player-timeline").performSemanticsAction(SemanticsActions.SetProgress) { it(0.25f) }
        compose.waitUntil(5_000) {
            val position = firstEpisodePosition()
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

    @Test fun detailResourceAndEpisodeFocusRemainConnected() {
        compose.runOnIdle { model.clearPlayback(); detailShown.value = true }
        compose.onNode(hasText("播放 · 第一集", substring = true) and hasClickAction()).assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNode(hasText("测试") and hasClickAction()).assertIsFocused()
        compose.onNodeWithText("线路").assertDoesNotExist()
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("第一集").assertIsFocused()
        captureWhenRequested("detail")
        remote(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText("第二集").assertIsFocused()
        assertEquals(0, model.selectedPlaybackSourceIndex)
        assertNull("Moving focus must not start playback", model.playbackUrl)
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
                retainedUrl = model.playbackUrl != null
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
                assertNull(model.playbackUrl)
                assertNull(findPlayerView(rootView))
                assertTrue(firstEpisodePosition() > 0)
            }
            compose.onNodeWithText("继续播放 · 第一集").assertExists()
        } finally {
            compose.runOnIdle { navigation.removeOnDestinationChangedListener(listener) }
        }
    }

    @Test fun referenceCommentsUseTheActualPlayerClock() {
        lateinit var controller: Media3Controller
        var started = false
        val timeline = MediaTimeline(emptyList(), listOf(ReferenceSpan(0, 120_000, 1_000, 121_000)))
        val comments = listOf(31_000L to "测试弹幕：暂停", 61_000L to "测试弹幕：定位")
        fun nearby(referenceMs: Long) = comments.filter { kotlin.math.abs(it.first - referenceMs) < 2_000 }.map { it.second }
        compose.runOnIdle {
            controller = Media3Controller(instrumentation.targetContext)
            controller.load(video.toURI().toString(), 30_000, "时间轴测试",
                onStarted = { started = true }, onPosition = {}, onError = { error(it) })
            controller.play()
        }
        try {
            compose.waitUntil(10_000) { started && controller.snapshot.ready }
            var paused = 0L
            compose.runOnIdle { controller.pause(); paused = controller.player.currentPosition }
            SystemClock.sleep(400)
            compose.runOnIdle {
                assertTrue(kotlin.math.abs(controller.player.currentPosition - paused) < 100)
                assertEquals(paused + 1_000, timeline.effectiveToReference(paused))
                assertTrue("测试弹幕：暂停" in nearby(timeline.effectiveToReference(paused)!!))
                controller.seekTo(60_000)
            }
            SystemClock.sleep(500)
            compose.runOnIdle {
                val actual = controller.player.currentPosition
                assertTrue(kotlin.math.abs(actual - 60_000) < 1_000)
                assertEquals(actual + 1_000, timeline.effectiveToReference(actual))
                assertTrue("测试弹幕：定位" in nearby(timeline.effectiveToReference(actual)!!))
                assertNull(MediaTimeline(emptyList()).effectiveToReference(actual))
                controller.setSpeed(1.5f)
                controller.play()
            }
            SystemClock.sleep(500)
            compose.runOnIdle {
                val actual = controller.player.currentPosition
                assertTrue(actual > 60_000)
                assertEquals(actual + 1_000, timeline.effectiveToReference(actual))
            }
        } finally { compose.runOnIdle { controller.close() } }
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
                assertEquals("Background loads must wait for foreground", 0, controller.player.mediaItemCount)
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
            (firstEpisodePosition()) >= 15_000
        }
        val before = firstEpisodePosition()
        val down = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0))
        SystemClock.sleep(200)
        instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 1))
        remote(KeyEvent.KEYCODE_BACK)
        instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, 0))
        remote(KeyEvent.KEYCODE_MEDIA_PAUSE)
        val after = firstEpisodePosition()
        assertTrue("Cancelled seek changed the actual position: $before -> $after", after < before + 5_000)
        remote(KeyEvent.KEYCODE_DPAD_RIGHT)
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { model.selectedEpisodeIndex == 1 }
        assertEquals("主线", model.fetchResponse!!.playSources[model.selectedPlaybackSourceIndex].name)
        assertEquals("🎬测试", model.selectedResult!!.site)
        assertTrue(firstEpisodePosition() >= 10_000)
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
