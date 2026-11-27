package art.anjing.tigertv.data

import android.util.Log
import art.anjing.tigertv.core.CoreService
import art.anjing.tigertv.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TigerTVRepository(private val core: CoreService) {
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playbackSaves = SaveQueue(persistenceScope)
    private val historySaves = SaveQueue(persistenceScope)

    private class SaveQueue(private val scope: CoroutineScope) {
        private var pending: Job? = null

        @Synchronized
        fun enqueue(write: suspend () -> Unit) {
            val previous = pending
            pending = scope.launch {
                previous?.join()
                write()
            }
        }

        suspend fun awaitPending() {
            val latest = synchronized(this) { pending }
            latest?.join()
        }
    }

    private suspend fun <T> result(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        try { Result.Success(block()) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { Log.w("TigerTV", "Core request failed", e); Result.Error(e) }
    }

    suspend fun loadConfig(): Result<SourceConfig> = result { core.loadConfig() }
    suspend fun search(keyword: String, onUpdate: suspend (SearchResponse) -> Unit = {}): Result<SearchResponse> = result {
        playbackSaves.awaitPending()
        core.search(keyword.trim(), onUpdate)
    }
    suspend fun fetch(siteName: String, vodId: Int): Result<FetchResponse> = result { core.fetch(siteName, vodId) }
    suspend fun resolveCoverFallback(siteName: String, vodId: Int): String? = withContext(Dispatchers.IO) { core.resolveCoverFallback(siteName, vodId) }
    suspend fun recentPlayback(): List<RecentPlayback> = withContext(Dispatchers.IO) {
        playbackSaves.awaitPending()
        core.recentPlayback()
    }
    fun rememberRecentPoster(workId: String, url: String) =
        playbackSaves.enqueue { core.rememberRecentPoster(workId, url) }
    fun recordPlayback(workId: String, site: String, line: String, episode: String,
        source: SearchResult? = null, cardSource: SearchResult? = null, cardPoster: String? = null, index: Int? = null,
    ) = playbackSaves.enqueue {
        core.recordPlayback(workId, site, line, episode, source, cardSource, cardPoster, index)
    }
    fun recordPlaybackPosition(workId: String, site: String, line: String, episode: String, positionMillis: Long, percent: Int? = null, sourceKey: String? = null, index: Int? = null) = playbackSaves.enqueue {
        core.recordPlaybackPosition(workId, site, line, episode, positionMillis, percent, sourceKey, index)
    }
    suspend fun warmHotSearches(category: String, onReady: suspend (String, List<HotSearchItem>) -> Unit) =
        withContext(Dispatchers.IO) { core.warmHotSearches(category, onReady) }
    fun hotSearchCategories(): List<HotSearchCategory> = core.hotSearchCategories()
    suspend fun hotSearches(category: String, onUpdate: (suspend (List<HotSearchItem>) -> Unit)? = null): List<HotSearchItem> =
        withContext(Dispatchers.IO) { core.hotSearches(category, onUpdate) }
    suspend fun loadHistory(): List<String> = withContext(Dispatchers.IO) {
        historySaves.awaitPending()
        core.history()
    }
    fun addHistory(item: String) = historySaves.enqueue { core.addHistory(item) }
    fun removeHistory(item: String) = historySaves.enqueue { core.removeHistory(item) }
    fun clearHistory() = historySaves.enqueue { core.clearHistory() }
    fun danmakuClient(baseUrl: String) = core.danmakuClient(baseUrl)
}
