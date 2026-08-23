package art.anjing.tigertv.ui.viewmodel

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import art.anjing.tigertv.data.Result
import art.anjing.tigertv.data.SearchHistoryStore
import art.anjing.tigertv.data.TigerTVRepository
import art.anjing.tigertv.domain.FetchResponse
import art.anjing.tigertv.domain.SearchResult
import art.anjing.tigertv.util.normalizeCoverUrl
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TigerTVViewModel(
    private val repository: TigerTVRepository,
    context: Context
) : ViewModel() {

    private val appContext = context.applicationContext
    private val historyStore = SearchHistoryStore(context)

    var keyword by mutableStateOf("")
        private set

    var submittedKeyword by mutableStateOf("")
        private set

    var results by mutableStateOf<List<SearchResult>>(emptyList())
        private set

    var selectedResult by mutableStateOf<SearchResult?>(null)
        private set

    var fetchResponse by mutableStateOf<FetchResponse?>(null)
        private set

    var selectedEpisodeIndex by mutableIntStateOf(-1)
        private set

    var isSearching by mutableStateOf(false)
        private set

    var isFetching by mutableStateOf(false)
        private set

    var isResolvingPlayback by mutableStateOf(false)
        private set

    var configErrorMessage by mutableStateOf<String?>(null)
        private set

    var searchErrorMessage by mutableStateOf<String?>(null)
        private set

    var fetchErrorMessage by mutableStateOf<String?>(null)
        private set

    var playbackErrorMessage by mutableStateOf<String?>(null)
        private set

    var resolvedPlaybackUrl by mutableStateOf<String?>(null)
        private set

    val searchHistory = mutableStateListOf<String>()

    /**
     * 空封面卡片的懒加载兜底 URL（key 为 "site-vodId"）。
     * 仅在卡片可见时按需填充；失败不入表，卡片重入视口时自然重试。
     */
    var coverFallbacks by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    private val coverLoadsInFlight = mutableSetOf<String>()
    private val coverLoadsMutex = Any()

    private var prefetchJob: Job? = null
    private val prefetchedKeys = mutableSetOf<String>()
    private val prefetchedKeysMutex = Any()

    private var searchJob: Job? = null
    private var fetchJob: Job? = null
    private var playbackJob: Job? = null

    init {
        loadConfig()
        loadSearchHistory()
    }

    private fun loadConfig() {
        viewModelScope.launch {
            configErrorMessage = when (val result = repository.loadConfig()) {
                is Result.Success -> null
                is Result.Error -> result.exception.message ?: "Config load failed"
            }
        }
    }

    private fun loadSearchHistory() {
        viewModelScope.launch {
            val saved = historyStore.load()
            searchHistory.clear()
            searchHistory.addAll(saved)
        }
    }

    private fun saveSearchHistory() {
        viewModelScope.launch {
            historyStore.save(searchHistory.toList())
        }
    }

    fun retryLoadConfig() {
        loadConfig()
    }

    /** 卡片可见时调用：`vod_pic` 为空才发起 detail 兜底请求。命名与 macOS 端保持一致。 */
    fun loadCoverFallbackIfPossible(result: SearchResult) {
        // 每张卡片进入组合都会经过这里，正好作为渐进式预取的触发点。
        ensureCoverPrefetch()
        if (result.vodPic.isNotBlank()) return
        val key = result.coverKey
        synchronized(coverLoadsMutex) {
            if (coverFallbacks.containsKey(key) || !coverLoadsInFlight.add(key)) return
        }
        viewModelScope.launch {
            try {
                val url = repository.resolveCoverFallback(result.site, result.vodId)
                if (url != null) {
                    coverFallbacks = coverFallbacks + (key to url)
                }
            } finally {
                synchronized(coverLoadsMutex) { coverLoadsInFlight.remove(key) }
            }
        }
    }

    /**
     * 封面渐进式预取：当前曝光卡片的兜底请求收敛后，按批次预取未曝光结果
     * （解析兜底 URL + 把图片字节预热进 Coil 内存/磁盘缓存），一批完成再取下一批，
     * 直到列表末尾。用户滚到时直接命中缓存，无需再等网络。
     * 已处理过的 key 跳过；新搜索会取消并清空预取状态。
     */
    private fun ensureCoverPrefetch() {
        if (results.isEmpty() || prefetchJob?.isActive == true) return
        val snapshot = results
        prefetchJob = viewModelScope.launch {
            // 等曝光中的兜底请求收敛，避免与可见卡片抢并发闸门。
            while (synchronized(coverLoadsMutex) { coverLoadsInFlight.isNotEmpty() }) {
                delay(PREFETCH_IDLE_POLL_MILLIS)
            }
            var index = 0
            while (index < snapshot.size && isActive) {
                val end = minOf(index + PREFETCH_BATCH, snapshot.size)
                coroutineScope {
                    snapshot.subList(index, end).map { result ->
                        async { prefetchCover(appContext, result) }
                    }.awaitAll()
                }
                index = end
            }
        }
    }

    private suspend fun prefetchCover(context: Context, result: SearchResult) {
        synchronized(prefetchedKeysMutex) {
            if (!prefetchedKeys.add(result.coverKey)) return
        }
        try {
            val url = if (result.vodPic.isNotBlank()) {
                result.vodPic
            } else {
                repository.resolveCoverFallback(result.site, result.vodId) ?: return
            }
            val normalized = normalizeCoverUrl(url) ?: return
            SingletonImageLoader.get(context).execute(
                ImageRequest.Builder(context)
                    .data(normalized)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build()
            )
        } catch (_: Exception) {
            // 预取失败静默放弃：卡片真正可见时 UI 自己的加载路径仍会重试。
        }
    }

    fun onKeywordChange(value: String) {
        keyword = value
    }

    fun search(): Boolean {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return false
        searchJob?.cancel()
        fetchJob?.cancel()
        playbackJob?.cancel()
        prefetchJob?.cancel()
        synchronized(prefetchedKeysMutex) { prefetchedKeys.clear() }
        submittedKeyword = trimmed
        addHistory(trimmed)
        isSearching = true
        isFetching = false
        isResolvingPlayback = false
        searchErrorMessage = null
        fetchErrorMessage = null
        playbackErrorMessage = null
        results = emptyList()
        selectedResult = null
        fetchResponse = null
        selectedEpisodeIndex = -1
        resolvedPlaybackUrl = null

        searchJob = viewModelScope.launch {
            when (val result = repository.search(trimmed)) {
                is Result.Success -> {
                    results = result.data.results
                    isSearching = false
                }
                is Result.Error -> {
                    isSearching = false
                    searchErrorMessage = result.exception.message ?: "Search failed"
                }
            }
        }
        return true
    }

    fun selectResult(result: SearchResult, forceRefresh: Boolean = false) {
        // forceRefresh=true 时跳过"同一结果"短路，专用于重试按钮。
        if (!forceRefresh && selectedResult?.let { it.site == result.site && it.vodId == result.vodId } == true) return
        fetchJob?.cancel()
        playbackJob?.cancel()
        selectedResult = result
        fetchResponse = null
        selectedEpisodeIndex = -1
        resolvedPlaybackUrl = null
        isFetching = true
        isResolvingPlayback = false
        fetchErrorMessage = null
        playbackErrorMessage = null

        fetchJob = viewModelScope.launch {
            when (val fetched = repository.fetch(result.site, result.vodId)) {
                is Result.Success -> {
                    fetchResponse = fetched.data
                    isFetching = false
                }
                is Result.Error -> {
                    isFetching = false
                    fetchErrorMessage = fetched.exception.message ?: "Fetch failed"
                }
            }
        }
    }

    /** 重试上次 fetch：绕过"同一结果"短路，确保下次真正发起请求。 */
    fun retryFetch() {
        val current = selectedResult ?: return
        selectResult(current, forceRefresh = true)
    }

    fun selectEpisode(index: Int) {
        val episodes = fetchResponse?.vodPlayUrl ?: return
        if (index < 0 || index >= episodes.size) return
        selectedEpisodeIndex = index
        playbackJob?.cancel()
        resolvedPlaybackUrl = null
        isResolvingPlayback = true
        playbackErrorMessage = null
        val episode = episodes[index]

        playbackJob = viewModelScope.launch {
            when (val result = repository.resolvePlaybackUrl(episode.url)) {
                is Result.Success -> {
                    resolvedPlaybackUrl = result.data
                    isResolvingPlayback = false
                }
                is Result.Error -> {
                    playbackErrorMessage = result.exception.message ?: "Playback resolution failed"
                    isResolvingPlayback = false
                }
            }
        }
    }

    fun retryPlayback() {
        if (selectedEpisodeIndex >= 0) {
            selectEpisode(selectedEpisodeIndex)
        }
    }

    fun clearPlayback() {
        playbackJob?.cancel()
        resolvedPlaybackUrl = null
        playbackErrorMessage = null
        selectedEpisodeIndex = -1
        isResolvingPlayback = false
    }

    fun clearSearchError() {
        searchErrorMessage = null
    }

    fun clearFetchError() {
        fetchErrorMessage = null
    }

    fun setPlaybackError(message: String) {
        playbackErrorMessage = message
    }

    private fun addHistory(item: String) {
        searchHistory.remove(item)
        searchHistory.add(0, item)
        while (searchHistory.size > MAX_HISTORY) {
            searchHistory.removeAt(searchHistory.size - 1)
        }
        saveSearchHistory()
    }

    fun removeHistory(item: String) {
        searchHistory.remove(item)
        saveSearchHistory()
    }

    fun clearHistory() {
        searchHistory.clear()
        saveSearchHistory()
    }

    override fun onCleared() {
        super.onCleared()
        searchJob?.cancel()
        fetchJob?.cancel()
        playbackJob?.cancel()
        prefetchJob?.cancel()
    }

    companion object {
        private const val MAX_HISTORY = 20

        /** 每批预取的结果数。 */
        private const val PREFETCH_BATCH = 6

        /** 等待曝光兜底请求收敛的轮询间隔。 */
        private const val PREFETCH_IDLE_POLL_MILLIS = 100L
    }
}

@Suppress("UNCHECKED_CAST")
class TigerTVViewModelFactory(
    private val repository: TigerTVRepository,
    private val context: Context
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TigerTVViewModel::class.java)) {
            return TigerTVViewModel(repository, context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
