package art.anjing.tigertv.ui.viewmodel

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import art.anjing.tigertv.core.CoreService
import art.anjing.tigertv.data.Result
import art.anjing.tigertv.data.TigerTVRepository
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

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
        viewModelScope.launch { repository.rememberRecentPoster(id, url) }
    }

    suspend fun refreshRecentPlayback() {
        playbackSaveJob?.join()
        recentPlayback = repository.recentPlayback()
        recentPlaybackLoaded = true
    }

    fun openRecentPlayback(recent: RecentPlayback) {
        val source = recent.source
        val resource = if (recent.bookmark.index != null) source.coverKey else source.site
        recentWork = VideoWork(recent.workId, source.vodName, listOf(source), source.coverKey,
            lastPlayedSite = source.site, playback = mapOf(resource to recent.bookmark),
            playbackPositions = mapOf(resource to recent.positions), lastPlayedSourceKey = source.coverKey)
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

    private val detailCache = mutableStateMapOf<String, Pair<TimeMark, FetchResponse>>()
    private val detailLoads = mutableMapOf<String, Deferred<Result<FetchResponse>>>()
    private val checkedDetails = mutableStateMapOf<String, Boolean>()

    fun cachedDetail(source: SearchResult): FetchResponse? = detailCache[source.coverKey]
        ?.takeIf { it.first.elapsedNow() < 5.minutes }?.second

    fun isDetailLoading(source: SearchResult): Boolean = cachedDetail(source) == null && checkedDetails[source.coverKey] != true

    private fun requestDetail(source: SearchResult): Deferred<Result<FetchResponse>> {
        val key = source.coverKey
        detailLoads[key]?.let { return it }
        checkedDetails[key] = false
        lateinit var pending: Deferred<Result<FetchResponse>>
        pending = viewModelScope.async(start = CoroutineStart.LAZY) {
            try {
                repository.fetch(source.site, source.vodId).also { result ->
                    ensureActive()
                    if (result is Result.Success) {
                        if (detailCache.size >= 64 && key !in detailCache) {
                            detailCache.maxByOrNull { it.value.first.elapsedNow() }?.let { detailCache.remove(it.key) }
                        }
                        detailCache[key] = TimeSource.Monotonic.markNow() to result.data
                    }
                }
            } finally {
                if (detailLoads[key] === pending) {
                    checkedDetails[key] = true
                    detailLoads.remove(key)
                }
            }
        }
        detailLoads[key] = pending
        pending.start()
        return pending
    }

    suspend fun preloadDetails(sources: List<SearchResult>) {
        val others = sources.filter { it.coverKey != selectedResult?.coverKey && cachedDetail(it) == null }
        others.chunked(3).forEach { batch -> batch.map(::requestDetail).awaitAll() }
    }

    private fun cancelDetailRequests() {
        fetchJob?.cancel()
        val pending = detailLoads.values.toList()
        detailLoads.clear()
        checkedDetails.clear()
        pending.forEach { it.cancel() }
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
    // Flushes must survive disposal of the screen and its ViewModel scope.
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var playbackSaveJob: Job? = null
    private var startedPlaybackRevision = -1
    private val changedPlaybackWorks = mutableSetOf<String>()
    private var latestPlayedSite: String? = null
    data class PlaybackSession(val workId: String, val site: String, val line: String, val episode: String, val positionMillis: Long, val revision: Int, val sourceKey: String = site, val index: Int = 0, val legacySite: Boolean = false, val legacyEpisode: Boolean = false)

    val playbackSession: PlaybackSession? get() {
        val work = selectedWork ?: return null
        val source = selectedResult ?: return null
        val line = fetchResponse?.playSources?.getOrNull(selectedPlaybackSourceIndex) ?: return null
        val episode = episodes.getOrNull(selectedEpisodeIndex) ?: return null
        return PlaybackSession(work.id, source.site, line.id, episode.name,
            work.positionFor(source, line, selectedEpisodeIndex), playbackRevision, source.coverKey, selectedEpisodeIndex,
            work.acceptsLegacy(source), line.episodes.count { it.name == episode.name } == 1)
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
        val previous = playbackSaveJob
        playbackSaveJob = persistenceScope.launch {
            previous?.join()
            repository.recordPlayback(session.workId, session.site, session.line, session.episode, recentSource, display, cardPoster, session.index)
        }
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
        val previous = playbackSaveJob
        playbackSaveJob = persistenceScope.launch {
            previous?.join()
            repository.recordPlaybackPosition(session.workId, session.site, session.line, session.episode, positionMillis, percent, session.sourceKey, session.index)
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
            playbackSaveJob?.join()
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

    fun selectRecommendedResult(result: SearchResult, filterAds: Boolean) {
        val work = works.firstOrNull { result in it.sources }
        val available = work?.sources.orEmpty().filter { cachedDetail(it)?.playSources?.isEmpty() != true }
        selectResult(work?.preferredSource(filterAds, available) ?: result)
    }

    fun selectResult(result: SearchResult, forceRefresh: Boolean = false) {
        if (!forceRefresh && fetchResponse != null && selectedResult?.coverKey == result.coverKey && cachedDetail(result) != null) return
        cancelCoverLoads()
        fetchJob?.cancel()
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
            val fetched = requestDetail(result).await()
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
        playbackErrorMessage = "无法播放"
        java.util.logging.Logger.getLogger("TigerTV").warning(message)
    }

    private fun addHistory(item: String) {
        searchHistory.remove(item)
        searchHistory.add(0, item)
        while (searchHistory.size > CoreService.MAX_SEARCH_HISTORY) {
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
        private const val PREFETCH_BATCH = 2
        private const val PREFETCH_LIMIT = 16

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
