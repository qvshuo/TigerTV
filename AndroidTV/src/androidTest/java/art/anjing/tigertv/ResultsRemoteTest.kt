package art.anjing.tigertv

import android.view.KeyEvent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.tv.material3.Surface
import androidx.lifecycle.ViewModelStore
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import art.anjing.tigertv.core.*
import art.anjing.tigertv.data.TigerTVRepository
import art.anjing.tigertv.ui.Screen
import art.anjing.tigertv.ui.TigerTVNavHost
import art.anjing.tigertv.ui.theme.TigerTVTheme
import art.anjing.tigertv.ui.components.UiMetrics
import art.anjing.tigertv.ui.viewmodel.TigerTVViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import java.util.concurrent.ConcurrentHashMap
import java.io.File
import org.junit.Rule
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResultsRemoteTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun resultFocusRestoresBelowFirstViewport() {
        val files = ConcurrentHashMap(mapOf("source" to """{"api_site":{"test":{"name":"🎬审查","api":"https://fixture.test/api"}}}"""))
        val storage = object : Storage {
            override fun read(path: String) = files[path]
            override fun writeAtomic(path: String, text: String) { files[path] = text }
        }
        val list = (1..35).joinToString(",") { """{"vod_id":$it,"vod_name":"审查作品$it","vod_play_from":"主线","vod_play_url":"第一集${'$'}https://fixture.test/video.m3u8"}""" }
        val core = CoreService("cache", "source", storage, configureClient(HttpClient(MockEngine {
            respond("""{"code":1,"list":[$list]}""")
        })))
        val store = ViewModelStore()
        lateinit var model: TigerTVViewModel
        compose.runOnIdle {
            model = TigerTVViewModel(TigerTVRepository(core), instrumentation.targetContext)
            store.put("audit", model)
            model.onKeywordChange("审查")
            model.search()
        }
        compose.waitUntil(10_000) { !model.isSearching && model.works.size == 35 }
        try {
            compose.setContent {
                TigerTVTheme {
                    Surface(Modifier.fillMaxSize()) {
                        val navigation = rememberNavController()
                        TigerTVNavHost(model, navigation)
                        LaunchedEffect(Unit) { navigation.navigate(Screen.Results.route) }
                    }
                }
            }
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToIndex(25)
            val card = compose.onNode(hasText("审查作品26") and hasClickAction())
            card.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            compose.waitForIdle()
            card.assertIsFocused()
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5_000) { model.fetchResponse != null }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.waitForIdle()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("搜索结果: 审查")).fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasText("审查作品26") and hasClickAction() and isFocused()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(hasText("审查作品26") and hasClickAction()).assertIsDisplayed().assertIsFocused()
            compose.onNodeWithText("返回").assertDoesNotExist()
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.waitForIdle()
            compose.onNode(hasText("审查作品27") and hasClickAction()).assertIsFocused()
            val focusedBounds = compose.onNode(hasText("审查作品27") and hasClickAction()).getUnclippedBoundsInRoot()
            val windowBounds = compose.onRoot().getUnclippedBoundsInRoot()
            assertTrue("Native focus scale extends into the bottom safety margin",
                focusedBounds.bottom <= windowBounds.bottom - UiMetrics.pageVerticalPadding)
            captureWhenRequested("results")
        } finally {
            compose.runOnIdle { store.clear() }
        }
    }

    @Test fun recommendationsSearchAndSettingsReturnToTheirLaunchControls() {
        val media = File(instrumentation.targetContext.cacheDir, "home-resume-fixture.mp4")
        instrumentation.context.assets.open("player-test.mp4").use { input -> media.outputStream().use(input::copyTo) }
        val mediaUrl = media.toURI()
        val files = ConcurrentHashMap(mapOf("source" to """{"api_site":{"test":{"name":"🎬审查","api":"https://fixture.test/api"}}}"""))
        val storage = object : Storage {
            override fun read(path: String) = files[path]
            override fun writeAtomic(path: String, text: String) { files[path] = text }
        }
        val core = CoreService("cache", "source", storage, configureClient(HttpClient(MockEngine { request ->
            if (request.url.host == "m.douban.com") {
                respond("""{"subject_collection_items":[{"id":"home","title":"热门验收","year":"2026"}]}""")
            } else {
                respond("""{"code":1,"list":[{"vod_id":1,"vod_name":"热门验收","vod_play_from":"主线","vod_play_url":"第一集${'$'}$mediaUrl"}]}""")
            }
        })))
        val store = ViewModelStore()
        val model = compose.runOnIdle {
            TigerTVViewModel(TigerTVRepository(core), instrumentation.targetContext).also { store.put("home", it) }
        }
        fun remote(key: Int) {
            instrumentation.sendKeyDownUpSync(key)
            compose.waitForIdle()
        }
        lateinit var navigation: androidx.navigation.NavHostController
        try {
            compose.setContent {
                TigerTVTheme {
                    Surface(Modifier.fillMaxSize()) {
                        navigation = rememberNavController()
                        TigerTVNavHost(model, navigation)
                    }
                }
            }
            compose.onNodeWithText("热门推荐").assertIsDisplayed()
            compose.onNodeWithText("热门推荐").assertDoesNotExist()
            compose.onNodeWithText("小老虎爱看剧").assertIsDisplayed()
            compose.onNodeWithText("继续观看").assertDoesNotExist()
            val recommendation = hasText("热门验收 (2026)") and hasClickAction()
            compose.waitUntil(10_000) { compose.onAllNodes(recommendation).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("home-search").assertIsFocused().assertIsDisplayed()
            compose.onNodeWithTag("home-settings").assertIsDisplayed()
            compose.onNodeWithText("小老虎爱看剧").assertIsDisplayed()
            compose.onNode(recommendation).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("搜索结果: 热门验收")).fetchSemanticsNodes().isNotEmpty() }
            remote(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(10_000) { compose.onAllNodes(recommendation and isFocused()).fetchSemanticsNodes().isNotEmpty() }
            remote(KeyEvent.KEYCODE_DPAD_UP)
            remote(KeyEvent.KEYCODE_DPAD_UP)
            compose.onNodeWithTag("home-search").assertIsFocused()
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNode(hasSetTextAction()).assertIsFocused()
            compose.onNodeWithText("搜索历史").assertIsDisplayed()
            remote(KeyEvent.KEYCODE_BACK)
            compose.onNodeWithTag("home-search").assertIsFocused()
            remote(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.onNodeWithTag("home-settings").assertIsFocused()
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithTag("danmaku-server").assertIsFocused()
            remote(KeyEvent.KEYCODE_BACK)
            compose.onNodeWithTag("home-settings").assertIsFocused()

            compose.onNode(recommendation).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            val work = hasText("热门验收") and hasClickAction()
            compose.waitUntil(10_000) { compose.onAllNodes(work and isFocused()).fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(10_000) { !model.isSearching }
            val searchBounds = compose.onNodeWithContentDescription("搜索").fetchSemanticsNode().boundsInRoot
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText("播放 · 第一集") and isFocused()).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(searchBounds, compose.onNodeWithContentDescription("返回").fetchSemanticsNode().boundsInRoot)
            remote(KeyEvent.KEYCODE_DPAD_UP)
            compose.onNodeWithContentDescription("返回").assertIsFocused()
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(10_000) { compose.onAllNodes(work and isFocused()).fetchSemanticsNodes().isNotEmpty() }
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText("播放 · 第一集") and isFocused()).fetchSemanticsNodes().isNotEmpty()
            }
            remote(KeyEvent.KEYCODE_DPAD_DOWN)
            remote(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNode(hasText("第一集") and hasClickAction()).assertIsFocused()
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(15_000) { model.works.single().playback.isNotEmpty() }
            fun currentRoute() = compose.runOnIdle { navigation.currentDestination?.route }
            fun backTo(route: String) {
                repeat(5) {
                    if (currentRoute() == route) return
                    remote(KeyEvent.KEYCODE_BACK)
                }
                org.junit.Assert.assertEquals(route, currentRoute())
            }
            backTo(Screen.Home.route)
            val recent = compose.onNodeWithTag("recent-card")
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("recent-card")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(recommendation).assertIsFocused()
            recent.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(10_000) { navigation.currentDestination?.route == Screen.Player.route }
            assertTrue(model.works.isEmpty())
            org.junit.Assert.assertEquals("第一集", model.playbackSession?.episode)
            backTo(Screen.Home.route)
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasTestTag("recent-card") and isFocused()).fetchSemanticsNodes().isNotEmpty()
            }
            recent.assertIsFocused()
            val restingCard = recent.fetchSemanticsNode().boundsInRoot
            remote(KeyEvent.KEYCODE_DPAD_UP)
            compose.onNodeWithTag("home-search").assertIsFocused()
            remote(KeyEvent.KEYCODE_DPAD_DOWN)
            recent.assertIsFocused()
            org.junit.Assert.assertEquals(restingCard, recent.fetchSemanticsNode().boundsInRoot)
            captureWhenRequested("home-recent")
        } finally {
            compose.runOnIdle { store.clear() }
            media.delete()
        }
    }

}
