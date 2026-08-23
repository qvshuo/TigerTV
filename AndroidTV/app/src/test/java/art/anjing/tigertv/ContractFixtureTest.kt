package art.anjing.tigertv

import art.anjing.tigertv.domain.SearchResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 复用 shared/api-contract/fixtures 的契约样例（AGENTS.md Cross-Platform Sync 约定）：
 * fixture 必须能被 Android 端模型直接解码，且 vod_pic / coverKey 语义与 CLI 一致。
 */
class ContractFixtureTest {

    private fun findRepoFixture(relative: String): File {
        var dir = File(System.getProperty("user.dir"))
        repeat(5) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("fixture not found: $relative (cwd=${System.getProperty("user.dir")})")
    }

    @Test
    fun `search sample fixture decodes into SearchResult with covers`() {
        val text = findRepoFixture("shared/api-contract/fixtures/search.sample.json").readText()
        val response = Json { ignoreUnknownKeys = true }.decodeFromString<SearchResponse>(text)

        assertEquals("逐玉", response.keyword)
        assertEquals(2, response.results.size)

        val first = response.results[0]
        assertEquals("🎬-爱奇艺-", first.site)
        assertEquals(73480, first.vodId)
        assertEquals("https://pic.example-iqiyi.com/cover/73480.jpg", first.vodPic)
        // 封面兜底缓存 key 统一为 "site-vodId"，禁止各处手拼漂移。
        assertTrue(first.coverKey.startsWith(first.site))
        assertTrue(first.coverKey.endsWith("-73480"))
    }
}
