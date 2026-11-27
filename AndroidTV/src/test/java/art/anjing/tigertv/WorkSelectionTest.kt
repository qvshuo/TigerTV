package art.anjing.tigertv

import android.content.Context
import android.util.Log
import art.anjing.tigertv.data.Result
import art.anjing.tigertv.data.TigerTVRepository
import art.anjing.tigertv.domain.*
import art.anjing.tigertv.ui.viewmodel.TigerTVViewModel
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class WorkSelectionTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: TigerTVRepository
    private lateinit var viewModel: TigerTVViewModel
    private val a = SearchResult("A", 1, "逐玉", vodYear = "2026", typeName = "国产剧")
    private val b = a.copy(site = "B", vodId = 99)
    private val first = EpisodeLink("第一集", "https://a.test/1.m3u8")
    private val second = EpisodeLink("第二集", "https://a.test/2.m3u8")
    private val backup = EpisodeLink("正片", "https://backup.test/1.m3u8")

    private fun detail(result: SearchResult) = FetchResponse(
        result.vodId, result.site,
        downloadSources = emptyList(),
        playSources = listOf(PlaybackSource("0", "主线", listOf(first, second)), PlaybackSource("2", "备用", listOf(backup)))
    )

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        repository = mockk(relaxed = true)
        coEvery { repository.loadConfig() } returns Result.Success(SourceConfig())
        coEvery { repository.loadHistory() } returns emptyList()
        coEvery { repository.hotSearches(any(), any()) } returns emptyList()
        coEvery { repository.search(any(), any()) } returns Result.Success(SearchResponse("逐玉", aggregateWorks(listOf(a, b))))
        coEvery { repository.fetch("A", 1) } returns Result.Success(detail(a))
        coEvery { repository.fetch("B", 99) } returns Result.Success(detail(b))
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        viewModel = TigerTVViewModel(repository, context)
    }

    @After fun tearDown() { unmockkStatic(Log::class); Dispatchers.resetMain() }

    private fun hotItem(title: String) = art.anjing.tigertv.domain.HotSearchItem(title, title, "2026", "", null, 0, "")

    @Test fun detailPrefersDirectMediaUnlessThereIsAnExactRecentLine() = runTest(dispatcher) {
        val page = first.copy(url = "https://a.test/watch/1")
        coEvery { repository.fetch("A", 1) } returns Result.Success(detail(a).copy(
            playSources = listOf(PlaybackSource("0", "页面", listOf(page)), PlaybackSource("2", "直连", listOf(backup)))
        ))
        viewModel.selectResult(a)
        advanceUntilIdle()
        assertEquals(listOf(backup), viewModel.episodes)
        viewModel.openRecentPlayback(RecentPlayback("played", a, PlaybackBookmark("0", page.name, 0), emptyMap(), 0, 1))
        advanceUntilIdle()
        assertEquals(listOf(page), viewModel.episodes)
        assertEquals(0, viewModel.takeExactRecentEpisode())
    }

    @Test fun recentCardResumesOnlyItsExactResourceLineAndEpisode() = runTest(dispatcher) {
        val recent = RecentPlayback("played", b, PlaybackBookmark("0", second.name, 1), mapOf("0" to mapOf(episodePositionKey(second.name, 1) to 84_000L)), 12, 1)
        viewModel.openRecentPlayback(recent)
        advanceUntilIdle()
        assertEquals(b, viewModel.selectedResult)
        assertEquals(emptyList<VideoWork>(), viewModel.works)
        assertEquals(1, viewModel.takeExactRecentEpisode())
        viewModel.selectEpisode(1)
        assertEquals(84_000L, viewModel.playbackSession?.positionMillis)
        assertNull(viewModel.takeExactRecentEpisode())

        viewModel.leaveDetail()
        viewModel.openRecentPlayback(recent.copy(bookmark = PlaybackBookmark("removed", second.name, 1)))
        advanceUntilIdle()
        assertNull(viewModel.takeExactRecentEpisode())
        assertNull(viewModel.playbackUrl)
        assertEquals(listOf(first, second), viewModel.episodes)

        viewModel.leaveDetail()
        val duplicates = detail(b).copy(playSources = listOf(PlaybackSource("0", "主线", listOf(second, second))))
        coEvery { repository.fetch("B", 99) } returns Result.Success(duplicates)
        viewModel.openRecentPlayback(recent)
        advanceUntilIdle()
        assertEquals(1, viewModel.takeExactRecentEpisode())
        assertNull(viewModel.playbackUrl)
    }

    @Test fun recentPlaybackKeepsMatchingSourcesWhenOriginalFails() = runTest(dispatcher) {
        viewModel.onKeywordChange("逐玉")
        viewModel.search()
        advanceUntilIdle()
        val work = viewModel.works.single()
        coEvery { repository.fetch("B", 99) } returns Result.Error(IllegalStateException("unavailable"))
        viewModel.openRecentPlayback(RecentPlayback(work.id, b, PlaybackBookmark("0", second.name, 1), emptyMap(), 0, 1))
        advanceUntilIdle()
        assertEquals(b, viewModel.selectedResult)
        assertEquals(setOf(a, b), viewModel.availableSources.toSet())
        viewModel.selectResult(a)
        advanceUntilIdle()
        assertNull(viewModel.takeExactRecentEpisode())
        assertEquals(listOf(first, second), viewModel.episodes)
    }

    @Test fun supersededConfigFailureCannotReplaceSuccessfulRetry() = runTest(dispatcher) {
        runCurrent()
        val late = CompletableDeferred<Result<SourceConfig>>()
        coEvery { repository.loadConfig() } coAnswers { withContext(NonCancellable) { late.await() } }
        viewModel.retryLoadConfig()
        runCurrent()
        coEvery { repository.loadConfig() } returns Result.Success(SourceConfig())
        viewModel.retryLoadConfig()
        runCurrent()
        late.complete(Result.Error(IllegalStateException("old failure")))
        runCurrent()
        assertNull(viewModel.configErrorMessage)
    }

    @Test fun warmupYieldsToTheNewVisibleCategoryAndCancelsOnLeavingHome() = runTest(dispatcher) {
        every { repository.hotSearchCategories() } returns listOf(
            HotSearchCategory("chinese", "国产剧"), HotSearchCategory("korean", "韩剧")
        )
        var cancelled = false
        coEvery { repository.warmHotSearches(any(), any()) } coAnswers {
            try { awaitCancellation() } finally { cancelled = true }
        }
        viewModel.loadHotSearchCategories()
        viewModel.loadHotSearches()
        runCurrent()
        coVerify(exactly = 0) { repository.warmHotSearches(any(), any()) }
        coEvery { repository.hotSearches("chinese", any()) } returns listOf(hotItem("国产甲"))
        viewModel.loadHotSearches()
        runCurrent()
        assertEquals(listOf("国产甲"), viewModel.hotSearches.map { it.title })
        assertFalse(viewModel.isLoadingHotSearches)
        coVerify(exactly = 1) { repository.warmHotSearches("chinese", any()) }
        coEvery { repository.hotSearches("korean", any()) } returns listOf(hotItem("韩剧甲"))
        viewModel.selectHotSearchCategory("korean")
        viewModel.loadHotSearches()
        runCurrent()
        assertTrue(cancelled)
        coVerify(exactly = 1) { repository.warmHotSearches("korean", any()) }
        cancelled = false
        viewModel.cancelHotSearchWarmup()
        runCurrent()
        assertTrue(cancelled)
    }

    @Test fun switchingToAPreloadedCategoryShowsItsItemsImmediately() = runTest(dispatcher) {
        every { repository.hotSearchCategories() } returns listOf(
            HotSearchCategory("chinese", "国产剧"), HotSearchCategory("korean", "韩剧"),
        )
        coEvery { repository.hotSearches("chinese", any()) } returns listOf(hotItem("国产甲"))
        coEvery { repository.warmHotSearches("chinese", any()) } coAnswers {
            secondArg<suspend (String, List<HotSearchItem>) -> Unit>()(
                "korean", listOf(hotItem("预加载韩剧")),
            )
        }
        viewModel.loadHotSearchCategories()
        viewModel.loadHotSearches()
        runCurrent()

        viewModel.selectHotSearchCategory("korean")
        assertEquals(listOf("预加载韩剧"), viewModel.hotSearches.map { it.title })
        assertFalse(viewModel.isLoadingHotSearches)
        coVerify(exactly = 0) { repository.hotSearches("korean", any()) }
    }

    @Test fun changingHotCategoryCancelsOldResultsWithoutWritingHistory() = runTest(dispatcher) {
        every { repository.hotSearchCategories() } returns listOf(
            HotSearchCategory("chinese", "国产剧"), HotSearchCategory("korean", "韩剧")
        )
        coEvery { repository.hotSearches("chinese", any()) } coAnswers { awaitCancellation() }
        coEvery { repository.hotSearches("korean", any()) } returns listOf(hotItem("韩剧甲"))
        viewModel.loadHotSearchCategories()
        assertEquals("chinese", viewModel.selectedHotCategory)
        val old = launch { viewModel.loadHotSearches() }
        runCurrent()
        assertTrue(viewModel.isLoadingHotSearches)
        viewModel.selectHotSearchCategory("korean")
        old.cancelAndJoin()
        viewModel.loadHotSearches()
        assertEquals(listOf("韩剧甲"), viewModel.hotSearches.map { it.title })
        assertFalse(viewModel.isLoadingHotSearches)
        viewModel.selectHotSearchCategory("unknown")
        assertEquals("korean", viewModel.selectedHotCategory)
        coVerify(exactly = 0) { repository.addHistory(any()) }
    }

    @Test fun disposingViewModelLeavesPersistenceWithRepository() = runTest(dispatcher) {
        viewModel.onKeywordChange("逐玉")
        viewModel.search()
        advanceUntilIdle()
        viewModel.selectResult(a)
        advanceUntilIdle()
        viewModel.selectEpisode(0)
        advanceUntilIdle()
        val session = requireNotNull(viewModel.playbackSession)
        viewModel.playbackDidStart(session)
        advanceUntilIdle()
        viewModel.savePlaybackPosition(session, 123_000)
        val store = androidx.lifecycle.ViewModelStore()
        store.put("player", viewModel)
        store.clear()
        verify(exactly = 1) {
            repository.recordPlaybackPosition(session.workId, session.site, session.line, session.episode,
                123_000, null, session.sourceKey, session.index)
        }
    }

    @Test fun progressKeepsItsOriginalIdentityAcrossNavigation() = runTest(dispatcher) {
        viewModel.onKeywordChange("逐玉")
        viewModel.search()
        advanceUntilIdle()
        viewModel.selectResult(a)
        advanceUntilIdle()
        viewModel.selectEpisode(1)
        advanceUntilIdle()
        val original = requireNotNull(viewModel.playbackSession)
        viewModel.playbackDidStart(original)
        viewModel.savePlaybackPosition(original, 754_000)
        viewModel.selectEpisode(1)
        advanceUntilIdle()
        assertEquals(754_000L, viewModel.playbackSession?.positionMillis)
        viewModel.selectResult(b)
        advanceUntilIdle()
        viewModel.selectEpisode(1)
        advanceUntilIdle()
        assertEquals(0L, viewModel.playbackSession?.positionMillis)
        // The old player flushes after selection has already changed.
        viewModel.savePlaybackPosition(original, 755_000)
        viewModel.playbackDidStart(original)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.recordPlayback(any(), "A", "0", second.name, any(), any(), matchNullable { true }, any()) }
        coVerify(exactly = 1) { repository.recordPlaybackPosition(original.workId, "A", "0", second.name, 755_000, null, original.sourceKey, original.index) }
        assertNull(viewModel.works.single().playbackPositions[b.coverKey])
        viewModel.selectResult(a)
        advanceUntilIdle()
        viewModel.selectEpisode(0)
        advanceUntilIdle()
        assertEquals(0L, viewModel.playbackSession?.positionMillis)
        viewModel.selectEpisode(1)
        advanceUntilIdle()
        assertEquals(755_000L, viewModel.playbackSession?.positionMillis)
        viewModel.savePlaybackPosition(requireNotNull(viewModel.playbackSession), 0)
        viewModel.clearPlayback()
        viewModel.selectEpisode(1)
        advanceUntilIdle()
        assertEquals(0L, viewModel.playbackSession?.positionMillis)
    }

    @Test fun onlyActualPlaybackUpdatesTheSelectedResourcesBookmark() = runTest(dispatcher) {
        viewModel.onKeywordChange("逐玉")
        viewModel.search()
        advanceUntilIdle()
        val work = viewModel.works.single()
        viewModel.selectResult(b)
        advanceUntilIdle()
        coVerify(exactly = 0) { repository.recordPlayback(any(), any(), any(), any(), any(), any(), matchNullable { true }, any()) }
        viewModel.playbackDidStart(TigerTVViewModel.PlaybackSession(work.id, "B", "0", first.name, 0, -1))
        viewModel.selectEpisode(0)
        advanceUntilIdle()
        coVerify(exactly = 0) { repository.recordPlayback(any(), any(), any(), any(), any(), any(), matchNullable { true }, any()) }
        viewModel.playbackDidStart(requireNotNull(viewModel.playbackSession))
        advanceUntilIdle()
        assertEquals(b, viewModel.works.single().defaultSource)
        coVerify(exactly = 1) { repository.recordPlayback(work.id, "B", "0", first.name, any(), any(), matchNullable { true }, any()) }
        viewModel.playbackDidStart(requireNotNull(viewModel.playbackSession))
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.recordPlayback(work.id, "B", "0", first.name, any(), any(), matchNullable { true }, any()) }
        viewModel.selectResult(a)
        advanceUntilIdle()
        viewModel.selectEpisode(0)
        advanceUntilIdle()
        viewModel.playbackDidStart(requireNotNull(viewModel.playbackSession))
        advanceUntilIdle()
        assertEquals(a.site, viewModel.works.single().lastPlayedSite)
        coVerify(exactly = 1) { repository.recordPlayback(work.id, "A", "0", first.name, any(), any(), matchNullable { true }, any()) }
    }

    @Test fun replayingTheSameEpisodeStartsANewPersistenceSession() = runTest(dispatcher) {
        viewModel.onKeywordChange("逐玉")
        viewModel.search()
        advanceUntilIdle()
        viewModel.selectResult(a)
        advanceUntilIdle()
        viewModel.selectEpisode(0)
        viewModel.playbackDidStart(requireNotNull(viewModel.playbackSession))
        advanceUntilIdle()
        viewModel.selectEpisode(0)
        viewModel.playbackDidStart(requireNotNull(viewModel.playbackSession))
        viewModel.playbackDidStart(requireNotNull(viewModel.playbackSession))
        advanceUntilIdle()
        // Core checks persistent state; a local bookmark cannot suppress another session's start.
        coVerify(exactly = 2) { repository.recordPlayback(viewModel.works.single().id, "A", "0", first.name, any(), any(), matchNullable { true }, any()) }
    }

    @Test fun lateSearchSnapshotsKeepLocalProgressAndStillMergeNewResources() = runTest(dispatcher) {
        val finish = CompletableDeferred<Unit>()
        val initial = aggregateWorks(listOf(a)).single()
        val expanded = aggregateWorks(listOf(a, b)).single()
        coEvery { repository.search(any(), any()) } coAnswers {
            val update = secondArg<suspend (SearchResponse) -> Unit>()
            update(SearchResponse("逐玉", listOf(initial)))
            finish.await()
            update(SearchResponse("逐玉", listOf(expanded)))
            Result.Success(SearchResponse("逐玉", listOf(expanded)))
        }
        viewModel.onKeywordChange("逐玉")
        viewModel.search()
        runCurrent()
        viewModel.selectResult(a)
        runCurrent()
        viewModel.selectEpisode(1)
        runCurrent()
        val session = requireNotNull(viewModel.playbackSession)
        viewModel.playbackDidStart(session)
        viewModel.savePlaybackPosition(session, 123_000)
        finish.complete(Unit)
        advanceUntilIdle()
        val work = viewModel.works.single()
        assertEquals(listOf(a, b), work.sources)
        assertEquals("A", work.lastPlayedSite)
        assertEquals(PlaybackBookmark("0", second.name, 1), work.bookmarkFor(a))
        assertEquals(123_000L, work.positionFor(a, PlaybackSource("0", "主线", listOf(first, second)), 1))
    }

    @Test fun partialSearchIsVisibleWhileOtherSitesAreStillLoading() = runTest(dispatcher) {
        coEvery { repository.search(any(), any()) } coAnswers {
            secondArg<suspend (SearchResponse) -> Unit>()(SearchResponse("逐玉", aggregateWorks(listOf(b))))
            delay(1000)
            Result.Success(SearchResponse("逐玉", aggregateWorks(listOf(a, b))))
        }
        viewModel.onKeywordChange("逐玉")
        viewModel.search()
        runCurrent()
        assertTrue(viewModel.isSearching)
        assertEquals(listOf(b), viewModel.works.single().sources)
        advanceUntilIdle()
        assertFalse(viewModel.isSearching)
        assertEquals(listOf(a, b), viewModel.works.single().sources)
    }

    @Test fun coverRequestsShareThreePermitsAndStopAtTheFirstValidSource() = runTest(dispatcher) {
        val c = b.copy(site = "C", vodId = 100)
        val other = (2..5).map { a.copy(vodId = it, vodName = "作品$it") }
        val works = aggregateWorks(listOf(a, b, c) + other)
        coEvery { repository.search(any(), any()) } returns Result.Success(SearchResponse("逐玉", works))
        val calls = mutableListOf<Pair<String, Int>>()
        val release = CompletableDeferred<Unit>()
        coEvery { repository.resolveCoverFallback(any(), any()) } coAnswers {
            val site = firstArg<String>()
            val id = secondArg<Int>()
            calls += site to id
            release.await()
            if (site == a.site && id == a.vodId) "invalid" else "https://cover.test/$id.jpg"
        }
        viewModel.onKeywordChange("逐玉")
        viewModel.search()
        advanceUntilIdle()
        works.forEach(viewModel::loadCoverFallbackIfPossible)
        runCurrent()
        assertEquals(3, calls.size)
        assertFalse(calls.any { it.first == b.site })
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(6, calls.size)
        assertFalse(calls.any { it.first == c.site })
        assertEquals("https://cover.test/99.jpg", viewModel.coverFallbacks[b.coverKey])
    }

    @Test fun newSearchCancelsVisibleCoverRequests() = runTest(dispatcher) {
        var cancelled = false
        coEvery { repository.resolveCoverFallback(any(), any()) } coAnswers {
            try { awaitCancellation() } finally { cancelled = true }
        }
        viewModel.onKeywordChange("逐玉")
        viewModel.search()
        runCurrent()
        viewModel.loadCoverFallbackIfPossible(viewModel.works.single())
        runCurrent()
        viewModel.onKeywordChange("另一部")
        viewModel.search()
        advanceUntilIdle()
        assertTrue(cancelled)
        assertTrue(viewModel.coverFallbacks.isEmpty())
    }

    @Test fun historyChangesReachTheRepositoryInUserOrder() = runTest(dispatcher) {
        runCurrent()
        viewModel.onKeywordChange("逐玉")
        viewModel.search()
        viewModel.clearHistory()
        verifyOrder {
            repository.addHistory("逐玉")
            repository.clearHistory()
        }
        assertTrue(viewModel.searchHistory.isEmpty())
    }

    @Test fun leavingResultsCancelsSearchAndClearsState() = runTest(dispatcher) {
        var cancelled = false
        coEvery { repository.search(any(), any()) } coAnswers {
            secondArg<suspend (SearchResponse) -> Unit>()(SearchResponse("逐玉", aggregateWorks(listOf(a))))
            try { awaitCancellation() } finally { cancelled = true }
        }
        viewModel.onKeywordChange("逐玉")
        viewModel.search()
        runCurrent()
        assertFalse(viewModel.works.isEmpty())
        viewModel.clearResults()
        runCurrent()
        assertTrue(cancelled)
        assertTrue(viewModel.works.isEmpty())
        assertFalse(viewModel.isSearching)
    }

    @Test fun heroPlayResumesLastActuallyPlayedEpisodeAndRestoresLine() = runTest(dispatcher) {
        viewModel.onKeywordChange("逐玉")
        viewModel.search()
        advanceUntilIdle()
        viewModel.selectResult(b)
        advanceUntilIdle()
        viewModel.selectEpisode(1)
        advanceUntilIdle()
        viewModel.playbackDidStart(requireNotNull(viewModel.playbackSession))
        advanceUntilIdle()
        viewModel.selectResult(a)
        advanceUntilIdle()
        viewModel.selectResult(b)
        advanceUntilIdle()
        assertEquals(1, viewModel.resumeEpisodeIndex)
        viewModel.selectPlaybackSource(1)
        viewModel.selectEpisode(0)
        advanceUntilIdle()
        viewModel.playbackDidStart(requireNotNull(viewModel.playbackSession))
        advanceUntilIdle()
        viewModel.selectResult(a)
        advanceUntilIdle()
        viewModel.selectResult(b)
        advanceUntilIdle()
        assertEquals(1, viewModel.selectedPlaybackSourceIndex)
        assertEquals(0, viewModel.resumeEpisodeIndex)
        assertEquals(backup, viewModel.episodes[viewModel.resumeEpisodeIndex])
    }

    @Test fun resourcesRestoreTheirOwnEpisodeNamesAndLines() = runTest(dispatcher) {
        val bFirst = EpisodeLink("01", "https://b.test/1.m3u8")
        val bSecond = EpisodeLink("02", "https://b.test/2.m3u8")
        coEvery { repository.fetch("B", 99) } returns Result.Success(detail(b).copy(
            playSources = listOf(PlaybackSource("7", "B线路", listOf(bFirst, bSecond)))))
        viewModel.onKeywordChange("逐玉")
        viewModel.search()
        advanceUntilIdle()
        viewModel.selectResult(a)
        advanceUntilIdle()
        viewModel.selectEpisode(1)
        advanceUntilIdle()
        viewModel.playbackDidStart(requireNotNull(viewModel.playbackSession))
        advanceUntilIdle()
        viewModel.selectResult(b)
        advanceUntilIdle()
        assertEquals(0, viewModel.resumeEpisodeIndex)
        viewModel.selectEpisode(1)
        advanceUntilIdle()
        viewModel.playbackDidStart(requireNotNull(viewModel.playbackSession))
        advanceUntilIdle()
        viewModel.selectResult(a)
        advanceUntilIdle()
        assertEquals(1, viewModel.resumeEpisodeIndex)
        assertEquals(second.name, viewModel.episodes[viewModel.resumeEpisodeIndex].name)
        viewModel.selectPlaybackSource(1)
        assertEquals(0, viewModel.resumeEpisodeIndex)
        viewModel.selectResult(b)
        advanceUntilIdle()
        assertEquals(1, viewModel.resumeEpisodeIndex)
        assertEquals(bSecond.name, viewModel.episodes[viewModel.resumeEpisodeIndex].name)
        assertEquals(mapOf(a.coverKey to PlaybackBookmark("0", second.name, 1), b.coverKey to PlaybackBookmark("7", bSecond.name, 1)), viewModel.works.single().playback)
        coVerify(exactly = 2) { repository.recordPlayback(any(), any(), any(), any(), any(), any(), matchNullable { true }, any()) }
    }

    @Test fun unavailableBookmarksFallBackWithoutBorrowingAnotherLinesEpisode() = runTest(dispatcher) {
        val work = aggregateWorks(listOf(a, b)).single().copy(
            lastPlayedSite = "A", playback = mapOf(a.coverKey to PlaybackBookmark("removed", second.name, 1)))
        coEvery { repository.search(any(), any()) } returns Result.Success(SearchResponse("逐玉", listOf(work)))
        viewModel.onKeywordChange("逐玉")
        viewModel.search()
        advanceUntilIdle()
        viewModel.selectResult(a)
        advanceUntilIdle()
        assertEquals(0, viewModel.selectedPlaybackSourceIndex)
        assertEquals(0, viewModel.resumeEpisodeIndex)
        viewModel.selectResult(b)
        advanceUntilIdle()
        assertNull(viewModel.playbackBookmark)
        assertEquals(0, viewModel.resumeEpisodeIndex)
    }

    @Test fun replayingAnOlderResourceUpdatesTheWorksLastSite() = runTest(dispatcher) {
        val work = aggregateWorks(listOf(a, b)).single().copy(lastPlayedSite = "B", recentPlayedSite = "A",
            playback = mapOf(a.coverKey to PlaybackBookmark("0", first.name, 0), b.coverKey to PlaybackBookmark("0", second.name, 1)))
        coEvery { repository.search(any(), any()) } returns Result.Success(SearchResponse("逐玉", listOf(work)))
        viewModel.onKeywordChange("逐玉")
        viewModel.search()
        advanceUntilIdle()
        viewModel.selectResult(a)
        advanceUntilIdle()
        viewModel.selectEpisode(0)
        advanceUntilIdle()
        viewModel.playbackDidStart(requireNotNull(viewModel.playbackSession))
        advanceUntilIdle()
        assertEquals("A", viewModel.works.single().lastPlayedSite)
        assertEquals(PlaybackBookmark("0", second.name, 1), viewModel.works.single().playback[b.coverKey])
        coVerify(exactly = 1) { repository.recordPlayback(work.id, "A", "0", first.name, any(), any(), matchNullable { true }, any()) }
    }

    @Test fun aNewWorkIgnoresAnUnrecommendedRecentSite() = runTest(dispatcher) {
        val otherA = a.copy(vodId = 10, vodName = "另一部剧", vodPic = "https://a.test/cover.jpg")
        val otherB = b.copy(vodId = 20, vodName = "另一部剧")
        coEvery { repository.search(any(), any()) } returns Result.Success(SearchResponse("剧", aggregateWorks(listOf(a, b, otherA, otherB))))
        viewModel.onKeywordChange("剧")
        viewModel.search()
        advanceUntilIdle()
        viewModel.selectResult(b)
        advanceUntilIdle()
        viewModel.selectEpisode(0)
        advanceUntilIdle()
        viewModel.playbackDidStart(requireNotNull(viewModel.playbackSession))
        advanceUntilIdle()
        val unwatched = viewModel.works.first { it.title == "另一部剧" }
        assertNull(unwatched.lastPlayedSite)
        assertEquals("A", unwatched.defaultSource.site)
        assertEquals(otherA, unwatched.orderedSources.first { it.vodPic.isNotBlank() })
        coVerify(exactly = 0) { repository.recordPlayback(unwatched.id, any(), any(), any(), any(), any(), matchNullable { true }, any()) }
    }

    @Test fun rawPlaybackSelectionNeverCallsRepository() = runTest(dispatcher) {
        val raw = listOf(
            EpisodeLink("HLS", "https://media.test/中文/index.m3u8?sign=a%2Fb%2Bc%3D&literal=a+b"),
            EpisodeLink("MP4", "https://media.test/video.mp4?sign=a%2Fb"),
            EpisodeLink("HTML", "https://page.test/player?id=42&sign=a%2Fb%2Bc%3D")
        )
        coEvery { repository.fetch("A", 1) } returns Result.Success(detail(a).copy(
            playSources = listOf(PlaybackSource("0", "原始地址", raw))))
        viewModel.selectResult(a)
        advanceUntilIdle()
        clearMocks(repository, answers = false, recordedCalls = true)
        raw.forEachIndexed { index, episode ->
            viewModel.selectEpisode(index)
            assertEquals(episode.url, viewModel.playbackUrl)
            assertEquals(index, viewModel.selectedEpisodeIndex)
            assertEquals(a, viewModel.selectedResult)
            assertEquals(0, viewModel.selectedPlaybackSourceIndex)
            assertNull(viewModel.playbackErrorMessage)
        }
        advanceUntilIdle()
        confirmVerified(repository)
    }

    @Test fun cancelledSearchCannotPublishAnIncrementalSnapshotFromDetachedWork() = runTest(dispatcher) {
        coEvery { repository.search("old", any()) } coAnswers {
            val update = secondArg<suspend (SearchResponse) -> Unit>()
            withContext(NonCancellable) {
                delay(100)
                update(SearchResponse("old", aggregateWorks(listOf(a.copy(vodName = "old")))))
            }
            Result.Success(SearchResponse("old", emptyList()))
        }
        viewModel.onKeywordChange("old")
        viewModel.search()
        runCurrent()
        viewModel.onKeywordChange("new")
        viewModel.search()
        advanceUntilIdle()
        assertEquals("逐玉", viewModel.works.single().title)
        assertFalse(viewModel.isSearching)
    }

    @Test fun resourceSwitchSharesAPreloadAndReusesCachedDetails() = runTest(dispatcher) {
        val response = CompletableDeferred<Result<FetchResponse>>()
        coEvery { repository.fetch("B", 99) } coAnswers { response.await() }
        viewModel.selectResult(a)
        runCurrent()
        val preload = launch { viewModel.preloadDetails(listOf(a, b)) }
        runCurrent()
        assertTrue(viewModel.isDetailLoading(b))
        viewModel.selectResult(b)
        runCurrent()
        response.complete(Result.Success(detail(b)))
        preload.join()
        runCurrent()
        assertEquals("B", viewModel.fetchResponse?.site)
        assertFalse(viewModel.isDetailLoading(b))
        viewModel.selectResult(a)
        assertEquals("A", viewModel.fetchResponse?.site)
        viewModel.selectResult(b)
        assertEquals("B", viewModel.fetchResponse?.site)
        coVerify(exactly = 1) { repository.fetch("A", 1) }
        coVerify(exactly = 1) { repository.fetch("B", 99) }
    }

    @Test fun lateFailureCannotOverwriteCurrentDetailOrSearch() = runTest(dispatcher) {
        coEvery { repository.fetch("A", 1) } coAnswers {
            withContext(NonCancellable) { delay(100) }
            Result.Error(IllegalStateException("old detail"))
        }
        viewModel.selectResult(a)
        runCurrent()
        viewModel.selectResult(b)
        advanceUntilIdle()
        assertEquals("B", viewModel.fetchResponse?.site)
        assertNull(viewModel.fetchErrorMessage)
        coEvery { repository.search("old", any()) } coAnswers {
            withContext(NonCancellable) { delay(100) }
            Result.Error(IllegalStateException("old search"))
        }
        viewModel.onKeywordChange("old")
        viewModel.search()
        runCurrent()
        viewModel.onKeywordChange("new")
        viewModel.search()
        advanceUntilIdle()
        assertFalse(viewModel.works.isEmpty())
        assertNull(viewModel.searchErrorMessage)
        assertFalse(viewModel.isSearching)
    }

    @Test fun switchingLinesUsesOnlyThatLinesEpisodesAndClearsPlayback() = runTest(dispatcher) {
        viewModel.selectResult(a)
        advanceUntilIdle()
        assertEquals(listOf(first, second), viewModel.episodes)
        viewModel.selectEpisode(1)
        advanceUntilIdle()
        assertEquals(second.url, viewModel.playbackUrl)
        viewModel.selectPlaybackSource(1)
        assertEquals(listOf(backup), viewModel.episodes)
        assertEquals(-1, viewModel.selectedEpisodeIndex)
        assertNull(viewModel.playbackUrl)
        viewModel.selectEpisode(1)
        assertEquals(-1, viewModel.selectedEpisodeIndex)
    }

    @Test fun leavingDetailCancelsFetchAndAllowsReopeningTheSameSource() = runTest(dispatcher) {
        var cancelled = false
        coEvery { repository.fetch("A", 1) } coAnswers {
            try { awaitCancellation() } finally { cancelled = true }
        }
        viewModel.selectResult(a)
        runCurrent()
        viewModel.leaveDetail()
        runCurrent()
        assertTrue(cancelled)
        assertFalse(viewModel.isFetching)
        assertNull(viewModel.fetchResponse)
        coEvery { repository.fetch("A", 1) } returns Result.Success(detail(a))
        viewModel.selectResult(a)
        advanceUntilIdle()
        assertEquals(detail(a), viewModel.fetchResponse)
    }

    @Test fun switchingSitesDiscardsLateDetailAndUsesEachSitesId() = runTest(dispatcher) {
        val delayed = CompletableDeferred<Result<FetchResponse>>()
        coEvery { repository.fetch("A", 1) } coAnswers { delayed.await() }
        viewModel.selectResult(a)
        runCurrent()
        viewModel.selectResult(b)
        advanceUntilIdle()
        delayed.complete(Result.Success(detail(a)))
        advanceUntilIdle()
        assertEquals("B", viewModel.fetchResponse?.site)
        assertEquals(99, viewModel.fetchResponse?.vodId)
        assertFalse(viewModel.isFetching)
        coVerify(exactly = 1) { repository.fetch("B", 99) }
    }

    @Test fun switchingSitesClearsPlaybackAndRetainsGroupedSources() = runTest(dispatcher) {
        viewModel.onKeywordChange("逐玉")
        viewModel.search()
        advanceUntilIdle()
        viewModel.selectResult(a)
        advanceUntilIdle()
        assertEquals(listOf(a, b), viewModel.availableSources)
        viewModel.selectEpisode(0)
        runCurrent()
        viewModel.selectResult(b)
        advanceUntilIdle()
        advanceUntilIdle()
        assertNull(viewModel.playbackUrl)
        assertEquals(0, viewModel.selectedPlaybackSourceIndex)
        assertEquals(-1, viewModel.selectedEpisodeIndex)
        assertEquals(listOf(a, b), viewModel.availableSources)
    }
}
