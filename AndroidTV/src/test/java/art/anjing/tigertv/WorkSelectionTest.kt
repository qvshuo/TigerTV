package art.anjing.tigertv

import android.content.Context
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
        repository = mockk(relaxed = true)
        coEvery { repository.loadConfig() } returns Result.Success(SourceConfig())
        coEvery { repository.loadHistory() } returns emptyList()
        coEvery { repository.hotSearches(any()) } returns emptyList()
        coEvery { repository.search(any(), any()) } returns Result.Success(SearchResponse("逐玉", aggregateWorks(listOf(a, b))))
        coEvery { repository.fetch("A", 1) } returns Result.Success(detail(a))
        coEvery { repository.fetch("B", 99) } returns Result.Success(detail(b))
        coEvery { repository.resolvePlaybackUrl(any()) } answers { Result.Success(firstArg()) }
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        viewModel = TigerTVViewModel(repository, context)
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun warmupStartsAfterVisibleTitlesAndSurvivesCategorySwitchUntilHomeCloses() = runTest(dispatcher) {
        every { repository.hotSearchCategories() } returns listOf(
            HotSearchCategory("chinese", "国产剧"), HotSearchCategory("korean", "韩剧")
        )
        var cancelled = false
        coEvery { repository.warmHotSearches(any()) } coAnswers {
            try { awaitCancellation() } finally { cancelled = true }
        }
        viewModel.loadHotSearchCategories()
        viewModel.loadHotSearches()
        runCurrent()
        coVerify(exactly = 0) { repository.warmHotSearches(any()) }
        coEvery { repository.hotSearches("chinese") } returns listOf("国产甲")
        viewModel.loadHotSearches()
        runCurrent()
        assertEquals(listOf("国产甲"), viewModel.hotSearches)
        assertFalse(viewModel.isLoadingHotSearches)
        coVerify(exactly = 1) { repository.warmHotSearches("chinese") }
        coEvery { repository.hotSearches("korean") } returns listOf("韩剧甲")
        viewModel.selectHotSearchCategory("korean")
        viewModel.loadHotSearches()
        runCurrent()
        coVerify(exactly = 1) { repository.warmHotSearches(any()) }
        viewModel.cancelHotSearchWarmup()
        runCurrent()
        assertTrue(cancelled)
        viewModel.loadHotSearches()
        runCurrent()
        coVerify(exactly = 1) { repository.warmHotSearches("korean") }
        viewModel.cancelHotSearchWarmup()
        runCurrent()
    }

    @Test fun changingHotCategoryCancelsOldResultsWithoutWritingHistory() = runTest(dispatcher) {
        every { repository.hotSearchCategories() } returns listOf(
            HotSearchCategory("chinese", "国产剧"), HotSearchCategory("korean", "韩剧")
        )
        coEvery { repository.hotSearches("chinese") } coAnswers { awaitCancellation() }
        coEvery { repository.hotSearches("korean") } returns listOf("韩剧甲")
        viewModel.loadHotSearchCategories()
        assertEquals("chinese", viewModel.selectedHotCategory)
        val old = launch { viewModel.loadHotSearches() }
        runCurrent()
        assertTrue(viewModel.isLoadingHotSearches)
        viewModel.selectHotSearchCategory("korean")
        old.cancelAndJoin()
        viewModel.loadHotSearches()
        assertEquals(listOf("韩剧甲"), viewModel.hotSearches)
        assertFalse(viewModel.isLoadingHotSearches)
        viewModel.selectHotSearchCategory("unknown")
        assertEquals("korean", viewModel.selectedHotCategory)
        coVerify(exactly = 0) { repository.saveHistory(any()) }
    }

    @Test fun onlyActualPlaybackUpdatesTheSelectedResourcesBookmark() = runTest(dispatcher) {
        viewModel.onKeywordChange("逐玉")
        viewModel.search()
        advanceUntilIdle()
        val work = viewModel.works.single()
        viewModel.selectResult(b)
        advanceUntilIdle()
        coVerify(exactly = 0) { repository.recordPlayback(any(), any(), any(), any()) }
        viewModel.playbackDidStart("stale-url")
        viewModel.selectEpisode(0)
        advanceUntilIdle()
        coVerify(exactly = 0) { repository.recordPlayback(any(), any(), any(), any()) }
        viewModel.playbackDidStart(first.url)
        advanceUntilIdle()
        assertEquals(b, viewModel.works.single().defaultSource)
        coVerify(exactly = 1) { repository.recordPlayback(work.id, "B", "0", first.name) }
        viewModel.playbackDidStart(first.url) // Resume must not append duplicate history.
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.recordPlayback(work.id, "B", "0", first.name) }
        viewModel.selectResult(a)
        advanceUntilIdle()
        viewModel.selectEpisode(0)
        advanceUntilIdle()
        viewModel.playbackDidStart(first.url)
        advanceUntilIdle()
        assertEquals(a.site, viewModel.works.single().lastPlayedSite)
        coVerify(exactly = 1) { repository.recordPlayback(work.id, "A", "0", first.name) }
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

    @Test fun newSearchCancelsVisibleCoverRequests() = runTest(dispatcher) {
        var cancelled = false
        coEvery { repository.resolveCoverFallback(any(), any()) } coAnswers {
            try { awaitCancellation() } finally { cancelled = true }
        }
        viewModel.onKeywordChange("逐玉")
        viewModel.search()
        runCurrent()
        viewModel.loadCoverFallbackIfPossible(a)
        runCurrent()
        viewModel.onKeywordChange("另一部")
        viewModel.search()
        advanceUntilIdle()
        assertTrue(cancelled)
        assertTrue(viewModel.coverFallbacks.isEmpty())
    }

    @Test fun historyWritesPreserveUserOrder() = runTest(dispatcher) {
        var persisted = listOf<String>()
        coEvery { repository.saveHistory(any()) } coAnswers {
            val items = firstArg<List<String>>()
            if (items.isNotEmpty()) delay(100)
            persisted = items
        }
        runCurrent()
        viewModel.onKeywordChange("逐玉")
        viewModel.search()
        runCurrent()
        viewModel.clearHistory()
        advanceUntilIdle()
        assertTrue(persisted.isEmpty())
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
        viewModel.playbackDidStart(second.url)
        advanceUntilIdle()
        viewModel.selectResult(a)
        advanceUntilIdle()
        viewModel.selectResult(b)
        advanceUntilIdle()
        assertEquals(1, viewModel.resumeEpisodeIndex)
        viewModel.selectPlaybackSource(1)
        viewModel.selectEpisode(0)
        advanceUntilIdle()
        viewModel.playbackDidStart(backup.url)
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
        viewModel.playbackDidStart(second.url)
        advanceUntilIdle()
        viewModel.selectResult(b)
        advanceUntilIdle()
        assertEquals(0, viewModel.resumeEpisodeIndex)
        viewModel.selectEpisode(1)
        advanceUntilIdle()
        viewModel.playbackDidStart(bSecond.url)
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
        assertEquals(mapOf("A" to PlaybackBookmark("0", second.name), "B" to PlaybackBookmark("7", bSecond.name)), viewModel.works.single().playback)
        coVerify(exactly = 2) { repository.recordPlayback(any(), any(), any(), any()) }
    }

    @Test fun unavailableBookmarksFallBackWithoutBorrowingAnotherLinesEpisode() = runTest(dispatcher) {
        val work = aggregateWorks(listOf(a, b)).single().copy(
            lastPlayedSite = "A", playback = mapOf("A" to PlaybackBookmark("removed", second.name)))
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
            playback = mapOf("A" to PlaybackBookmark("0", first.name), "B" to PlaybackBookmark("0", second.name)))
        coEvery { repository.search(any(), any()) } returns Result.Success(SearchResponse("逐玉", listOf(work)))
        viewModel.onKeywordChange("逐玉")
        viewModel.search()
        advanceUntilIdle()
        viewModel.selectResult(a)
        advanceUntilIdle()
        viewModel.selectEpisode(0)
        advanceUntilIdle()
        viewModel.playbackDidStart(first.url)
        advanceUntilIdle()
        assertEquals("A", viewModel.works.single().lastPlayedSite)
        assertEquals(PlaybackBookmark("0", second.name), viewModel.works.single().playback["B"])
        coVerify(exactly = 1) { repository.recordPlayback(work.id, "A", "0", first.name) }
    }

    @Test fun aNewWorkInheritsTheRecentSiteWithoutSavingItsOwnHistory() = runTest(dispatcher) {
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
        viewModel.playbackDidStart(first.url)
        advanceUntilIdle()
        val unwatched = viewModel.works.first { it.title == "另一部剧" }
        assertNull(unwatched.lastPlayedSite)
        assertEquals("B", unwatched.defaultSource.site)
        assertEquals(otherA, unwatched.orderedSources.first { it.vodPic.isNotBlank() })
        coVerify(exactly = 0) { repository.recordPlayback(unwatched.id, any(), any(), any()) }
    }

    @Test fun switchingLinesUsesOnlyThatLinesEpisodesAndClearsPlayback() = runTest(dispatcher) {
        viewModel.selectResult(a)
        advanceUntilIdle()
        assertEquals(listOf(first, second), viewModel.episodes)
        viewModel.selectEpisode(1)
        advanceUntilIdle()
        assertEquals(second.url, viewModel.resolvedPlaybackUrl)
        viewModel.selectPlaybackSource(1)
        assertEquals(listOf(backup), viewModel.episodes)
        assertEquals(-1, viewModel.selectedEpisodeIndex)
        assertNull(viewModel.resolvedPlaybackUrl)
        viewModel.selectEpisode(1) // Last episode cannot spill into another line.
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

    @Test fun switchingSitesCancelsPlaybackResolutionAndRetainsGroupedSources() = runTest(dispatcher) {
        viewModel.onKeywordChange("逐玉")
        viewModel.search()
        advanceUntilIdle()
        viewModel.selectResult(a)
        advanceUntilIdle()
        assertEquals(listOf(a, b), viewModel.availableSources)
        val delayed = CompletableDeferred<Result<String>>()
        coEvery { repository.resolvePlaybackUrl(first.url) } coAnswers { delayed.await() }
        viewModel.selectEpisode(0)
        runCurrent()
        viewModel.selectResult(b)
        advanceUntilIdle()
        delayed.complete(Result.Success(first.url))
        advanceUntilIdle()
        assertNull(viewModel.resolvedPlaybackUrl)
        assertFalse(viewModel.isResolvingPlayback)
        assertEquals(0, viewModel.selectedPlaybackSourceIndex)
        assertEquals(-1, viewModel.selectedEpisodeIndex)
        assertEquals(listOf(a, b), viewModel.availableSources)
    }
}
