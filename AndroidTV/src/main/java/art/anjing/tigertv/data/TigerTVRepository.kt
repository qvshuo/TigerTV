package art.anjing.tigertv.data

import art.anjing.tigertv.core.CoreService
import art.anjing.tigertv.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Android UI adapter. All runtime business rules are implemented in KMP core. */
class TigerTVRepository(private val core: CoreService) {
    private suspend fun <T> result(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        try { Result.Success(block()) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { java.util.logging.Logger.getLogger("TigerTV").log(java.util.logging.Level.WARNING, "Core request failed", e); Result.Error(e) }
    }

    suspend fun loadConfig(): Result<SourceConfig> = result { core.loadConfig() }
    suspend fun search(keyword: String, onUpdate: suspend (SearchResponse) -> Unit = {}): Result<SearchResponse> = result { core.search(keyword.trim(), onUpdate) }
    suspend fun fetch(siteName: String, vodId: Int): Result<FetchResponse> = result { core.fetch(siteName, vodId) }
    suspend fun resolvePlaybackUrl(url: String): Result<String> = result { core.resolvePlaybackUrl(url) }
    suspend fun resolveCoverFallback(siteName: String, vodId: Int): String? = withContext(Dispatchers.IO) { core.resolveCoverFallback(siteName, vodId) }
    suspend fun recordPlayback(workId: String, site: String, line: String, episode: String) = withContext(Dispatchers.IO) {
        core.recordPlayback(workId, site, line, episode)
    }
    suspend fun warmHotSearches(category: String) = withContext(Dispatchers.IO) { core.warmHotSearches(category) }
    fun hotSearchCategories(): List<HotSearchCategory> = core.hotSearchCategories()
    suspend fun hotSearches(category: String): List<String> = withContext(Dispatchers.IO) { core.hotSearches(category) }
    suspend fun loadHistory(): List<String> = withContext(Dispatchers.IO) { core.history() }
    suspend fun saveHistory(items: List<String>) = withContext(Dispatchers.IO) { core.saveHistory(items) }
    fun close() = core.close()
}
