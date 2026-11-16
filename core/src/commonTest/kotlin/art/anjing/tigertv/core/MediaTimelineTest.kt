package art.anjing.tigertv.core

import kotlin.test.*

class MediaTimelineTest {
    @Test fun realIntervalsAndCollapsedBoundaries() {
        val timeline = MediaTimeline(listOf(MediaInterval(300880, 326640), MediaInterval(2428640, 2454400)))
        assertEquals(EffectivePosition(300879, false), timeline.videoToEffective(300879))
        assertEquals(EffectivePosition(300880, true), timeline.videoToEffective(310000))
        assertEquals(EffectivePosition(300880, false), timeline.videoToEffective(326640))
        assertEquals(326640L, timeline.effectiveToVideo(300880))
        assertEquals(EffectivePosition(2402880, true), timeline.videoToEffective(2430000))
        assertEquals(2454400L, timeline.effectiveToVideo(2402880))
        assertEquals(2448600L, timeline.videoToEffective(2500120).timeMs)
        assertEquals(2500120L, timeline.effectiveToVideo(2448600))
    }
    @Test fun normalizesAndDoesNotInventReferenceMapping() {
        val timeline = MediaTimeline(listOf(MediaInterval(20, 30), MediaInterval(10, 20), MediaInterval(10, 15)), listOf(ReferenceSpan(0, 10, 100, 110), ReferenceSpan(20, 30, 120, 140)))
        assertEquals(listOf(MediaInterval(10, 30)), timeline.ads)
        assertEquals(30L, timeline.effectiveToVideo(10))
        assertEquals(105L, timeline.effectiveToReference(5))
        assertNull(timeline.effectiveToReference(15))
        assertEquals(130L, timeline.effectiveToReference(25))
        assertEquals(25L, timeline.referenceToEffective(130))
        assertNull(timeline.referenceToEffective(115))
        assertNull(timeline.effectiveToReference(30))
        assertFailsWith<IllegalArgumentException> { MediaTimeline(emptyList(), listOf(ReferenceSpan(0, 10, 0, 10), ReferenceSpan(9, 20, 11, 20))) }
    }
    @Test fun seekAndEndBoundariesRemainOnOriginalTimeline() {
        val timeline = MediaTimeline(listOf(MediaInterval(10_000, 20_000), MediaInterval(20_000, 25_000)))
        assertEquals(EffectivePosition(9_999, false), timeline.videoToEffective(9_999))
        assertEquals(EffectivePosition(10_000, true), timeline.videoToEffective(10_000))
        assertEquals(EffectivePosition(10_000, false), timeline.videoToEffective(25_000))
        assertEquals(25_000L, timeline.effectiveToVideo(10_000))
        assertEquals(45_000L, timeline.videoToEffective(60_000).timeMs)
        assertEquals(60_000L, timeline.effectiveToVideo(45_000))
        assertNull(timeline.effectiveToReference(45_000))
    }

    @Test fun playbackClockUsesActualPositionAndRequiresReferenceAnchors() {
        val timeline = MediaTimeline(
            listOf(MediaInterval(300_880, 326_640), MediaInterval(2_428_640, 2_454_400)),
            listOf(ReferenceSpan(0, 3_000_000, 10_000, 3_010_000)),
        )
        // A paused or buffering player presents an unchanged currentPosition.
        val before = timeline.effectiveToReference(300_000)
        assertEquals(310_000L, before)
        assertEquals(before, timeline.effectiveToReference(300_000))
        // After a seek or speed change use the reported position, never elapsed wall time.
        assertEquals(2_410_000L, timeline.effectiveToReference(2_400_000))
        assertEquals(2_410_500L, timeline.effectiveToReference(2_400_500))
        assertNull(MediaTimeline(emptyList()).effectiveToReference(300_000))
        assertEquals(2_454_400L, timeline.effectiveToVideo(2_402_880))
    }

}
