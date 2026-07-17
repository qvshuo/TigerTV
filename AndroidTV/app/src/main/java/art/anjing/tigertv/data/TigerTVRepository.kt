package art.anjing.tigertv.data

import android.util.Log
import art.anjing.tigertv.domain.EpisodeLink
import art.anjing.tigertv.domain.FetchResponse
import art.anjing.tigertv.domain.SearchResponse
import art.anjing.tigertv.domain.SearchResult
import art.anjing.tigertv.domain.SourceConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.CancellationException

class TigerTVRepository(
    private val configDataSource: ConfigDataSource,
    private val apiClient: MacCMSApiClient,
    private val playbackUrlResolver: PlaybackUrlResolver,
    private val searchCacheTtlMillis: Long = DEFAULT_SEARCH_CACHE_TTL
) {
    private var cachedConfig: SourceConfig? = null
    private val searchCache = mutableMapOf<String, CacheEntry<SearchResponse>>()

    suspend fun loadConfig(): Result<SourceConfig> {
        return when (val result = configDataSource.loadConfig()) {
            is Result.Success -> {
                cachedConfig = result.data
                result
            }
            is Result.Error -> result
        }
    }

    suspend fun search(keyword: String): Result<SearchResponse> {
        val trimmed = keyword.trim()
        val cached = searchCache[trimmed]
        if (cached != null && cached.isFresh(searchCacheTtlMillis)) {
            Log.i(TAG, "Using cached search results for '$trimmed'")
            return Result.Success(cached.data)
        }

        val config = cachedConfig ?: when (val loaded = loadConfig()) {
            is Result.Success -> loaded.data
            is Result.Error -> return Result.Error(loaded.exception)
        }

        return try {
            val sites = config.apiSite.values.toList()
            val semaphore = Semaphore(MAX_CONCURRENT)
            val results = coroutineScope {
                sites.map { site ->
                    async {
                        semaphore.withPermit {
                            apiClient.search(site.name, site.api, trimmed)
                        }
                    }
                }.awaitAll().flatten()
            }
            val response = SearchResponse(keyword, results)
            searchCache[trimmed] = CacheEntry(response, System.currentTimeMillis())
            Result.Success(response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    suspend fun fetch(siteName: String, vodId: Int): Result<FetchResponse> {
        val config = cachedConfig ?: when (val loaded = loadConfig()) {
            is Result.Success -> loaded.data
            is Result.Error -> return Result.Error(loaded.exception)
        }

        val site = config.apiSite.values.find { it.name == siteName }
            ?: return Result.Error(IllegalArgumentException("Unknown site: $siteName. Available: ${config.apiSite.values.map { it.name }}"))

        return try {
            val detail = apiClient.fetchDetail(site.api, vodId)
            if (detail.code != 1) {
                return Result.Error(IllegalStateException("Fetch failed: ${detail.msg}"))
            }
            val item = detail.list.firstOrNull()
                ?: return Result.Error(IllegalStateException("Empty detail response"))
            val play = parseEpisodeLinks(item.vodPlayUrl)
            val down = parseEpisodeLinks(item.vodDownUrl)
            Result.Success(
                FetchResponse(
                    vodId = item.vodId,
                    site = siteName,
                    vodPlayUrl = play,
                    vodDownUrl = down
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    suspend fun resolvePlaybackUrl(url: String): Result<String> {
        return try {
            Result.Success(playbackUrlResolver.resolve(url))
        } catch (e: CancellationException) {
            // 结构化取消必须重抛，否则 viewModelScope.cancel 无法中断子 job。
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    private fun parseEpisodeLinks(raw: String): List<EpisodeLink> {
        if (raw.isBlank()) return emptyList()
        val links = mutableListOf<EpisodeLink>()
        val groups = raw.split("$$$")
        for (group in groups) {
            val items = group.split("#")
            for (item in items) {
                val parts = item.split("$", limit = 2)
                val name = parts.getOrNull(0)?.trim() ?: continue
                val url = parts.getOrNull(1)?.trim() ?: continue
                if (name.isNotEmpty() && url.isNotEmpty()) {
                    links.add(EpisodeLink(name, url))
                }
            }
        }
        return links
    }

    companion object {
        private const val MAX_CONCURRENT = 20
        private const val DEFAULT_SEARCH_CACHE_TTL = 10 * 60 * 1000L // 10 minutes
        private const val TAG = "TigerTVRepository"
    }

    private data class CacheEntry<T>(
        val data: T,
        val createdAtMillis: Long
    ) {
        fun isFresh(ttlMillis: Long): Boolean {
            return System.currentTimeMillis() - createdAtMillis < ttlMillis
        }
    }
}
