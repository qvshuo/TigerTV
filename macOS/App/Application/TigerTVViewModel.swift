import Foundation
import SwiftUI

@MainActor
final class TigerTVViewModel: ObservableObject {
    private let repository: any VideoRepository

    @Published var keyword = ""
    @Published private(set) var submittedKeyword = ""
    @Published private(set) var works: [VideoWork] = []
    @Published private(set) var selectedPlaybackSourceIndex = 0
    private var selectedWork: VideoWork? {
        works.first { $0.sources.contains { $0.id == selectedResult?.id } }
    }
    var availableSources: [SearchResult] { selectedWork?.sources ?? [] }
    var episodes: [EpisodeLink] {
        guard let response = fetchResponse else { return [] }
        let sources = response.playSources
        return sources.indices.contains(selectedPlaybackSourceIndex) ? sources[selectedPlaybackSourceIndex].episodes : []
    }
    func selectPlaybackSource(_ index: Int) {
        guard let sources = fetchResponse?.playSources, sources.indices.contains(index), index != selectedPlaybackSourceIndex else { return }
        clearPlayback()
        selectedPlaybackSourceIndex = index
    }
    func retryFetch() {
        guard let current = selectedResult else { return }
        selectResult(current, forceRefresh: true)
    }

    @Published private(set) var selectedResult: SearchResult?
    @Published private(set) var fetchResponse: FetchResponse?
    @Published private(set) var selectedEpisode: EpisodeLink?
    @Published private(set) var playbackRevision = 0

    @Published private(set) var isSearching = false
    @Published private(set) var isFetching = false

    @Published private(set) var configErrorMessage: String?
    @Published private(set) var searchErrorMessage: String?
    @Published private(set) var fetchErrorMessage: String?

    @Published private(set) var searchHistory: [String] = []
    @Published private(set) var hotSearches: [HotSearchItem] = []
    @Published private(set) var hotSearchCategories: [HotSearchCategory] = []
    @Published private(set) var selectedHotCategory = ""
    @Published private(set) var isLoadingHotSearches = false
    @Published private(set) var hotSearchRevision = 0

    /// Source-specific fallback URLs; failures are not cached.
    @Published private(set) var coverFallbacks: [String: URL] = [:]
    private var historySaveTask: Task<Void, Never>?
    private var historyRevision = 0
    private var playbackSaveTask: Task<Void, Never>?
    private var startedPlaybackRevision = -1
    private var changedPlaybackWorks = Set<String>()
    private var latestPlayedSite: String?
    var playbackSession: PlaybackSession? {
        guard let work = selectedWork, let source = selectedResult, let episode = selectedEpisode,
              let lines = fetchResponse?.playSources, lines.indices.contains(selectedPlaybackSourceIndex) else { return nil }
        let line = lines[selectedPlaybackSourceIndex]
        return PlaybackSession(workId: work.id, site: source.site, line: line.id, episode: episode.name,
            positionMillis: work.playbackPositions[source.site]?[line.id]?[episode.name] ?? 0, revision: playbackRevision)
    }

    func playbackDidStart(_ session: PlaybackSession) {
        guard let active = playbackSession, session.revision == active.revision,
              session.workId == active.workId, session.site == active.site, session.line == active.line,
              session.episode == active.episode, startedPlaybackRevision != session.revision else { return }
        startedPlaybackRevision = session.revision
        changedPlaybackWorks.insert(session.workId)
        latestPlayedSite = session.site
        works = works.map {
            var updated = $0
            updated.recentPlayedSite = session.site
            if updated.id == session.workId {
                updated.lastPlayedSite = session.site
                updated.playback[session.site] = PlaybackBookmark(line: session.line, episode: session.episode)
            }
            return updated
        }
        let previous = playbackSaveTask
        playbackSaveTask = Task {
            await previous?.value
            await repository.recordPlayback(workId: session.workId, site: session.site, line: session.line, episode: session.episode)
        }
    }

    func savePlaybackPosition(_ session: PlaybackSession, positionMillis: Int64) {
        guard positionMillis >= 0 else { return }
        if let index = works.firstIndex(where: { $0.id == session.workId }) {
            changedPlaybackWorks.insert(session.workId)
            works[index].playbackPositions[session.site, default: [:]][session.line, default: [:]][session.episode] = positionMillis
        }
        let previous = playbackSaveTask
        playbackSaveTask = Task {
            await previous?.value
            await repository.recordPlaybackPosition(workId: session.workId, site: session.site, line: session.line,
                episode: session.episode, positionMillis: positionMillis)
        }
    }
    func flushPersistence() async {
        await playbackSaveTask?.value
        await historySaveTask?.value
    }

    private var coverLoadsInFlight: Set<String> = []

    private var searchTask: Task<Void, Never>?
    private var fetchTask: Task<Void, Never>?
    private var configTask: Task<Void, Never>?
    private var hotSearchGeneration = 0
    private var hotWarmTask: Task<Void, Never>?

    // Discard results from cancelled or superseded operations.
    private var searchGeneration = 0
    private var fetchGeneration = 0

    convenience init() {
        self.init(repository: SharedCoreRepository.shared)
    }

    init(repository: any VideoRepository) {
        self.repository = repository
        configTask = Task {
            await loadSearchHistory()
            guard !Task.isCancelled else { return }
            await loadConfig()
        }
    }

    func loadHotSearchCategories() async {
        let categories = await repository.loadHotSearchCategories()
        guard !Task.isCancelled else { return }
        hotSearchCategories = categories
        if selectedHotCategory.isEmpty { selectedHotCategory = categories.first?.id ?? "" }
    }

    func cancelHotSearchWarmup() {
        hotWarmTask?.cancel()
        hotWarmTask = nil
    }

    func selectHotSearchCategory(_ category: String) {
        guard category != selectedHotCategory, hotSearchCategories.contains(where: { $0.id == category }) else { return }
        hotSearchGeneration += 1
        isLoadingHotSearches = true
        selectedHotCategory = category
        hotSearches = []
    }

    func retryHotSearches() { hotSearchRevision += 1 }

    func loadHotSearches() async {
        let category = selectedHotCategory
        guard !category.isEmpty else { return }
        hotSearchGeneration += 1
        let generation = hotSearchGeneration
        isLoadingHotSearches = true
        hotSearches = []
        defer { if generation == hotSearchGeneration { isLoadingHotSearches = false } }
        let titles = await repository.loadHotSearches(category: category)
        if !Task.isCancelled && generation == hotSearchGeneration && selectedHotCategory == category {
            hotSearches = titles
            if !titles.isEmpty && hotWarmTask == nil {
                let repository = self.repository
                hotWarmTask = Task { await repository.warmHotSearches(category: category) }
            }
        }
    }

    func loadConfig() async {
        configErrorMessage = nil
        let result = await repository.loadConfig()
        guard !Task.isCancelled else { return }
        if case .failure = result {
            configErrorMessage = "资源列表加载失败，请重试"
        }
    }

    func retryLoadConfig() {
        configTask?.cancel()
        configTask = Task { await loadConfig() }
    }

    func loadCoverFallbackIfPossible(for result: SearchResult) async {
        guard result.vodPic.isEmpty, coverFallbacks[result.id] == nil else { return }
        guard !coverLoadsInFlight.contains(result.id) else { return }
        let generation = searchGeneration
        coverLoadsInFlight.insert(result.id)
        defer { if generation == searchGeneration { coverLoadsInFlight.remove(result.id) } }
        if let url = await repository.coverFallbackURL(siteName: result.site, vodId: result.vodId),
           generation == searchGeneration, !Task.isCancelled {
            coverFallbacks[result.id] = url
        }
    }

    func clearActiveError() {
        searchErrorMessage = nil
        fetchErrorMessage = nil
    }

    func search() {
        let trimmed = keyword.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }

        cancelSearch()
        fetchTask?.cancel()

        submittedKeyword = trimmed
        addHistory(trimmed)

        isSearching = true
        isFetching = false
        searchErrorMessage = nil
        fetchErrorMessage = nil
        works = []
        changedPlaybackWorks.removeAll()
        latestPlayedSite = nil
        coverFallbacks = [:]
        selectedResult = nil
        fetchResponse = nil
        selectedEpisode = nil

        let gen = searchGeneration
        searchTask = Task {
            await playbackSaveTask?.value
            guard gen == searchGeneration, !Task.isCancelled else { return }
            let result = await repository.search(keyword: trimmed) { response in
                await self.applySearchUpdate(response, generation: gen)
            }
            guard gen == searchGeneration, !Task.isCancelled else { return }
            switch result {
            case .success(let response):
                isSearching = false
                applySearchWorks(response.works)
            case .failure:
                isSearching = false
                searchErrorMessage = "搜索失败，请重试"
            }
        }
    }

    private func applySearchUpdate(_ response: SearchResponse, generation: Int) {
        guard generation == searchGeneration, !Task.isCancelled else { return }
        applySearchWorks(response.works)
    }

    private func applySearchWorks(_ incoming: [VideoWork]) {
        guard !changedPlaybackWorks.isEmpty else { works = incoming; return }
        // Preserve local playback while later sites add resources to the same work.
        let local = Dictionary(uniqueKeysWithValues: works.filter { changedPlaybackWorks.contains($0.id) }.map { ($0.id, $0) })
        works = incoming.map { work in
            var updated = work
            if let site = latestPlayedSite { updated.recentPlayedSite = site }
            if let played = local[work.id] {
                updated.lastPlayedSite = played.lastPlayedSite
                updated.playback = played.playback
                updated.playbackPositions = played.playbackPositions
            }
            return updated
        }
    }

    var resumeEpisode: EpisodeLink? {
        guard let lines = fetchResponse?.playSources, lines.indices.contains(selectedPlaybackSourceIndex) else { return nil }
        let bookmark = selectedResult.flatMap { selectedWork?.playback[$0.site] }
        return lines[selectedPlaybackSourceIndex].resumeEpisode(bookmark)
    }

    func selectResult(_ result: SearchResult, forceRefresh: Bool = false) {
        guard forceRefresh || selectedResult?.id != result.id else { return }

        fetchTask?.cancel()

        selectedResult = result
        selectedPlaybackSourceIndex = 0
        fetchResponse = nil
        selectedEpisode = nil
        isFetching = true
        fetchErrorMessage = nil

        fetchGeneration += 1
        let gen = fetchGeneration
        fetchTask = Task {
            let result = await repository.fetch(siteName: result.site, vodId: result.vodId)
            guard gen == fetchGeneration, !Task.isCancelled else { return }
            switch result {
            case .success(let response):
                isFetching = false
                fetchResponse = response
                if let source = selectedResult, let bookmark = selectedWork?.playback[source.site],
                   let index = response.playSources.firstIndex(where: { $0.id == bookmark.line }) {
                    selectedPlaybackSourceIndex = index
                }
            case .failure:
                isFetching = false
                fetchErrorMessage = "详情加载失败，请重试"
            }
        }
    }

    func selectEpisode(_ episode: EpisodeLink) {
        guard episodes.contains(episode) else { return }
        selectedEpisode = episode
        playbackRevision += 1
    }

    func retryPlayback() {
        guard let selectedEpisode else { return }
        selectEpisode(selectedEpisode)
    }

    func clearPlayback() {
        selectedEpisode = nil
    }

    func clearFetch() {
        fetchTask?.cancel()
        selectedResult = nil
        fetchResponse = nil
        isFetching = false
    }

    func clearResults() {
        cancelSearch()
        fetchTask?.cancel()
        works = []
        coverFallbacks = [:]
        changedPlaybackWorks.removeAll()
        latestPlayedSite = nil
        submittedKeyword = ""
        selectedResult = nil
        fetchResponse = nil
        selectedEpisode = nil
        isSearching = false
        isFetching = false
    }

    func removeHistory(_ item: String) {
        searchHistory.removeAll { $0 == item }
        saveHistory { await $0.removeHistory(item) }
    }

    func clearHistory() {
        searchHistory.removeAll()
        saveHistory { await $0.clearHistory() }
    }

    private func addHistory(_ item: String) {
        searchHistory.removeAll { $0 == item }
        searchHistory.insert(item, at: 0)
        if searchHistory.count > 20 {
            searchHistory = Array(searchHistory.prefix(20))
        }
        saveHistory { await $0.addHistory(item) }
    }

    private func saveHistory(_ update: @escaping @Sendable (any VideoRepository) async -> Void) {
        historyRevision += 1
        let previous = historySaveTask
        historySaveTask = Task {
            await previous?.value
            await update(repository)
        }
    }

    private func loadSearchHistory() async {
        guard historyRevision == 0 else { return }
        let revision = historyRevision
        let saved = await repository.loadHistory()
        if revision == historyRevision && !Task.isCancelled { searchHistory = saved }
    }

    func cancelRequests() {
        cancelSearch()
        fetchTask?.cancel()
        configTask?.cancel()
        cancelHotSearchWarmup()
        isSearching = false
        isFetching = false
    }

    private func cancelSearch() {
        searchTask?.cancel()
        searchGeneration += 1
        coverLoadsInFlight.removeAll()
    }
}
