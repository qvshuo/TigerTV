package art.anjing.tigertv.core

import kotlin.test.*
class DanmakuSelectionTest {
    @Test fun upgradesNeedTwiceAsManyScrollingCommentsAndComparableDurations() {
        val track = DanmakuTrack(2_810_000, emptyList())
        assertTrue(shouldUpgradeDanmaku(track, track.copy(referenceDurationMs=2_806_720), 13_381,42_288))
        assertTrue(shouldUpgradeDanmaku(track,track,2,4))
        assertFalse(shouldUpgradeDanmaku(track, track.copy(referenceDurationMs = 2_783_520), 5, 15))
        assertFalse(shouldUpgradeDanmaku(track,track,2,3))
        assertFalse(shouldUpgradeDanmaku(track,track,2_000,3_999))
        assertFalse(shouldUpgradeDanmaku(track,track.copy(referenceDurationMs=7_446_000),1_000,5_000))
        assertFalse(shouldUpgradeDanmaku(track,track.copy(referenceDurationMs=null),1_000,5_000))
        val scrolling = DanmakuComment(0,"普通",1,0xFFFFFF)
        val fixed = scrolling.copy(mode=5)
        assertFalse(shouldUpgradeDanmaku(track.copy(comments=listOf(scrolling)),track.copy(comments=listOf(scrolling,fixed,fixed))))
        assertTrue(shouldUpgradeDanmaku(track.copy(comments=listOf(scrolling)),track.copy(comments=listOf(scrolling,scrolling))))
    }
    @Test fun orderIsProbePriorityOnly() {
        assertEquals(listOf("iqiyi","youku","tencent","imgo","360"),listOf("360","tencent","imgo","youku","iqiyi").sortedBy(::danmakuProbePriority))
    }
}
