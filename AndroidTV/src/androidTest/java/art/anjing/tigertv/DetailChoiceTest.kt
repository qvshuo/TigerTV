package art.anjing.tigertv

import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import art.anjing.tigertv.ui.episodes.DetailChoice
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
