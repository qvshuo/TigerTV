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
import art.anjing.tigertv.domain.HotSearchCategory
import art.anjing.tigertv.domain.PlaybackBookmark
import art.anjing.tigertv.domain.FetchResponse
import art.anjing.tigertv.domain.VideoWork
import art.anjing.tigertv.domain.SearchResult
import art.anjing.tigertv.data.normalizeCoverUrl
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    var configErrorMessage by mutableStateOf<String?>(null)
        private set

    var searchErrorMessage by mutableStateOf<String?>(null)
        private set

    var fetchErrorMessage by mutableStateOf<String?>(null)
        private set

    var playbackErrorMessage by mutableStateOf<String?>(null)
        private set

    val playbackUrl: String? get() = episodes.getOrNull(selectedEpisodeIndex)?.url

    /** Changes on every playback selection, including retries of the same URL. */
    var playbackRevision by mutableIntStateOf(0)
        private set

    val searchHistory = mutableStateListOf<String>()
    var hotSearches by mutableStateOf<List<art.anjing.tigertv.domain.HotSearchItem>>(emptyList())
        private set
    var hotSearchCategories by mutableStateOf<List<HotSearchCategory>>(emptyList())
        private set
    var selectedHotCategory by mutableStateOf("")
        private set
    var isLoadingHotSearches by mutableStateOf(false)
        private set
    var hotSearchRevision by mutableIntStateOf(0)
        private set

    /** Source-specific fallback URLs; failures are not cached. */
    var coverFallbacks by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    // Flushes must survive disposal of the screen and its ViewModel scope.
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var playbackSaveJob: Job? = null
    private var startedPlaybackRevision = -1
    private val changedPlaybackWorks = mutableSetOf<String>()
    private var latestPlayedSite: String? = null
    data class PlaybackSession(val workId: String, val site: String, val line: String, val episode: String, val positionMillis: Long, val revision: Int)

    val playbackSession: PlaybackSession? get() {
        val work = selectedWork ?: return null
        val source = selectedResult ?: return null
        val line = fetchResponse?.playSources?.getOrNull(selectedPlaybackSourceIndex) ?: return null
        val episode = episodes.getOrNull(selectedEpisodeIndex) ?: return null
        return PlaybackSession(work.id, source.site, line.id, episode.name,
            work.playbackPositions[source.site]?.get(line.id)?.get(episode.name) ?: 0, playbackRevision)
    }

    fun playbackDidStart(session: PlaybackSession) {
        val active = playbackSession ?: return
        // Resume positions can change while the identity of the playing session stays fixed.
        if (session.copy(positionMillis = active.positionMillis) != active) return
        if (startedPlaybackRevision == session.revision) return
        startedPlaybackRevision = session.revision
        changedPlaybackWorks += session.workId
        latestPlayedSite = session.site
        works = works.map {
            it.copy(
                lastPlayedSite = if (it.id == session.workId) session.site else it.lastPlayedSite,
                recentPlayedSite = session.site,
                playback = if (it.id == session.workId) it.playback + (session.site to PlaybackBookmark(session.line, session.episode)) else it.playback
            )
        }
        val previous = playbackSaveJob
        playbackSaveJob = persistenceScope.launch {
            previous?.join()
            repository.recordPlayback(session.workId, session.site, session.line, session.episode)
        }
    }

    fun savePlaybackPosition(session: PlaybackSession, positionMillis: Long) {
        if (positionMillis < 0) return
        works = works.map { work ->
            if (work.id != session.workId) work else {
                changedPlaybackWorks += session.workId
                val sites = work.playbackPositions
                val lines = sites[session.site].orEmpty()
                val episodes = lines[session.line].orEmpty()
                work.copy(playbackPositions = sites + (session.site to (lines + (session.line to (episodes + (session.episode to positionMillis))))))
            }
        }
        val previous = playbackSaveJob
        playbackSaveJob = persistenceScope.launch {
            previous?.join()
            repository.recordPlaybackPosition(session.workId, session.site, session.line, session.episode, positionMillis)
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
    private var configJob: Job? = null
    private var hotSearchGeneration = 0
    private var hotWarmJob: Job? = null

    init {
        loadConfig()
        loadSearchHistory()
    }

    fun loadHotSearchCategories() {
        hotSearchCategories = repository.hotSearchCategories()
        if (selectedHotCategory.isEmpty()) selectedHotCategory = hotSearchCategories.firstOrNull()?.id.orEmpty()
    }

    fun cancelHotSearchWarmup() {
        hotWarmJob?.cancel()
        hotWarmJob = null
    }

    fun selectHotSearchCategory(category: String) {
        if (category == selectedHotCategory || hotSearchCategories.none { it.id == category }) return
        hotSearchGeneration++
        isLoadingHotSearches = true
        selectedHotCategory = category
        hotSearches = emptyList()
    }

    fun retryHotSearches() { hotSearchRevision++ }

    suspend fun loadHotSearches() {
        val category = selectedHotCategory
        if (category.isEmpty()) return
        val generation = ++hotSearchGeneration
        isLoadingHotSearches = true
        hotSearches = emptyList()
        try {
            val titles = repository.hotSearches(category)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (generation == hotSearchGeneration && selectedHotCategory == category) {
                hotSearches = titles
                if (titles.isNotEmpty() && hotWarmJob == null) {
                    hotWarmJob = viewModelScope.launch { repository.warmHotSearches(category) }
                }
            }
        } finally {
            if (generation == hotSearchGeneration) isLoadingHotSearches = false
        }
    }

    private fun loadConfig() {
        configJob?.cancel()
        configJob = viewModelScope.launch {
            val result = repository.loadConfig()
            ensureActive()
            configErrorMessage = when (result) {
                is Result.Success -> null
                is Result.Error -> "资源列表加载失败，请重试"
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

    private fun saveSearchHistory(update: suspend () -> Unit) {
        historyRevision++
        val previous = historySaveJob
        historySaveJob = persistenceScope.launch {
            previous?.join()
            update()
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
        submittedKeyword = trimmed
        addHistory(trimmed)
        isSearching = true
        isFetching = false
        searchErrorMessage = null
        fetchErrorMessage = null
        playbackErrorMessage = null
        works = emptyList()
        changedPlaybackWorks.clear()
        latestPlayedSite = null
        selectedResult = null
        fetchResponse = null
        selectedEpisodeIndex = -1

        searchJob = viewModelScope.launch {
            playbackSaveJob?.join()
            ensureActive()
            val requestContext = coroutineContext
            val result = repository.search(trimmed) { update ->
                withContext(Dispatchers.Main) { requestContext.ensureActive(); applySearchWorks(update.works) }
            }
            ensureActive()
            when (result) {
                is Result.Success -> {
                    applySearchWorks(result.data.works)
                    isSearching = false
                }
                is Result.Error -> {
                    isSearching = false
                    searchErrorMessage = "搜索失败，请重试"
                }
            }
        }
        return true
    }

    private fun applySearchWorks(incoming: List<VideoWork>) {
        if (changedPlaybackWorks.isEmpty()) { works = incoming; return }
        // A delayed search snapshot must not undo playback performed during that search.
        val local = works.filter { it.id in changedPlaybackWorks }.associateBy { it.id }
        works = incoming.map { work ->
            val played = local[work.id]
            work.copy(
                recentPlayedSite = latestPlayedSite ?: work.recentPlayedSite,
                lastPlayedSite = if (played != null) played.lastPlayedSite else work.lastPlayedSite,
                playback = played?.playback ?: work.playback,
                playbackPositions = played?.playbackPositions ?: work.playbackPositions
            )
        }
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
        changedPlaybackWorks.clear()
        latestPlayedSite = null
        submittedKeyword = ""
        selectedResult = null
        fetchResponse = null
        isSearching = false
        isFetching = false
        searchErrorMessage = null
        fetchErrorMessage = null
    }

    val playbackBookmark get() = selectedWork?.playback?.get(selectedResult?.site)

    val resumeEpisodeIndex: Int get() = fetchResponse?.playSources
        ?.getOrNull(selectedPlaybackSourceIndex)?.resumeEpisodeIndex(playbackBookmark) ?: 0

    fun selectResult(result: SearchResult, forceRefresh: Boolean = false) {
        if (!forceRefresh && fetchResponse != null && selectedResult?.let { it.site == result.site && it.vodId == result.vodId } == true) return
        cancelCoverLoads()
        fetchJob?.cancel()
        selectedResult = result
        selectedPlaybackSourceIndex = 0
        fetchResponse = null
        selectedEpisodeIndex = -1
        isFetching = true
        fetchErrorMessage = null
        playbackErrorMessage = null

        fetchJob = viewModelScope.launch {
            val fetched = repository.fetch(result.site, result.vodId)
            ensureActive()
            when (fetched) {
                is Result.Success -> {
                    fetchResponse = fetched.data
                    val bookmark = playbackBookmark
                    if (bookmark != null) {
                        selectedPlaybackSourceIndex = fetched.data.playSources.indexOfFirst { it.id == bookmark.line }.coerceAtLeast(0)
                    }
                    isFetching = false
                }
                is Result.Error -> {
                    isFetching = false
                    fetchErrorMessage = "详情加载失败，请重试"
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
        if (index !in episodes.indices) return
        selectedEpisodeIndex = index
        playbackErrorMessage = null
        playbackRevision++
    }

    fun retryPlayback() {
        if (selectedEpisodeIndex >= 0) {
            selectEpisode(selectedEpisodeIndex)
        }
    }

    fun clearPlayback() {
        playbackErrorMessage = null
        selectedEpisodeIndex = -1
    }

    fun clearSearchError() {
        searchErrorMessage = null
    }

    fun setPlaybackError(message: String) {
        playbackErrorMessage = "播放失败，请重试"
        java.util.logging.Logger.getLogger("TigerTV").warning(message)
    }

    private fun addHistory(item: String) {
        searchHistory.remove(item)
        searchHistory.add(0, item)
        while (searchHistory.size > MAX_HISTORY) {
            searchHistory.removeAt(searchHistory.size - 1)
        }
        saveSearchHistory { repository.addHistory(item) }
    }

    fun removeHistory(item: String) {
        searchHistory.remove(item)
        saveSearchHistory { repository.removeHistory(item) }
    }

    fun clearHistory() {
        searchHistory.clear()
        saveSearchHistory { repository.clearHistory() }
    }

    override fun onCleared() {
        searchJob?.cancel()
        fetchJob?.cancel()
        prefetchJob?.cancel()
        persistenceScope.launch {
            playbackSaveJob?.join()
            historySaveJob?.join()
            repository.close()
            persistenceScope.cancel()
        }
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
