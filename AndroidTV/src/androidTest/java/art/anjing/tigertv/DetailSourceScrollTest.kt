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

    @Test fun switchingSourcesKeepsTheFocusedResourceAtTheSameViewportPosition() = checkSourceSwitch()
    @Test fun shorterSynopsisKeepsTheResourcePosition() = checkSourceSwitch(shortSynopsis = true)
    @Test fun failedSourceKeepsTheResourceFocusUntilRemoteNavigation() = checkSourceSwitch(failDetail = true)
    @Test fun lateSourceResponseDoesNotUndoNewRemoteNavigation() = checkSourceSwitch(navigateWhileLoading = true)
    @Test fun longerSynopsisKeepsTheResourcePosition() = checkSourceSwitch(startsShort = true)
    @Test fun enlargedTextKeepsSourcePositionWithAShorterSynopsis() = checkSourceSwitch(shortSynopsis = true, fontScale = 1.3f)
    @Test fun emptySourceKeepsTheResourcePosition() = checkSourceSwitch(shortSynopsis = true, emptyEpisodes = true)
    @Test fun repeatedRetryFailureRestoresRetryFocus() = checkSourceSwitch(failDetail = true, retryAgain = true)
    @Test fun lateRetryFailureDoesNotStealFocusAfterRemoteNavigation() = checkSourceSwitch(failDetail = true, retryAgain = true, navigateDuringRetry = true)

    private fun checkSourceSwitch(shortSynopsis: Boolean = false, failDetail: Boolean = false, navigateWhileLoading: Boolean = false, startsShort: Boolean = false, fontScale: Float = 1f, emptyEpisodes: Boolean = false, retryAgain: Boolean = false, navigateDuringRetry: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val files = ConcurrentHashMap(mapOf("source" to """{"api_site":{"a":{"name":"🎬源甲","api":"https://a.test/api"},"b":{"name":"🎬源乙","api":"https://b.test/api"}}}"""))
        val storage = object : Storage {
            override fun read(path: String) = files[path]
            override fun writeAtomic(path: String, text: String) { files[path] = text }
        }
        val releaseDetail = CompletableDeferred<Unit>()
        val releaseRetry = CompletableDeferred<Unit>()
        var secondSourceRequests = 0
        val episodes = (1..30).joinToString("#") { "第${it}集${'$'}https://media.test/$it.m3u8" }
        val longSynopsis = "这是一段用于验证切源时保持资源位置的剧情简介。".repeat(80)
        val synopsis = if (startsShort) "简短介绍" else longSynopsis
        val core = CoreService("cache", "source", storage, configureClient(HttpClient(MockEngine { request ->
            val secondDetail = request.url.parameters["ids"] != null && request.url.host == "b.test"
            if (secondDetail) {
                if (++secondSourceRequests == 1) releaseDetail.await() else releaseRetry.await()
            }
            val content = if (secondDetail) { if (shortSynopsis) "简短介绍" else longSynopsis } else synopsis
            if (secondDetail && failDetail) respond("""{"code":0,"msg":"fixture failure","list":[]}""")
            else respond("""{"code":1,"list":[{"vod_id":1,"vod_name":"切源测试","vod_director":"导演甲","vod_actor":"主演甲","vod_content":"$content","vod_play_from":"主线","vod_play_url":"${if (secondDetail && emptyEpisodes) "" else episodes}"}]}""")
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
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(synopsis, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertFalse("Synopsis must display its full text", layouts.single().hasVisualOverflow)
            if (!startsShort) assertTrue("Long synopsis must remain expanded", layouts.single().lineCount > 3)
            compose.onNodeWithText("展开").assertDoesNotExist()
            compose.onNodeWithText("收起").assertDoesNotExist()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.waitForIdle()
            compose.onNode(hasText("🎬源甲") and hasClickAction()).assertIsFocused()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.waitForIdle()
            val source = compose.onNode(hasText("🎬源乙") and hasClickAction())
            source.assertIsFocused()
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
                compose.onNode(hasText("🎬源甲") and hasClickAction()).assertIsFocused()
            }
            releaseDetail.complete(Unit)
            compose.waitUntil(10_000) { !model.isFetching && (model.fetchResponse?.site == "🎬源乙" || model.fetchErrorMessage != null) }
            compose.waitForIdle()
            if (navigateWhileLoading) {
                compose.onNode(hasText("🎬源甲") and hasClickAction()).assertIsFocused().assertIsDisplayed()
                assertNull(model.playbackUrl)
                return
            }
            source.assertIsFocused().assertIsDisplayed().assertIsSelected()
            val loaded = source.fetchSemanticsNode().boundsInRoot
            assertEquals("Detail completion moved the focused resource ($beforeScroll -> $loadingScroll -> ${scrollPosition()})", before.top, loaded.top, 2f)
            if (emptyEpisodes) {
                compose.onNodeWithText("暂无播放内容").assertIsDisplayed()
                assertTrue(model.episodes.isEmpty())
                assertNull(model.playbackUrl)
                return
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.waitForIdle()
            compose.onNodeWithText(if (failDetail) "重试" else "主线").assertIsFocused().assertIsDisplayed()
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
