package art.anjing.tigertv

import art.anjing.tigertv.domain.SearchResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Both clients decode the same current core fixtures. */
class ContractFixtureTest {

    private fun findRepoFixture(relative: String): File {
        var dir = File(requireNotNull(System.getProperty("user.dir")))
        repeat(5) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("fixture not found: $relative (cwd=${System.getProperty("user.dir")})")
    }

    @Test
    fun `grouped search and detail share the native core contract`() {
        val json = Json { ignoreUnknownKeys = true }
        val saved = json.decodeFromString<SearchResponse>(findRepoFixture("docs/fixtures/search-playback.sample.json").readText())
        assertEquals(art.anjing.tigertv.domain.PlaybackBookmark("0", "第02集"), saved.works.single().playback["🎬二"])
        assertEquals(754_000L, saved.works.single().playbackPositions["🎬二"]?.get("0")?.get("第02集"))
        assertEquals(45_000L, saved.works.single().playbackPositions["🎬二"]?.get("2")?.get("第02集"))
        val search = json.decodeFromString<SearchResponse>(findRepoFixture("docs/fixtures/search.sample.json").readText())
        assertEquals(listOf(1, 99), search.works.single().sources.map { it.vodId })
        assertEquals("🎬二", search.works.single().defaultSource.site)
        val detail = json.decodeFromString<art.anjing.tigertv.domain.FetchResponse>(findRepoFixture("docs/fixtures/fetch.sample.json").readText())
        val raw = json.decodeFromString<art.anjing.tigertv.domain.FetchResponse>(findRepoFixture("docs/fixtures/fetch-playback.sample.json").readText())
        assertEquals(listOf("HLS", "MP4", "HTML", ""), raw.playSources.single().episodes.map { it.name })
        assertEquals("https://media.example/中文/index.m3u8?sign=a%2Fb%2Bc%3D&literal=a+b", raw.playSources.single().episodes.first().url)
        assertEquals("https://page.example/player?id=42&sign=a%2Fb%2Bc%3D", raw.playSources.single().episodes[2].url)
        assertEquals("作品简介", detail.vodContent)
        assertEquals(listOf("0", "2"), detail.playSources.map { it.id })
        assertEquals(listOf(2, 1), detail.playSources.map { it.episodes.size })
    }
}
