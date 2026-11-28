package art.anjing.tigertv.ui.viewmodel

import art.anjing.tigertv.core.preferredSource

import android.content.Context
import android.util.Log
import art.anjing.tigertv.TigerTVApplication
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import art.anjing.tigertv.core.productDefaults
import art.anjing.tigertv.data.Result
import art.anjing.tigertv.data.TigerTVRepository
import art.anjing.tigertv.domain.workId
import art.anjing.tigertv.domain.HotSearchCategory
import art.anjing.tigertv.domain.PlaybackBookmark
import art.anjing.tigertv.domain.episodePositionKey
import art.anjing.tigertv.domain.RecentPlayback
import art.anjing.tigertv.domain.FetchResponse
import art.anjing.tigertv.domain.VideoWork
import art.anjing.tigertv.domain.SearchResult
import art.anjing.tigertv.data.normalizeCoverUrl
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class TigerTVViewModel(
    private val repository: TigerTVRepository,
    context: Context
) : ViewModel() {

    fun danmakuClient(baseUrl: String) = repository.danmakuClient(baseUrl)

    private val appContext = context.applicationContext

    var keyword by mutableStateOf("")
        private set

    var submittedKeyword by mutableStateOf("")
        private set

    private val selectedWork get() = recentWork ?: works.firstOrNull { work ->
        work.sources.any { it.coverKey == selectedResult?.coverKey }
    }
    private var recentWork by mutableStateOf<VideoWork?>(null)
    private var pendingRecentBookmark: PlaybackBookmark? = null
    var recentPlayback by mutableStateOf<List<RecentPlayback>>(emptyList())
        private set
    var recentPlaybackLoaded by mutableStateOf(false)
        private set

    private val workCovers = mutableStateMapOf<String, String>()

    fun cardSource(work: VideoWork): SearchResult =
        recentPlayback.firstOrNull { it.workId == work.id }?.cardSource ?: work.displaySource

    fun workCover(id: String): String? = workCovers[id] ?: recentPlayback.firstOrNull { it.workId == id }?.cardPoster

    fun rememberWorkCover(id: String, url: String) {
        if (workCovers[id] != url) workCovers[id] = url
    }

    fun rememberRecentCover(id: String, url: String) {
        rememberWorkCover(id, url)
        val recent = recentPlayback.firstOrNull { it.workId == id } ?: return
        if (recent.cardPoster == url) return
        recentPlayback = recentPlayback.map { if (it.workId == id) it.copy(cardPoster = url) else it }
        repository.rememberRecentPoster(id, url)
    }

    suspend fun refreshRecentPlayback() {
        recentPlayback = repository.recentPlayback()
        recentPlaybackLoaded = true
    }

    fun openRecentPlayback(recent: RecentPlayback) {
        val source = recent.source
        val resource = source.coverKey
        val work = works.firstOrNull { it.id == recent.workId }
        val sources = (listOf(source, recent.cardSource) + work?.sources.orEmpty())
            .filter { it.coverKey == source.coverKey || workId(it) == recent.workId }
            .distinctBy { it.coverKey }
        recentWork = VideoWork(recent.workId, source.vodName, sources, source.coverKey,
            lastPlayedSite = source.site, playback = work?.playback.orEmpty() + (resource to recent.bookmark),
            playbackPositions = work?.playbackPositions.orEmpty() + (resource to recent.positions),
            lastPlayedSourceKey = source.coverKey)
        pendingRecentBookmark = recent.bookmark
        selectResult(source, forceRefresh = true)
    }

    /** If the original line or episode disappeared, leave the user in details to choose. */
    fun takeExactRecentEpisode(): Int? {
        val bookmark = pendingRecentBookmark ?: return null
        pendingRecentBookmark = null
        val line = fetchResponse?.playSources?.getOrNull(selectedPlaybackSourceIndex) ?: return null
        if (line.id != bookmark.line) return null
        return line.bookmarkedIndex(bookmark)
    }

    var works by mutableStateOf<List<VideoWork>>(emptyList())
        private set
    val availableSources: List<SearchResult>
        get() = selectedWork?.sources.orEmpty()
    var configuredSites by mutableStateOf<List<String>>(emptyList())
        private set
    private var settledSearchSites by mutableStateOf<Set<String>>(emptySet())
    fun isSiteSearching(site: String): Boolean = recentWork == null && isSearching && site !in settledSearchSites
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

    private val detailRequests = DetailRequests(viewModelScope) { repository.fetch(it.site, it.vodId) }

    fun cachedDetail(source: SearchResult): FetchResponse? = detailRequests.cachedDetail(source)
    fun isDetailLoading(source: SearchResult): Boolean = detailRequests.isDetailLoading(source)

    suspend fun preloadDetails(sources: List<SearchResult>) {
        val others = sources.filter { it.coverKey != selectedResult?.coverKey && cachedDetail(it) == null }
        others.chunked(3).forEach { batch -> batch.map(detailRequests::requestDetail).awaitAll() }
    }

    private fun cancelDetailRequests() {
        fetchJob?.cancel()
        detailRequests.cancelPending()
    }

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

    var coverFallbacks by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    private var startedPlaybackRevision = -1
    private val changedPlaybackWorks = mutableSetOf<String>()
    private var latestPlayedSite: String? = null
    data class PlaybackSession(val workId: String, val site: String, val line: String, val episode: String, val positionMillis: Long, val revision: Int, val sourceKey: String = site, val index: Int = 0)

    val playbackSession: PlaybackSession? get() {
        val work = selectedWork ?: return null
        val source = selectedResult ?: return null
        val line = fetchResponse?.playSources?.getOrNull(selectedPlaybackSourceIndex) ?: return null
        val episode = episodes.getOrNull(selectedEpisodeIndex) ?: return null
        return PlaybackSession(work.id, source.site, line.id, episode.name,
            work.positionFor(source, line, selectedEpisodeIndex), playbackRevision, source.coverKey, selectedEpisodeIndex)
    }

    fun playbackDidStart(session: PlaybackSession) {
        val active = playbackSession ?: return
        if (session.copy(positionMillis = active.positionMillis) != active) return
        val source = selectedResult ?: return
        val detail = fetchResponse
        val display = selectedWork?.let(::cardSource) ?: source
        val cardPoster = workCover(session.workId)
        val recentSource = source.copy(
            vodName = source.vodName.ifBlank { detail?.vodName.orEmpty() },
            vodPic = detail?.vodPic?.takeIf { it.isNotBlank() } ?: source.vodPic,
        )
        if (startedPlaybackRevision == session.revision) return
        startedPlaybackRevision = session.revision
        changedPlaybackWorks += session.workId
        latestPlayedSite = session.site
        fun updateBookmark(work: VideoWork) = work.copy(
            lastPlayedSite = if (work.id == session.workId) session.site else work.lastPlayedSite,
            recentPlayedSite = session.site,
            lastPlayedSourceKey = if (work.id == session.workId) session.sourceKey else work.lastPlayedSourceKey,
            playback = if (work.id == session.workId) work.playback +
                (session.sourceKey to PlaybackBookmark(session.line, session.episode, session.index)) else work.playback,
        )
        recentWork = recentWork?.let(::updateBookmark)
        works = works.map(::updateBookmark)
        repository.recordPlayback(session.workId, session.site, session.line, session.episode, recentSource, display, cardPoster, session.index)
    }

    fun savePlaybackPosition(session: PlaybackSession, positionMillis: Long, percent: Int? = null) {
        if (positionMillis < 0) return
        fun updatePosition(work: VideoWork): VideoWork {
            if (work.id != session.workId) return work
            changedPlaybackWorks += session.workId
            val sites = work.playbackPositions
            val lines = sites[session.sourceKey].orEmpty()
            val episodes = lines[session.line].orEmpty()
            return work.copy(playbackPositions = sites +
                (session.sourceKey to (lines + (session.line to (episodes + (episodePositionKey(session.episode, session.index) to positionMillis))))))
        }
        recentWork = recentWork?.let(::updatePosition)
        works = works.map(::updatePosition)
        repository.recordPlaybackPosition(session.workId, session.site, session.line, session.episode, positionMillis, percent, session.sourceKey, session.index)
    }
    private val coverJobs = mutableMapOf<String, Deferred<String?>>()
    private val coverFallbackPermits = Semaphore(3)
    private val attemptedCoverKeys = mutableSetOf<String>()
    private var searchGeneration = 0
    private var historyRevision = 0

    private var prefetchJob: Job? = null
    private val prefetchedKeys = mutableSetOf<String>()

    private var searchJob: Job? = null
    private var fetchJob: Job? = null
    private var configJob: Job? = null
    private var hotSearchGeneration = 0
    private var hotWarmJob: Job? = null
    private val warmedHotSearches = mutableMapOf<String, List<art.anjing.tigertv.domain.HotSearchItem>>()

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
        warmedHotSearches.clear()
    }

    fun selectHotSearchCategory(category: String) {
        if (category == selectedHotCategory || hotSearchCategories.none { it.id == category }) return
        hotWarmJob?.cancel()
        hotWarmJob = null
        hotSearchGeneration++
        hotSearches = warmedHotSearches[category].orEmpty()
        isLoadingHotSearches = hotSearches.isEmpty()
        selectedHotCategory = category
    }

    fun retryHotSearches() { hotSearchRevision++ }

    suspend fun loadHotSearches() {
        val category = selectedHotCategory
        if (category.isEmpty()) return
        val generation = ++hotSearchGeneration
        isLoadingHotSearches = hotSearches.isEmpty()
        try {
            val titles = repository.hotSearches(category) { batch ->
                withContext(Dispatchers.Main.immediate) {
                    if (generation == hotSearchGeneration && selectedHotCategory == category) {
                        hotSearches = batch
                        isLoadingHotSearches = false
                    }
                }
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (generation == hotSearchGeneration && selectedHotCategory == category) {
                if (titles.isNotEmpty()) {
                    hotSearches = titles
                    warmedHotSearches[category] = titles
                }
                if (titles.isNotEmpty() && hotWarmJob == null) {
                    hotWarmJob = viewModelScope.launch {
                        repository.warmHotSearches(category) { warmedCategory, items ->
                            withContext(Dispatchers.Main.immediate) { warmedHotSearches[warmedCategory] = items }
                        }
                    }
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
            if (result is Result.Success) configuredSites = result.data.apiSite.values.map { it.name }.distinct()
            configErrorMessage = when (result) {
                is Result.Success -> null
                is Result.Error -> "资源暂不可用"
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

    fun retryLoadConfig() {
        loadConfig()
    }

    fun loadCoverFallbackIfPossible(work: VideoWork) {
        coverRequest(work).start()
        ensureCoverPrefetch()
    }

    private fun coverRequest(work: VideoWork): Deferred<String?> = coverJobs.getOrPut(work.id) {
        val generation = searchGeneration
        viewModelScope.async(start = CoroutineStart.LAZY) {
            try {
                var sources = work.orderedSources
                do {
                    sources = works.firstOrNull { it.id == work.id }?.orderedSources ?: sources
                    for (source in sources) {
                        val key = source.coverKey
                        normalizeCoverUrl(source.vodPic)?.takeIf { it.toHttpUrlOrNull() != null }?.let { return@async it }
                        coverFallbacks[key]?.let { return@async it }
                        if (!attemptedCoverKeys.add(key)) continue
                        val url = coverFallbackPermits.withPermit {
                            repository.resolveCoverFallback(source.site, source.vodId)
                        }?.let(::normalizeCoverUrl)?.takeIf { it.toHttpUrlOrNull() != null }
                        ensureActive()
                        if (url != null) {
                            coverFallbacks = coverFallbacks + (key to url)
                            return@async url
                        }
                    }
                } while (works.firstOrNull { it.id == work.id }?.orderedSources.orEmpty().any { it !in sources })
                null
            } finally {
                if (generation == searchGeneration) coverJobs.remove(work.id)
            }
        }
    }

    private fun ensureCoverPrefetch() {
        if (works.isEmpty() || prefetchJob?.isActive == true) return
        val snapshot = works.take(PREFETCH_LIMIT)
        prefetchJob = viewModelScope.launch {
            snapshot.chunked(PREFETCH_BATCH).forEach { batch ->
                ensureActive()
                coroutineScope { batch.map { work -> async { prefetchCover(appContext, work) } }.awaitAll() }
            }
        }
    }

    private suspend fun prefetchCover(context: Context, work: VideoWork) {
        if (!prefetchedKeys.add(work.id)) return
        try {
            val normalized = coverRequest(work).await() ?: return
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
        }
    }

    fun onKeywordChange(value: String) {
        keyword = value
    }

    fun search(): Boolean {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return false
        cancelSearch()
        cancelDetailRequests()
        submittedKeyword = trimmed
        addHistory(trimmed)
        isSearching = true
        isFetching = false
        searchErrorMessage = null
        fetchErrorMessage = null
        playbackErrorMessage = null
        works = emptyList()
        settledSearchSites = emptySet()
        changedPlaybackWorks.clear()
        latestPlayedSite = null
        selectedResult = null
        fetchResponse = null
        selectedEpisodeIndex = -1

        searchJob = viewModelScope.launch {
            ensureActive()
            val requestContext = coroutineContext
            val result = repository.search(trimmed) { update ->
                withContext(Dispatchers.Main) {
                    requestContext.ensureActive()
                    settledSearchSites = update.settledSites
                    applySearchWorks(update.works)
                }
            }
            ensureActive()
            when (result) {
                is Result.Success -> {
                    applySearchWorks(result.data.works)
                    isSearching = false
                }
                is Result.Error -> {
                    isSearching = false
                    searchErrorMessage = "搜索失败"
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
                lastPlayedSourceKey = played?.lastPlayedSourceKey ?: work.lastPlayedSourceKey,
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
        attemptedCoverKeys.clear()
    }

    private fun cancelSearch() {
        searchJob?.cancel()
        cancelCoverLoads()
        coverFallbacks = emptyMap()
        prefetchedKeys.clear()
    }

    fun clearResults() {
        cancelSearch()
        cancelDetailRequests()
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

    val playbackBookmark get() = selectedResult?.let { selectedWork?.bookmarkFor(it) }

    val resumeEpisodeIndex: Int get() = fetchResponse?.playSources
        ?.getOrNull(selectedPlaybackSourceIndex)?.resumeEpisodeIndex(playbackBookmark) ?: 0

    val resumePositionMillis: Long get() {
        val source = selectedResult ?: return 0
        val line = fetchResponse?.playSources?.getOrNull(selectedPlaybackSourceIndex) ?: return 0
        return selectedWork?.positionFor(source, line, resumeEpisodeIndex) ?: 0
    }

    fun selectPreferredResult(result: SearchResult) {
        val work = works.firstOrNull { result in it.sources }
        val available = work?.sources.orEmpty().filter { cachedDetail(it)?.playSources?.isEmpty() != true }
        selectResult(work?.preferredSource(available) ?: result)
    }

    fun selectResult(result: SearchResult, forceRefresh: Boolean = false) {
        if (!forceRefresh && fetchResponse != null && selectedResult?.coverKey == result.coverKey && cachedDetail(result) != null) return
        cancelCoverLoads()
        fetchJob?.cancel()
        if (recentWork?.lastPlayedSourceKey != result.coverKey) pendingRecentBookmark = null
        selectedResult = result
        selectedPlaybackSourceIndex = 0
        fetchResponse = null
        selectedEpisodeIndex = -1
        fetchErrorMessage = null
        playbackErrorMessage = null
        val cached = if (forceRefresh) null else cachedDetail(result)
        if (cached != null) {
            applyDetail(cached)
            return
        }
        isFetching = true

        fetchJob = viewModelScope.launch {
            val fetched = detailRequests.requestDetail(result).await()
            ensureActive()
            when (fetched) {
                is Result.Success -> applyDetail(fetched.data)
                is Result.Error -> {
                    isFetching = false
                    fetchErrorMessage = "资源加载失败"
                }
            }
        }
    }

    private fun applyDetail(detail: FetchResponse) {
        fetchResponse = detail
        val lines = detail.playSources
        val remembered = lines.indexOfFirst { it.id == playbackBookmark?.line }
        val direct = lines.indexOfFirst { line -> line.episodes.all { episode ->
            val path = episode.url.substringBefore('?').substringBefore('#')
            path.endsWith(".m3u8", ignoreCase = true) || path.endsWith(".mp4", ignoreCase = true)
        } }
        selectedPlaybackSourceIndex = if (remembered >= 0) remembered else direct.coerceAtLeast(0)
        isFetching = false
    }

    fun leaveDetail() {
        if (recentWork != null) {
            recentWork = null
            pendingRecentBookmark = null
            selectedResult = null
            fetchResponse = null
        }
        cancelDetailRequests()
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

    fun clearPlayback() {
        playbackErrorMessage = null
        selectedEpisodeIndex = -1
    }

    fun clearSearchError() {
        searchErrorMessage = null
    }

    fun setPlaybackError(message: String) {
        playbackErrorMessage = "无法播放"
        Log.w("TigerTV", message)
    }

    private fun addHistory(item: String) {
        searchHistory.remove(item)
        searchHistory.add(0, item)
        while (searchHistory.size > productDefaults.searchHistoryLimit) {
            searchHistory.removeAt(searchHistory.size - 1)
        }
        historyRevision++
        repository.addHistory(item)
    }

    fun removeHistory(item: String) {
        searchHistory.remove(item)
        historyRevision++
        repository.removeHistory(item)
    }

    fun clearHistory() {
        searchHistory.clear()
        historyRevision++
        repository.clearHistory()
    }

    override fun onCleared() {
        searchJob?.cancel()
        fetchJob?.cancel()
        prefetchJob?.cancel()
    }

    companion object {
        private const val PREFETCH_BATCH = 2
        private const val PREFETCH_LIMIT = 16

    }
}

@Suppress("UNCHECKED_CAST")
class TigerTVViewModelFactory(
    private val application: TigerTVApplication
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TigerTVViewModel::class.java)) {
            return TigerTVViewModel(application.repository, application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
