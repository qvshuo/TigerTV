package art.anjing.tigertv

import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import art.anjing.tigertv.R
import art.anjing.tigertv.ui.components.ActionButton
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import art.anjing.tigertv.ui.detail.DetailChoice
import art.anjing.tigertv.ui.home.SearchHistoryRow
import art.anjing.tigertv.ui.theme.TigerTVTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DetailChoiceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun labelsRemainVisibleCentredAndStableInAllDetailLayouts() {
        checkDetailLayouts(fontScale = 1f)
    }

    @Test fun enlargedLabelsRemainVisibleCentredAndStableInAllDetailLayouts() {
        checkDetailLayouts(fontScale = 1.3f)
    }

    @Test fun enlargedHistoryLabelsFitTheirNativeControls() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                TigerTVTheme {
                    Box(Modifier.fillMaxSize().padding(32.dp)) {
                        SearchHistoryRow(listOf("逐玉"), {}, {}, {})
                    }
                }
            }
        }
        listOf("逐玉", "清空历史").forEach { title ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(title, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertFalse("Text was clipped: $title", layouts.single().hasVisualOverflow)
        }
    }

    @Test fun actionButtonContentsStayCentredWhenMinimumWidthExceedsContent() {
        checkActionButtons(fontScale = 1f)
    }

    @Test fun enlargedActionButtonContentsStayCentred() {
        checkActionButtons(fontScale = 1.3f)
    }

    private fun checkActionButtons(fontScale: Float) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                TigerTVTheme {
                    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        ActionButton(onClick = {}, modifier = Modifier.widthIn(min = 144.dp).testTag("retry-text")) {
                            Text("重试", Modifier.testTag("retry-text-label"))
                        }
                        ActionButton(onClick = {}, modifier = Modifier.widthIn(min = 144.dp).testTag("retry-icon")) {
                            Icon(painterResource(R.drawable.player_refresh), contentDescription = null,
                                modifier = Modifier.size(24.dp).testTag("retry-icon-image"))
                            Spacer(Modifier.width(8.dp))
                            Text("重试", Modifier.testTag("retry-icon-label"))
                        }
                        ActionButton(onClick = {}, modifier = Modifier.testTag("natural-action")) {
                            Text("播放 · 第一集")
                        }
                    }
                }
            }
        }
        fun checkContents(tag: String, withIcon: Boolean) {
            val label = compose.onNodeWithTag("$tag-label", useUnmergedTree = true)
            val layouts = mutableListOf<TextLayoutResult>()
            label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertFalse("Label is clipped", layout.hasVisualOverflow)
            val labelBounds = label.fetchSemanticsNode().boundsInRoot
            val scale = labelBounds.width / layout.size.width
            val textLeft = labelBounds.left + layout.getLineLeft(0) * scale
            val textRight = labelBounds.left + layout.getLineRight(0) * scale
            val buttonBounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            val contentLeft = if (withIcon) compose.onNodeWithTag("$tag-image", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot.left else textLeft
            assertEquals("Content is not horizontally centred: $tag", buttonBounds.center.x, (contentLeft + textRight) / 2, 1f)
            assertEquals("Label is not vertically centred: $tag", buttonBounds.center.y, labelBounds.center.y, 1f)
        }
        listOf("retry-text", "retry-icon").forEach { tag ->
            checkContents(tag, withIcon = tag == "retry-icon")
            compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            compose.mainClock.advanceTimeBy(500)
            checkContents(tag, withIcon = tag == "retry-icon")
        }
        val naturalWidth = compose.onNodeWithTag("natural-action").fetchSemanticsNode().boundsInRoot.width
        val screenWidth = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        assertTrue("Natural-width actions must not stretch across the screen", naturalWidth < screenWidth / 2)
    }

    private fun checkDetailLayouts(fontScale: Float) {
        val selected = mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                TigerTVTheme {
                    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            DetailChoice("🎬测试资源", selected.value) { selected.value = !selected.value }
                            DetailChoice("🎬另一资源", false) {}
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(8.dp)) {
                            item { DetailChoice("主线路", selected.value) { selected.value = !selected.value } }
                            item { DetailChoice("备用线路", false) {} }
                        }
                        DetailChoice("第十二集：再次相逢", selected.value, Modifier.width(176.dp), maxLines = 2) {
                            selected.value = !selected.value
                        }
                    }
                }
            }
        }
        val titles = listOf("🎬测试资源", "主线路", "第十二集：再次相逢")
        val initialWidths = titles.associateWith { button(it).fetchSemanticsNode().size.width }
        fun checkLabels() {
            titles.forEach { title ->
                val node = compose.onNodeWithText(title, useUnmergedTree = true)
                node.assertIsDisplayed()
                val layouts = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                assertFalse("Text was clipped: $title", layout.hasVisualOverflow)
                assertTrue("Text has no width: $title", layout.size.width > 0)
                val textBounds = node.fetchSemanticsNode().boundsInRoot
                val glyphCentre = textBounds.left + (layout.getLineLeft(0) + layout.getLineRight(0)) *
                    (textBounds.right - textBounds.left) / (2 * layout.size.width)
                val buttonBounds = button(title).fetchSemanticsNode().boundsInRoot
                assertEquals("Label is not centred: $title", (buttonBounds.left + buttonBounds.right) / 2, glyphCentre, 1f)
                assertEquals("Label is not vertically centred: $title", buttonBounds.center.y, textBounds.center.y, 1f)
                assertEquals(initialWidths.getValue(title), button(title).fetchSemanticsNode().size.width)
            }
        }
        checkLabels()
        activate("主线路")
        button("主线路").assertIsSelected()
        checkLabels()
        activate("🎬测试资源")
        button("主线路").assertIsNotSelected()
        checkLabels()
    }

    private fun activate(title: String) {
        button(title).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
    }

    private fun button(title: String) = compose.onNode(hasText(title) and hasClickAction())
}
