package art.anjing.tigertv.core

import kotlin.test.*
class DanmakuSelectionTest {
    @Test fun upgradesNeedBothGainsAndComparableDurations() {
        val track = DanmakuTrack(2_810_000, emptyList())
        assertTrue(shouldUpgradeDanmaku(track, track.copy(referenceDurationMs=2_806_720), 13_381,42_288))
        assertFalse(shouldUpgradeDanmaku(track,track,18_889,18_889))
        assertFalse(shouldUpgradeDanmaku(track,track,2,4))
        assertFalse(shouldUpgradeDanmaku(track,track,2_000,3_999))
        assertTrue(shouldUpgradeDanmaku(track,track,1_000,2_000))
        assertFalse(shouldUpgradeDanmaku(track,track.copy(referenceDurationMs=7_446_000),1_000,5_000))
        assertFalse(shouldUpgradeDanmaku(track,track.copy(referenceDurationMs=null),1_000,5_000))
    }
    @Test fun orderIsProbePriorityOnly() {
        assertEquals(listOf("iqiyi","youku","tencent","imgo","360"),listOf("360","tencent","imgo","youku","iqiyi").sortedBy(::danmakuProbePriority))
    }
}
