package art.anjing.tigertv

import art.anjing.tigertv.domain.SearchResult
import art.anjing.tigertv.domain.mergeSearchResults
import art.anjing.tigertv.domain.normalizeVodName
import art.anjing.tigertv.domain.vodYear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMergerTest {

    private fun make(
        site: String,
        id: Int,
        name: String,
        time: String = "",
        remarks: String = "",
        pic: String = ""
    ) = SearchResult(site, id, name, vodTime = time, vodRemarks = remarks, vodPic = pic)

    // MARK: 归一化与年份

    @Test
    fun `normalize trims whitespace case and full-width`() {
        assertEquals("逐玉", normalizeVodName("  逐　玉 "))
        assertEquals("fullwidth123", normalizeVodName("Ｆｕｌｌ Ｗｉｄｔｈ １２３"))
        assertEquals("foobar", normalizeVodName("Foo Bar"))
    }

    @Test
    fun `vod year extraction`() {
        assertEquals("2026", vodYear("2026-01-01"))
        assertEquals("2025", vodYear("2025"))
        assertEquals("", vodYear(""))
        assertEquals("", vodYear("abc-2026"))
        assertEquals("", vodYear("20 26"))
    }

    // MARK: 合并行为

    @Test
    fun `same name same year merges`() {
        val a = make("🎬-爱奇艺-", 1, "逐玉", time = "2026-01-01")
        val b = make("🎬金鹰点播", 2, "逐玉", time = "2026-01-02")
        val merged = mergeSearchResults(listOf(a, b))
        assertEquals(1, merged.size)
        assertEquals(2, merged[0].sources.size)
        assertEquals(1, merged[0].extraCount)
    }

    @Test
    fun `same name different year does not merge`() {
        val a = make("🎬A", 1, "逐玉", time = "2026-01-01")
        val b = make("🎬B", 2, "逐玉", time = "2020-01-01")
        assertEquals(2, mergeSearchResults(listOf(a, b)).size)
    }

    @Test
    fun `same name missing year merges by name only`() {
        val a = make("🎬A", 1, "逐玉")
        val b = make("🎬B", 2, " 逐　玉 ")
        val merged = mergeSearchResults(listOf(a, b))
        assertEquals(1, merged.size)
        assertEquals(2, merged[0].sources.size)
    }

    @Test
    fun `distinct names do not merge`() {
        val a = make("🎬A", 1, "逐玉")
        val b = make("🎬B", 2, "苍兰诀")
        assertEquals(2, mergeSearchResults(listOf(a, b)).size)
    }

    // MARK: 主源选择

    @Test
    fun `primary prefers cover`() {
        val noCover = make("🎬A", 1, "逐玉", time = "2026-01-09", remarks = "完结")
        val withCover = make("🎬B", 2, "逐玉", time = "2026-01-01", pic = "https://p/x.jpg")
        assertEquals("🎬B", mergeSearchResults(listOf(noCover, withCover))[0].primary.site)
    }

    @Test
    fun `primary prefers remarks when no cover`() {
        val noRemarks = make("🎬A", 1, "逐玉", time = "2026-01-09")
        val withRemarks = make("🎬B", 2, "逐玉", time = "2026-01-01", remarks = "更新至10集")
        assertEquals("🎬B", mergeSearchResults(listOf(noRemarks, withRemarks))[0].primary.site)
    }

    @Test
    fun `primary prefers newer time when tied`() {
        val older = make("🎬A", 1, "逐玉", time = "2025-12-28", remarks = "完结")
        val newer = make("🎬B", 2, "逐玉", time = "2026-01-01", remarks = "完结")
        assertEquals("🎬B", mergeSearchResults(listOf(older, newer))[0].primary.site)
    }

    @Test
    fun `primary falls back to first when fully tied`() {
        val first = make("🎬A", 1, "逐玉")
        val second = make("🎬B", 2, "逐玉")
        assertEquals("🎬A", mergeSearchResults(listOf(first, second))[0].primary.site)
    }

    // MARK: 顺序与透传

    @Test
    fun `group order follows first appearance`() {
        val a1 = make("🎬A", 1, "逐玉")
        val b1 = make("🎬B", 2, "苍兰诀")
        val a2 = make("🎬C", 3, "逐玉")
        val merged = mergeSearchResults(listOf(a1, b1, a2))
        assertEquals(2, merged.size)
        assertEquals("逐玉", merged[0].primary.vodName)
        assertEquals(2, merged[0].sources.size)
        assertEquals("苍兰诀", merged[1].primary.vodName)
    }

    @Test
    fun `single source group passthrough`() {
        val a = make("🎬A", 1, "逐玉")
        val merged = mergeSearchResults(listOf(a))
        assertEquals(1, merged.size)
        assertEquals(0, merged[0].extraCount)
        assertEquals(a, merged[0].primary)
        assertEquals(listOf(a), merged[0].sources)
    }

    @Test
    fun `empty input`() {
        assertTrue(mergeSearchResults(emptyList()).isEmpty())
    }
}
