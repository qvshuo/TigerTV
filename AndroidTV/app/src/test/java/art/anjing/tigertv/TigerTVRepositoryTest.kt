package art.anjing.tigertv

import android.content.Context
import art.anjing.tigertv.data.ConfigDataSource
import art.anjing.tigertv.data.MacCMSApiClient
import art.anjing.tigertv.data.PlaybackUrlResolver
import art.anjing.tigertv.data.Result
import art.anjing.tigertv.data.TigerTVRepository
import art.anjing.tigertv.domain.EpisodeLink
import art.anjing.tigertv.domain.FetchResponse
import art.anjing.tigertv.domain.SearchResponse
import art.anjing.tigertv.domain.SearchResult
import art.anjing.tigertv.domain.SourceConfig
import art.anjing.tigertv.domain.SourceSite
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TigerTVRepositoryTest {

    private val configSource = mockk<ConfigDataSource>()
    private val apiClient = mockk<MacCMSApiClient>()
    private val playbackResolver = mockk<PlaybackUrlResolver>()
    private val repository = TigerTVRepository(configSource, apiClient, playbackResolver)

    @Test
    fun `search returns merged results from multiple sites`() = runBlocking {
        coEvery { configSource.loadConfig() } returns Result.Success(
            SourceConfig(
                apiSite = mapOf(
                    "a" to SourceSite("🎬-爱奇艺-", "https://a.com"),
                    "b" to SourceSite("🎬金鹰点播", "https://b.com")
                )
            )
        )
        coEvery { apiClient.search("🎬-爱奇艺-", "https://a.com", "逐玉") } returns listOf(
            SearchResult("🎬-爱奇艺-", 1, "逐玉")
        )
        coEvery { apiClient.search("🎬金鹰点播", "https://b.com", "逐玉") } returns listOf(
            SearchResult("🎬金鹰点播", 2, "逐玉")
        )

        val result = repository.search("逐玉")
        assertTrue(result is Result.Success)
        val data = (result as Result.Success).data
        assertEquals("逐玉", data.keyword)
        assertEquals(2, data.results.size)
    }

    @Test
    fun `fetch parses episode groups`() = runBlocking {
        coEvery { configSource.loadConfig() } returns Result.Success(
            SourceConfig(
                apiSite = mapOf("a" to SourceSite("🎬-爱奇艺-", "https://a.com"))
            )
        )
        coEvery { apiClient.fetchDetail("https://a.com", 73480) } returns art.anjing.tigertv.data.MacCMSDetailResponse(
            code = 1,
            list = listOf(
                art.anjing.tigertv.data.MacCMSDetailItem(
                    vodId = 73480,
                    vodPlayUrl = "第01集\$https://a.com/1.html#第02集\$https://a.com/2.html\$\$\$备用第01集\$https://a.com/alt1.html"
                )
            )
        )

        val result = repository.fetch("🎬-爱奇艺-", 73480)
        assertTrue(result is Result.Success)
        val data = (result as Result.Success).data
        assertEquals(3, data.vodPlayUrl.size)
        assertEquals("第01集", data.vodPlayUrl[0].name)
    }

    @Test
    fun `unknown site returns error`() = runBlocking {
        coEvery { configSource.loadConfig() } returns Result.Success(
            SourceConfig(apiSite = mapOf("a" to SourceSite("🎬-爱奇艺-", "https://a.com")))
        )
        val result = repository.fetch("不存在", 1)
        assertTrue(result is Result.Error)
    }

    @Test
    fun `cover fallback fetches detail and caches`() = runBlocking {
        coEvery { configSource.loadConfig() } returns Result.Success(
            SourceConfig(apiSite = mapOf("a" to SourceSite("🎬-爱奇艺-", "https://a.com")))
        )
        val detail = art.anjing.tigertv.data.MacCMSDetailResponse(
            code = 1,
            list = listOf(art.anjing.tigertv.data.MacCMSDetailItem(vodId = 73480, vodPic = "https://pic.example.com/cover/73480.jpg"))
        )
        // 第二次必须命中缓存：结束时校验 detail 只被请求一次。
        coEvery { apiClient.fetchDetail("https://a.com", 73480) } returns detail

        assertEquals("https://pic.example.com/cover/73480.jpg", repository.resolveCoverFallback("🎬-爱奇艺-", 73480))
        assertEquals("https://pic.example.com/cover/73480.jpg", repository.resolveCoverFallback("🎬-爱奇艺-", 73480))
        coVerify(exactly = 1) { apiClient.fetchDetail("https://a.com", 73480) }
    }

    @Test
    fun `cover fallback returns null without caching on empty pic`() = runBlocking {
        coEvery { configSource.loadConfig() } returns Result.Success(
            SourceConfig(apiSite = mapOf("a" to SourceSite("🎬-爱奇艺-", "https://a.com")))
        )
        val detail = art.anjing.tigertv.data.MacCMSDetailResponse(
            code = 1,
            list = listOf(art.anjing.tigertv.data.MacCMSDetailItem(vodId = 1, vodPic = "  "))
        )
        // 失败不缓存：两次调用各发一次请求。
        coEvery { apiClient.fetchDetail("https://a.com", 1) } returns detail

        assertNull(repository.resolveCoverFallback("🎬-爱奇艺-", 1))
        assertNull(repository.resolveCoverFallback("🎬-爱奇艺-", 1))
        coVerify(exactly = 2) { apiClient.fetchDetail("https://a.com", 1) }
    }
}
