package art.anjing.tigertv

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
import org.junit.Rule
import org.junit.Assert.assertTrue
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

}
