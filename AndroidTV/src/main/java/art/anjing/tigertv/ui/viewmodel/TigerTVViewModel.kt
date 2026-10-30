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
import art.anjing.tigertv.data.TigerTVRepository
import art.anjing.tigertv.domain.PlaybackBookmark
import art.anjing.tigertv.domain.FetchResponse
import art.anjing.tigertv.domain.VideoWork
import art.anjing.tigertv.domain.SearchResult
import art.anjing.tigertv.util.normalizeCoverUrl
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TigerTVViewModel(
    private val repository: TigerTVRepository,
    context: Context
) : ViewModel() {

    private val appContext = context.applicationContext

    var keyword by mutableStateOf("")
        private set

    var submittedKeyword by mutableStateOf("")
        private set

    private val selectedWork get() = works.firstOrNull { work ->
        work.sources.any { it.coverKey == selectedResult?.coverKey }
    }

    var works by mutableStateOf<List<VideoWork>>(emptyList())
        private set
    val availableSources: List<SearchResult>
        get() = selectedWork?.sources.orEmpty()
    var selectedPlaybackSourceIndex by mutableIntStateOf(0)
        private set
    val episodes get() = fetchResponse?.playSources?.getOrNull(selectedPlaybackSourceIndex)?.episodes
        ?: emptyList()

    fun selectPlaybackSource(index: Int) {
        if (fetchResponse?.playSources?.getOrNull(index) == null || index == selectedPlaybackSourceIndex) return
        clearPlayback()
        selectedPlaybackSourceIndex = index
    }

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

    /** Changes on every successful resolution, including retries of the same URL. */
    var playbackRevision by mutableIntStateOf(0)
        private set

    val searchHistory = mutableStateListOf<String>()

    /** Source-specific fallback URLs; failures are not cached. */
    var coverFallbacks by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    private var sourceSaveJob: Job? = null
    fun playbackDidStart(url: String) {
        val source = selectedResult ?: return
        if (url != resolvedPlaybackUrl || selectedEpisodeIndex < 0) return
        val work = selectedWork ?: return
        val episode = episodes.getOrNull(selectedEpisodeIndex) ?: return
        val line = fetchResponse?.playSources?.getOrNull(selectedPlaybackSourceIndex) ?: return
        val bookmark = PlaybackBookmark(source.site, line.id, episode.name)
        if (work.playback == bookmark && work.recentPlayedSite == source.site) return
        works = works.map {
            it.copy(lastPlayedSite = if (it.id == work.id) source.site else it.lastPlayedSite, recentPlayedSite = source.site, playback = if (it.id == work.id) bookmark else it.playback)
        }
        val previous = sourceSaveJob
        sourceSaveJob = viewModelScope.launch {
            previous?.join()
            repository.recordPlayback(work.id, source.site, line.id, episode.name)
        }
    }
    private val coverJobs = mutableMapOf<String, Job>()
    private var searchGeneration = 0
    private var historyRevision = 0
    private var historySaveJob: Job? = null

    private var prefetchJob: Job? = null
    private val prefetchedKeys = mutableSetOf<String>()

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
                is Result.Error -> result.exception.message ?: "加载配置失败"
            }
        }
    }

    private fun loadSearchHistory() {
        val revision = historyRevision
        viewModelScope.launch {
            val saved = repository.loadHistory()
            if (revision == historyRevision) {
                searchHistory.clear()
                searchHistory.addAll(saved)
            }
        }
    }

    private fun saveSearchHistory() {
        historyRevision++
        val snapshot = searchHistory.toList()
        val previous = historySaveJob
        historySaveJob = viewModelScope.launch {
            previous?.join()
            repository.saveHistory(snapshot)
        }
    }

    fun retryLoadConfig() {
        loadConfig()
    }

    /** Resolve missing posters only while their owning search is active. */
    fun loadCoverFallbackIfPossible(result: SearchResult) {
        if (result.vodPic.isBlank() && result.coverKey !in coverFallbacks && result.coverKey !in coverJobs) {
            val generation = searchGeneration
            coverJobs[result.coverKey] = viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    repository.resolveCoverFallback(result.site, result.vodId)?.let { url ->
                        if (generation == searchGeneration) coverFallbacks = coverFallbacks + (result.coverKey to url)
                    }
                } finally {
                    if (generation == searchGeneration) coverJobs.remove(result.coverKey)
                }
            }
            coverJobs[result.coverKey]?.start()
        }
        ensureCoverPrefetch()
    }

    /** Warm a bounded window of work posters, one source per work, in batches of six. */
    private fun ensureCoverPrefetch() {
        if (works.isEmpty() || prefetchJob?.isActive == true) return
        val snapshot = works.take(PREFETCH_LIMIT).map { work ->
            work.orderedSources.firstOrNull { it.vodPic.isNotBlank() } ?: work.defaultSource
        }
        prefetchJob = viewModelScope.launch {
            while (coverJobs.isNotEmpty()) delay(PREFETCH_IDLE_POLL_MILLIS)
            snapshot.chunked(PREFETCH_BATCH).forEach { batch ->
                ensureActive()
                coroutineScope { batch.map { result -> async { prefetchCover(appContext, result) } }.awaitAll() }
            }
        }
    }

    private suspend fun prefetchCover(context: Context, result: SearchResult) {
        if (!prefetchedKeys.add(result.coverKey)) return
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
                    .size(320, 480)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Visible cards retry through their normal image-loading path.
        }
    }

    fun onKeywordChange(value: String) {
        keyword = value
    }

    fun search(): Boolean {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return false
        cancelSearch()
        fetchJob?.cancel()
        playbackJob?.cancel()
        submittedKeyword = trimmed
        addHistory(trimmed)
        isSearching = true
        isFetching = false
        isResolvingPlayback = false
        searchErrorMessage = null
        fetchErrorMessage = null
        playbackErrorMessage = null
        works = emptyList()
        selectedResult = null
        fetchResponse = null
        selectedEpisodeIndex = -1
        resolvedPlaybackUrl = null

        searchJob = viewModelScope.launch {
            when (val result = repository.search(trimmed) { update ->
                withContext(Dispatchers.Main) { ensureActive(); works = update.works }
            }) {
                is Result.Success -> {
                    ensureActive()
                    works = result.data.works
                    isSearching = false
                }
                is Result.Error -> {
                    isSearching = false
                    searchErrorMessage = result.exception.message ?: "搜索失败"
                }
            }
        }
        return true
    }

    private fun cancelCoverLoads() {
        searchGeneration++
        prefetchJob?.cancel()
        coverJobs.values.forEach { it.cancel() }
        coverJobs.clear()
    }

    private fun cancelSearch() {
        searchJob?.cancel()
        cancelCoverLoads()
        coverFallbacks = emptyMap()
        prefetchedKeys.clear()
    }

    fun clearResults() {
        cancelSearch()
        fetchJob?.cancel()
        clearPlayback()
        works = emptyList()
        submittedKeyword = ""
        selectedResult = null
        fetchResponse = null
        isSearching = false
        isFetching = false
        searchErrorMessage = null
        fetchErrorMessage = null
    }

    val playbackBookmark get() = selectedWork?.playback

    val resumeEpisodeIndex: Int get() {
        val bookmark = selectedWork?.playback
        return episodes.indexOfFirst { it.name == bookmark?.episode }.coerceAtLeast(0)
    }

    fun selectResult(result: SearchResult, forceRefresh: Boolean = false) {
        if (!forceRefresh && fetchResponse != null && selectedResult?.let { it.site == result.site && it.vodId == result.vodId } == true) return
        cancelCoverLoads()
        fetchJob?.cancel()
        playbackJob?.cancel()
        selectedResult = result
        selectedPlaybackSourceIndex = 0
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
                    ensureActive()
                    fetchResponse = fetched.data
                    val bookmark = works.firstOrNull { it.sources.any { source -> source.coverKey == result.coverKey } }?.playback
                    if (bookmark?.site == result.site) {
                        selectedPlaybackSourceIndex = fetched.data.playSources.indexOfFirst { it.id == bookmark.line }.coerceAtLeast(0)
                    }
                    isFetching = false
                }
                is Result.Error -> {
                    isFetching = false
                    fetchErrorMessage = fetched.exception.message ?: "加载详情失败"
                }
            }
        }
    }

    fun leaveDetail() {
        fetchJob?.cancel()
        isFetching = false
        clearPlayback()
    }

    fun retryFetch() {
        val current = selectedResult ?: return
        selectResult(current, forceRefresh = true)
    }

    fun selectEpisode(index: Int) {
        val episodes = episodes
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
                    ensureActive()
                    resolvedPlaybackUrl = result.data
                    playbackRevision++
                    isResolvingPlayback = false
                }
                is Result.Error -> {
                    playbackErrorMessage = result.exception.message ?: "解析播放地址失败"
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
        searchJob?.cancel()
        fetchJob?.cancel()
        playbackJob?.cancel()
        prefetchJob?.cancel()
        repository.close()
    }

    companion object {
        private const val MAX_HISTORY = 20

        private const val PREFETCH_BATCH = 6
        private const val PREFETCH_LIMIT = 24

        private const val PREFETCH_IDLE_POLL_MILLIS = 100L
    }
}

@Suppress("UNCHECKED_CAST")
class TigerTVViewModelFactory(
    private val context: Context
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TigerTVViewModel::class.java)) {
            val app = context.applicationContext
            val core = art.anjing.tigertv.core.CoreService(
                java.io.File(app.cacheDir, "shared-v1").absolutePath,
                historyDirectory = java.io.File(app.filesDir, "shared-v1").absolutePath
            )
            return TigerTVViewModel(TigerTVRepository(core), app) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
