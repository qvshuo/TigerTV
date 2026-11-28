package art.anjing.tigertv

import android.view.KeyEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.Surface
import art.anjing.tigertv.core.*
import art.anjing.tigertv.data.TigerTVRepository
import art.anjing.tigertv.ui.detail.DetailScreen
import art.anjing.tigertv.ui.theme.TigerTVTheme
import art.anjing.tigertv.ui.viewmodel.TigerTVViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DetailSourceScrollTest {
    @get:Rule val compose = createComposeRule()

    @Test fun switchingSourcesKeepsTheFocusedResourceAtTheSameViewportPosition() = checkSourceSwitch(grouped = true)
    @Test fun shorterSynopsisKeepsTheResourcePosition() = checkSourceSwitch(shortSynopsis = true)
    @Test fun failedSourceKeepsTheResourceFocusUntilRemoteNavigation() = checkSourceSwitch(failDetail = true)
    @Test fun lateSourceResponseDoesNotUndoNewRemoteNavigation() = checkSourceSwitch(navigateWhileLoading = true)
    @Test fun longerSynopsisKeepsTheResourcePosition() = checkSourceSwitch(startsShort = true)
    @Test fun enlargedTextKeepsSourcePositionWithAShorterSynopsis() = checkSourceSwitch(shortSynopsis = true, fontScale = 1.3f)
    @Test fun emptySourceIsDisabled() = checkSourceSwitch(shortSynopsis = true, emptyEpisodes = true)
    @Test fun repeatedRetryFailureRestoresRetryFocus() = checkSourceSwitch(failDetail = true, retryAgain = true)
    @Test fun lateRetryFailureDoesNotStealFocusAfterRemoteNavigation() = checkSourceSwitch(failDetail = true, retryAgain = true, navigateDuringRetry = true)

    @Test fun rapidEpisodeNavigationKeepsFocusInLongLists() = checkSourceSwitch(rapidEpisodes = true)

    private fun checkSourceSwitch(shortSynopsis: Boolean = false, failDetail: Boolean = false, navigateWhileLoading: Boolean = false, startsShort: Boolean = false, fontScale: Float = 1f, emptyEpisodes: Boolean = false, retryAgain: Boolean = false, navigateDuringRetry: Boolean = false, rapidEpisodes: Boolean = false, grouped: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val recommendation = if (grouped) ""","recommendation":"ad_filter_verified"""" else ""
        val files = ConcurrentHashMap(mapOf("source" to """{"api_site":{"a":{"name":"🎬源甲","api":"https://a.test/api"$recommendation},"b":{"name":"🎬源乙","api":"https://b.test/api"},"c":{"name":"🎬源丙","api":"https://c.test/api"}}}"""))
        val storage = object : Storage {
            override fun read(path: String) = files[path]
            override fun writeAtomic(path: String, text: String) { files[path] = text }
        }
        val releaseDetail = CompletableDeferred<Unit>()
        val releaseRetry = CompletableDeferred<Unit>()
        var secondSourceRequests = 0
        val episodes = (1..if (rapidEpisodes) 120 else 30).joinToString("#") {
            val name = if (rapidEpisodes && it % 3 == 0) "第${it}集：这是一段用于验证长标题跨列显示的剧集原名" else "第${it}集"
            "$name${'$'}https://media.test/$it.m3u8"
        }
        val longSynopsis = "这是一段用于验证切源时保持资源位置的剧情简介。".repeat(80)
        val synopsis = if (startsShort) "简短介绍" else longSynopsis
        val core = CoreService("cache", "source", storage, configureClient(HttpClient(MockEngine { request ->
            if (request.url.host == "c.test") return@MockEngine respond("""{"code":1,"list":[]}""")
            val secondDetail = request.url.parameters["ids"] != null && request.url.host == "b.test"
            if (secondDetail) {
                if (++secondSourceRequests == 1) releaseDetail.await() else releaseRetry.await()
            }
            val content = if (secondDetail) { if (shortSynopsis) "简短介绍" else longSynopsis } else synopsis
            if (secondDetail && failDetail) respond("""{"code":0,"msg":"fixture failure","list":[]}""")
            else respond("""{"code":1,"list":[{"vod_id":1,"vod_name":"切源测试","vod_year":"2026","vod_director":"导演甲","vod_actor":"主演甲","vod_content":"$content","vod_play_from":"主线","vod_play_url":"${if (secondDetail && emptyEpisodes) "" else episodes}"}]}""")
        })))
        val store = ViewModelStore()
        lateinit var model: TigerTVViewModel
        compose.runOnIdle {
            model = TigerTVViewModel(TigerTVRepository(core), instrumentation.targetContext)
            store.put("detail", model)
            model.onKeywordChange("切源测试")
            model.search()
        }
        compose.waitUntil(10_000) { !model.isSearching && model.works.isNotEmpty() }
        compose.runOnIdle { model.selectResult(model.works.single().sources.first { it.site == "🎬源甲" }) }
        compose.waitUntil(10_000) { !model.isFetching && model.fetchResponse != null }
        try {
            compose.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    TigerTVTheme {
                        Surface(Modifier.fillMaxSize()) { DetailScreen(model, {}, {}) }
                    }
                }
            }
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("播放 · 第1集") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
            if (rapidEpisodes) {
                val down = KeyEvent.KEYCODE_DPAD_DOWN
                val up = KeyEvent.KEYCODE_DPAD_UP
                repeat(2) { instrumentation.sendKeyDownUpSync(down); compose.waitForIdle() }
                compose.onNode(hasText("第1集") and hasClickAction()).assertIsFocused()
                val stats = InstrumentationRegistry.getArguments().getString("measure_episode_focus") == "true"
                fun gfx(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand("dumpsys gfxinfo ${instrumentation.targetContext.packageName} $command")
                ).bufferedReader().use { it.readText() }
                if (stats) gfx("reset")
                val timings = mutableListOf<Long>()
                fun send(key: Int) {
                    val start = android.os.SystemClock.uptimeMillis()
                    instrumentation.sendKeyDownUpSync(key)
                    val latency = android.os.SystemClock.uptimeMillis() - start
                    timings += latency
                    android.os.SystemClock.sleep((35 - latency).coerceAtLeast(0))
                }
                repeat(18) { send(down) }
                compose.waitForIdle()
                val focused = compose.onNode(isFocused()).fetchSemanticsNode().config[SemanticsProperties.Text].single().text
                val episode = Regex("^第([0-9]+)集").find(focused)?.groupValues?.get(1)?.toIntOrNull()
                assertTrue("Expected focus several rows into the episode grid: $focused", episode != null && episode >= 17)
                repeat(18) { send(up) }
                compose.waitForIdle()
                val returned = compose.onNode(isFocused()).fetchSemanticsNode().config[SemanticsProperties.Text].single().text
                if (stats) {
                    val sorted = timings.sorted()
                    val result = gfx("framestats").lineSequence().filter {
                        it.startsWith("Total frames rendered:") || it.startsWith("Janky frames:") ||
                            Regex("^(50|90|95|99)th percentile:").containsMatchIn(it)
                    }.take(6).joinToString(", ")
                    android.util.Log.i("TigerTVEpisodeFocus", "keys=${sorted.size}, keyDispatchMsP50=${sorted[sorted.size / 2]}, keyDispatchMsP95=${sorted[sorted.size * 95 / 100]}, down=$focused, returned=$returned, $result")
                }
                assertTrue("Focus was lost after reversing: $returned",
                    Regex("^(?:播放 · )?第[1-9]集").containsMatchIn(returned) || returned == "源甲")
                val grid = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
                grid.performScrollToNode(hasText("第113集"))
                compose.onNode(hasText("第113集") and hasClickAction())
                    .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                instrumentation.sendKeyDownUpSync(down)
                compose.mainClock.advanceTimeBy(600)
                compose.waitForIdle()
                val lastRow = compose.onNode(isFocused()).fetchSemanticsNode()
                val label = lastRow.config[SemanticsProperties.Text].single().text
                assertTrue("Expected focus on the last episode row: $label", Regex("^第(?:11[7-9]|120)集").containsMatchIn(label))
                val rowBottom = lastRow.boundsInRoot.bottom
                val gridBottom = grid.fetchSemanticsNode().boundsInRoot.bottom
                assertTrue("Focused last-row button needs room for its border: $rowBottom / $gridBottom", rowBottom < gridBottom)
                assertNull(model.playbackUrl)
                return
            }
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(synopsis, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertFalse("Synopsis must display its full text", layouts.single().hasVisualOverflow)
            if (!startsShort) assertTrue("Long synopsis must remain expanded", layouts.single().lineCount > 3)
            compose.onNodeWithText("展开").assertDoesNotExist()
            compose.onNodeWithText("收起").assertDoesNotExist()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.waitForIdle()
            compose.onNode(hasText("源甲") and hasClickAction()).assertIsFocused()
            if (grouped) {
                compose.onNodeWithText("推荐资源").assertExists()
                compose.onNodeWithText("其他资源").assertExists()
            }
            instrumentation.sendKeyDownUpSync(if (grouped) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.waitForIdle()
            val source = compose.onNode(hasText("源乙") and hasClickAction())
            source.assertIsFocused()
            compose.onNodeWithText("源丙").assertIsDisplayed().assertIsNotEnabled()
            fun scrollPosition() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
                .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            val before = source.fetchSemanticsNode().boundsInRoot
            val beforeScroll = scrollPosition()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5_000) { model.isFetching }
            compose.waitForIdle()
            source.assertIsFocused().assertIsDisplayed()
            val loading = source.fetchSemanticsNode().boundsInRoot
            val loadingScroll = scrollPosition()
            assertEquals("Loading moved the focused resource", before.top, loading.top, 2f)
            if (navigateWhileLoading) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
                compose.waitForIdle()
                compose.onNode(hasText("源甲") and hasClickAction()).assertIsFocused()
            }
            releaseDetail.complete(Unit)
            compose.waitUntil(10_000) { !model.isFetching && (model.fetchResponse?.site == "🎬源乙" || model.fetchErrorMessage != null) }
            compose.waitForIdle()
            if (navigateWhileLoading) {
                compose.onNode(hasText("源甲") and hasClickAction()).assertIsFocused().assertIsDisplayed()
                assertNull(model.playbackUrl)
                return
            }
            if (emptyEpisodes) source.assertIsNotEnabled() else source.assertIsFocused()
            source.assertIsDisplayed().assertIsSelected()
            val loaded = source.fetchSemanticsNode().boundsInRoot
            assertEquals("Detail completion moved the focused resource ($beforeScroll -> $loadingScroll -> ${scrollPosition()})", before.top, loaded.top, 2f)
            if (emptyEpisodes) {
                compose.onNodeWithText("暂无剧集").assertIsDisplayed()
                assertTrue(model.episodes.isEmpty())
                assertNull(model.playbackUrl)
                return
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.waitForIdle()
            if (failDetail) {
                compose.onNodeWithText("重试").assertIsFocused().assertIsDisplayed()
            } else {
                compose.onNode(hasText("集", substring = true) and hasClickAction() and isFocused()).assertIsDisplayed()
                compose.onNodeWithText("主线").assertDoesNotExist()
            }
            if (retryAgain) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.waitUntil(5_000) { model.isFetching }
                compose.waitForIdle()
                var navigatedLabel: String? = null
                if (navigateDuringRetry) {
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
                    compose.waitForIdle()
                    val focused = compose.onNode(isFocused()).assertIsDisplayed().fetchSemanticsNode()
                    navigatedLabel = focused.config[SemanticsProperties.Text].single().text
                }
                releaseRetry.complete(Unit)
                compose.waitUntil(10_000) { !model.isFetching && model.fetchErrorMessage != null }
                compose.waitForIdle()
                if (navigateDuringRetry) compose.onNode(hasText(navigatedLabel!!) and isFocused()).assertIsDisplayed()
                else compose.onNodeWithText("重试").assertIsFocused().assertIsDisplayed()
            }
            assertNull(model.playbackUrl)
        } finally {
            releaseDetail.complete(Unit)
            releaseRetry.complete(Unit)
            compose.runOnIdle { store.clear() }
        }
    }
}
