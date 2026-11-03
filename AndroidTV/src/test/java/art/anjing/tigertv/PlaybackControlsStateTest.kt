package art.anjing.tigertv

import art.anjing.tigertv.ui.player.PlaybackControlsState
import art.anjing.tigertv.ui.player.formatPlaybackTime
import org.junit.Assert.*
import org.junit.Test

class PlaybackControlsStateTest {
    private fun PlaybackControlsState.right(now: Long, repeated: Boolean = false, playing: Boolean = true) =
        adjustSeek(1, 50_000, 3_600_000, playing, 22, now, repeated)

    @Test fun seekingPreviewsAccumulateWithoutChangingTheOriginalPlaybackIntent() {
        val first = PlaybackControlsState().right(100, playing = true)
        val repeated = first.right(250, repeated = true, playing = false)
        assertEquals(50_000L, repeated.seek?.originMillis)
        assertEquals(70_000L, repeated.seek?.targetMillis)
        assertTrue(requireNotNull(repeated.seek).wasPlaying)
        // A pause caused by scrubbing must not replace the original playback intention.
        assertFalse(PlaybackControlsState().right(100, playing = false).right(250, true).seek!!.wasPlaying)
        assertNull(repeated.copy(seek = null).seek)
    }

    @Test fun holdingAcceleratesAndThrottlesRemoteRepeatEvents() {
        var controls = PlaybackControlsState().right(0)
        assertEquals(60_000L, controls.seek?.targetMillis)
        controls = controls.right(50, repeated = true)
        assertEquals(60_000L, controls.seek?.targetMillis)
        controls = controls.right(1_500, repeated = true)
        assertEquals(90_000L, controls.seek?.targetMillis)
        controls = controls.right(4_000, repeated = true)
        assertEquals(150_000L, controls.seek?.targetMillis)
    }

    @Test fun seekingClampsAtBothEndsAndUnknownDurationCannotCreateAPreview() {
        val forward = PlaybackControlsState().adjustSeek(1, 95_000, 100_000, true, 22, 0, false)
        assertEquals(99_000L, forward.seek?.targetMillis)
        val backward = PlaybackControlsState().adjustSeek(-1, 3_000, 100_000, true, 21, 0, false)
        assertEquals(0L, backward.seek?.targetMillis)
        assertNull(PlaybackControlsState().adjustSeek(1, 0, 0, true, 22, 0, false).seek)
    }

    @Test fun autoHideRespectsPauseMenusSeekingAndFreshInteraction() {
        val controls = PlaybackControlsState().show(1_000)
        assertFalse(controls.shouldAutoHide(5_999, true))
        assertTrue(controls.shouldAutoHide(6_000, true))
        assertFalse(controls.shouldAutoHide(60_000, false))
        assertFalse(controls.copy(speedMenuOpen = true).shouldAutoHide(60_000, true))
        assertFalse(controls.right(1_000).shouldAutoHide(60_000, true))
        assertFalse(controls.show(59_000).shouldAutoHide(60_000, true))
        assertFalse(controls.hide().visible)
    }

    @Test fun timestampsSupportLongMoviesAndInvalidNativeTimes() {
        assertEquals("00:00", formatPlaybackTime(-1))
        assertEquals("12:34", formatPlaybackTime(754_000))
        assertEquals("2:03:04", formatPlaybackTime(7_384_000))
    }
}
