package art.anjing.tigertv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import art.anjing.tigertv.ui.player.Media3Controller
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerInputTest {
    @Test fun invalidAndUnsupportedInputsFailWithoutLeavingMediaOrWritingProgress() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val controller = Media3Controller(instrumentation.targetContext)
            try {
                // DASH is deliberately not installed; source creation throws synchronously.
                for (url in listOf("", "   ", "relative/video.mp4", "https://", "data:text/plain,video", "https://fixture.test/video.mpd")) {
                    var error: String? = null
                    controller.load(url, 0, "fixture",
                        onStarted = { fail("Invalid input started playback") },
                        onPosition = { fail("Invalid input wrote progress") },
                        onError = { error = it })
                    assertNotNull("Expected playback error for $url", error)
                    assertEquals(0, controller.player.mediaItemCount)
                }
            } finally {
                controller.close()
            }
            controller.load("https://fixture.test/video.mp4", 0, "closed",
                onStarted = { fail("Closed player started playback") },
                onPosition = { fail("Closed player wrote progress") },
                onError = { fail("Closed player accepted a load") })
        }
    }
}
