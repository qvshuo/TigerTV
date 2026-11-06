package art.anjing.tigertv

import android.view.KeyEvent
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.Text
import art.anjing.tigertv.ui.components.ActionButton
import art.anjing.tigertv.ui.home.SearchInput
import art.anjing.tigertv.ui.requestFocusSafely
import art.anjing.tigertv.ui.theme.TigerTVTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchRemoteTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun browseEditDismissAndSearchWithTheRemote() {
        val keyword = mutableStateOf("")
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        var searches = 0
        compose.setContent {
            TigerTVTheme {
                val inputFocus = remember { FocusRequester() }
                Column(Modifier.fillMaxSize()) {
                    SearchInput(keyword.value, { keyword.value = it }, { searches++ }, inputFocus)
                    ActionButton(onClick = {}) { Text("国产剧") }
                }
                LaunchedEffect(Unit) { requestFocusSafely(inputFocus) }
            }
        }
        val input = compose.onNode(hasSetTextAction())
        input.assertIsFocused()
        assertFalse("Browsing must not open the keyboard", keyboardVisible())
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("国产剧").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_UP)
        input.assertIsFocused()
        assertFalse(keyboardVisible())
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { keyboardVisible() }
        input.performTextInput("逐玉")
        compose.waitUntil { keyword.value == "逐玉" }
        remote(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { !keyboardVisible() }
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { keyboardVisible() }
        remote(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { !keyboardVisible() }
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("国产剧").assertIsFocused()
        compose.runOnIdle { keyword.value = "庆余年" }
        input.assertTextContains("庆余年")
        remote(KeyEvent.KEYCODE_DPAD_UP)
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { keyboardVisible() }
        input.performImeAction()
        compose.waitUntil(5_000) { !keyboardVisible() }
        compose.runOnIdle { assertEquals(1, searches) }
    }

    private fun remote(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        compose.waitForIdle()
    }

    private fun keyboardVisible() = instrumentation.uiAutomation.windows.any {
        it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
    }
}
