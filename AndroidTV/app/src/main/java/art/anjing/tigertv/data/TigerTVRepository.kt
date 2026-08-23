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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.CancellationException

class TigerTVRepository(
    private val configDataSource: ConfigDataSource,
    private val apiClient: MacCMSApiClient,
    private val playbackUrlResolver: PlaybackUrlResolver,
    private val searchCacheTtlMillis: Long = DEFAULT_SEARCH_CACHE_TTL,
    coverUrlCacheDir: File? = null
) {
    private var cachedConfig: SourceConfig? = null
    private val searchCache = mutableMapOf<String, CacheEntry<SearchResponse>>()
    private val coverCache = mutableMapOf<String, String>()
    private val coverCacheMutex = Mutex()
    private val coverSemaphore = Semaphore(COVER_FALLBACK_CONCURRENCY)

    /** 兜底 URL 磁盘持久层（7 天 TTL）；null 表示仅进程内缓存。 */
    private val coverDiskCache = coverUrlCacheDir?.let { CoverFallbackDiskCache(it) }

    /**
     * 搜索封面懒加载兜底：多数站点 `ac=list` 响应不含 `vod_pic`（仅 detail 返回），
     * 空封面卡片可见时按需取 detail 补齐。命中内存缓存不发请求；失败不缓存，
     * 卡片再次进入视口时自然重试。并发受信号量限制，避免滚动时打爆站点。
     */
    suspend fun resolveCoverFallback(siteName: String, vodId: Int): String? {
        val key = "$siteName-$vodId"
        coverCacheMutex.withLock {
            coverCache[key]?.let { return it }
        }
        // 磁盘持久层命中：回填内存缓存即可，无需发 detail 请求（7 天 TTL 内稳定有效）。
        coverDiskCache?.get(key)?.let { persisted ->
            coverCacheMutex.withLock { coverCache[key] = persisted }
            return persisted
        }

        val config = cachedConfig ?: when (val loaded = loadConfig()) {
            is Result.Success -> loaded.data
            is Result.Error -> return null
        }
        val site = config.apiSite.values.find { it.name == siteName } ?: return null

        return try {
            coverSemaphore.withPermit {
                // 拿到许可后二次检查：排队期间同 key 可能已被其他任务填入缓存。
                val cached = coverCacheMutex.withLock { coverCache["$siteName-$vodId"] }
                cached ?: run {
                    val detail = apiClient.fetchDetail(site.api, vodId)
                    val pic = if (detail.code == 1) detail.list.firstOrNull()?.vodPic?.trim() else null
                    val url = pic?.takeIf { it.isNotEmpty() }
                    if (url != null) {
                        coverCacheMutex.withLock { coverCache["$siteName-$vodId"] = url }
                        coverDiskCache?.put("$siteName-$vodId", url)
                    }
                    url
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Cover fallback failed for $siteName/$vodId", e)
            null
        }
    }

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
        private const val COVER_FALLBACK_CONCURRENCY = 4
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

/**
 * 兜底封面 URL 的磁盘持久缓存（JSON + 原子替换，7 天 TTL）。
 * 进程重启后命中即可跳过 detail 请求；读取时惰性清除过期项。
 */
internal class CoverFallbackDiskCache(private val directory: File) {
    @Serializable
    data class Entry(val url: String, val fetchedAtMillis: Long)

    private val file = File(directory, "cover-fallback-cache.json")
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun get(key: String): String? = mutex.withLock {
        val entries = read()
        val entry = entries[key] ?: return@withLock null
        if (System.currentTimeMillis() - entry.fetchedAtMillis >= TTL_MILLIS) {
            write(entries - key)
            null
        } else {
            entry.url
        }
    }

    suspend fun put(key: String, url: String) {
        put(key, url, System.currentTimeMillis())
    }

    internal suspend fun put(key: String, url: String, fetchedAtMillis: Long) = mutex.withLock {
        write(read() + (key to Entry(url, fetchedAtMillis)))
    }

    private fun read(): Map<String, Entry> = try {
        json.decodeFromString<Map<String, Entry>>(file.readText())
    } catch (_: Exception) {
        emptyMap()
    }

    private fun write(entries: Map<String, Entry>) {
        try {
            directory.mkdirs()
            // tmp + rename 原子写，避免进程中断留下半截 JSON。
            val tmp = File(directory, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(entries))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (_: Exception) {
            // 缓存写失败不影响主流程。
        }
    }

    companion object {
        const val TTL_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
