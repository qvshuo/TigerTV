package art.anjing.tigertv.core

import kotlin.test.*
class DanmakuMatchingTest {
    @Test fun titleAndSeasonMustAgree() {
        assertTrue(reasonableDanmakuTitle("嫁金钗", "嫁金钗(2026)【国产剧】from youku"))
        assertTrue(reasonableDanmakuTitle("示例 第二季", "示例 S02(2026)【国产剧】"))
        assertFalse(reasonableDanmakuTitle("示例", "示例第二季"))
        assertFalse(reasonableDanmakuTitle("示例", "示例2"))
        assertFalse(reasonableDanmakuTitle("示例(2020)", "示例(2026)"))
        assertFalse(reasonableDanmakuTitle("示例", "示例前传"))
    }
    @Test fun ordinalsAreSemanticAndSpecialsStayManual() {
        for ((label,n) in listOf("第02集" to 2,"第二十一集" to 21,"S02E03" to 3,"Episode 4" to 4,"05" to 5)) assertEquals(n, regularDanmakuEpisode(label), label)
        for (label in listOf("番外1", "第2期加更", "SP01", "预告第1集", "2.5", "正片", "第1集花絮")) assertNull(regularDanmakuEpisode(label), label)
    }
    @Test fun duplicateOrUnknownMultiSeasonNumbersAreNotGuessed() {
        val first = DanmakuEpisode(1,"正片","2",1,"one")
        val second = DanmakuEpisode(2,"正片","2",2,"two")
        assertEquals(second, matchingDanmakuEpisode(listOf(first,second),2,2))
        assertNull(matchingDanmakuEpisode(listOf(first.copy(season=null),second.copy(season=null)),2,2))
        assertNull(matchingDanmakuEpisode(listOf(first, first.copy(id=3)),2,1))
        assertNull(matchingDanmakuEpisode(listOf(first),null,1))
        assertNull(matchingDanmakuEpisode(listOf(first.copy(title="Special 2")),2,1))
    }
}
