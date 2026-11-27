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
        for (label in listOf("番外1", "第2期加更", "SP01", "预告第1集", "2.5", "正片", "第1集花絮", "第3期上", "抢鲜版第3期", "第1期前采")) assertNull(regularDanmakuEpisode(label), label)
    }
    @Test fun duplicateOrUnknownMultiSeasonNumbersAreNotGuessed() {
        val first = DanmakuEpisode(1,"正片","2",1,"one")
        val second = DanmakuEpisode(2,"正片","2",2,"two")
        assertEquals(second, matchingDanmakuEpisode(listOf(first, second), 2, 2))
        val namedSecond = second.copy(title = "S02E02", number = "3", season = 1)
        assertEquals(namedSecond, matchingDanmakuEpisode(listOf(first, namedSecond), 2, 2))
        assertNull(matchingDanmakuEpisode(listOf(first.copy(season=null),second.copy(season=null)),2,2))
        assertNull(matchingDanmakuEpisode(listOf(first, first.copy(id=3)),2,1))
        val explicitlyNumbered = first.copy(id = 4, title = "第2集")
        assertEquals(explicitlyNumbered, matchingDanmakuEpisode(listOf(first, explicitlyNumbered), 2, 1))
        assertNull(matchingDanmakuEpisode(listOf(first),null,1))
        assertNull(matchingDanmakuEpisode(listOf(first.copy(title="Special 2")),2,1))
        assertNull(matchingDanmakuEpisode(listOf(first.copy(title="第3集")),2,1))
        assertNull(matchingDanmakuEpisode(listOf(first.copy(title="第2期上")),2,1))
    }

    @Test fun titleEvidenceTakesPriorityOverPlatformOrder() {
        assertEquals(0, danmakuTitleRank("《逐玉》", "逐玉（２０２６）【国产剧】from tencent", "2026"))
        assertEquals(1, danmakuTitleRank("逐玉", "【高清】逐玉", "2026"))
        assertNull(danmakuTitleRank("逐玉", "逐玉（2025）", "2026"))
        assertNull(danmakuTitleRank("后会友期", "后会友期【沉浸版】"))
        assertNull(danmakuTitleRank("示例", "抢鲜版【别名：示例】"))
        assertTrue(reasonableDanmakuTitle("《示例》 S02", "Example 第二季（又名：示例）【1080P】"))
        assertEquals("示例 S02", danmakuSearchKeyword("示例 S02（2026）【高清】"))
    }

    @Test fun varietyDatesAndTitleOrdinalsDisambiguateApiNumbers() {
        val first = DanmakuEpisode(11, "【imgo】 第1期：再见朋友！出发前采 2026-10-22期", "1")
        val second = DanmakuEpisode(12, "【imgo】 第2期：朋友再见！ 2026-10-22期", "2")
        val extra = DanmakuEpisode(13, "【imgo】 抢鲜版：下周正片抢先看 2026-10-23期", "3")
        val later = DanmakuEpisode(14, "【imgo】 第3期：正片 2026-11-05期", "5")
        val episodes = listOf(first, second, extra, later)
        assertEquals(first, matchingDanmakuEpisode(episodes, "第20261022期第01期", null))
        assertEquals(later, matchingDanmakuEpisode(episodes, "2026-11-05 第03期", null))
        assertNull(matchingDanmakuEpisode(episodes, "20261022期", null))
        assertEquals(extra, matchingDanmakuEpisode(episodes, "第20261023期抢鲜版", null))
        assertNull(matchingDanmakuEpisode(episodes, "20261022 第3期", null))
        assertNull(matchingDanmakuEpisode(episodes, "第3期上", null))
        assertEquals(2, regularDanmakuEpisode("Ｓ０１Ｅ０２"))
    }

    @Test fun datedSplitVarietyMatchesOnlyItsPart() {
        val upper = DanmakuEpisode(10233, "【imgo】 第6期上：我需要你看见我 2026-11-19期", "11")
        val lower = DanmakuEpisode(10234, "【imgo】 2026-11-19 第6期下：一切都是错的！", "12")
        val extra = DanmakuEpisode(10235, "【imgo】 第6期下加更：2026-11-19", "13")
        val episodes = listOf(upper, lower, extra)
        assertEquals(lower, matchingDanmakuEpisode(episodes, "第20261119期下", null))
        assertEquals(upper, matchingDanmakuEpisode(episodes, "第20261119期上", null))
        assertNull(matchingDanmakuEpisode(episodes, "第20261119期", null))
        assertNull(matchingDanmakuEpisode(episodes, "第20261119期下抢鲜版", null))
        assertNull(matchingDanmakuEpisode(episodes, "第20261112期下", null))
        assertNull(matchingDanmakuEpisode(episodes + lower.copy(id = 100), "第20261119期下", null))
    }
}
