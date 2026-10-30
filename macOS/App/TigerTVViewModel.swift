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
    @Published private(set) var resolvedPlaybackUrl: String?
    @Published private(set) var playbackRevision = 0

    @Published private(set) var isSearching = false
    @Published private(set) var isFetching = false
    @Published private(set) var isResolvingPlayback = false

    @Published private(set) var configErrorMessage: String?
    @Published private(set) var searchErrorMessage: String?
    @Published private(set) var fetchErrorMessage: String?
    @Published private(set) var playbackErrorMessage: String?

    @Published private(set) var searchHistory: [String] = []

    /// Source-specific fallback URLs; failures are not cached.
    @Published private(set) var coverFallbacks: [String: URL] = [:]
    private var historySaveTask: Task<Void, Never>?
    private var historyRevision = 0
    private var sourceSaveTask: Task<Void, Never>?
    func playbackDidStart(url: String) {
        guard resolvedPlaybackUrl == url, let episode = selectedEpisode,
              let lines = fetchResponse?.playSources, lines.indices.contains(selectedPlaybackSourceIndex), let source = selectedResult,
              let work = selectedWork else { return }
        let line = lines[selectedPlaybackSourceIndex]
        let bookmark = PlaybackBookmark(site: source.site, line: line.id, episode: episode.name)
        guard work.playback != bookmark || work.recentPlayedSite != source.site else { return }
        works = works.map {
            var updated = $0
            updated.recentPlayedSite = source.site
            if updated.id == work.id { updated.lastPlayedSite = source.site; updated.playback = bookmark }
            return updated
        }
        let previous = sourceSaveTask
        sourceSaveTask = Task {
            await previous?.value
            await repository.recordPlayback(workId: work.id, site: source.site, line: line.id, episode: episode.name)
        }
    }
    private var coverLoadsInFlight: Set<String> = []

    private var searchTask: Task<Void, Never>?
    private var fetchTask: Task<Void, Never>?
    private var playbackTask: Task<Void, Never>?

    // Discard results from cancelled or superseded operations.
    private var searchGeneration = 0
    private var fetchGeneration = 0
    private var playbackGeneration = 0

    convenience init() {
        let cachesDir = FileManager.default
            .urls(for: .cachesDirectory, in: .userDomainMask)
            .first ?? FileManager.default.temporaryDirectory
        let cacheDirectory = cachesDir
            .appendingPathComponent(Bundle.main.bundleIdentifier ?? "art.anjing.TigerTV")
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        let dataDirectory = support.appendingPathComponent(Bundle.main.bundleIdentifier ?? "art.anjing.TigerTV")
            .appendingPathComponent("shared-v1")
        let repository = SharedCoreRepository(directory: cacheDirectory.appendingPathComponent("shared-v1"), dataDirectory: dataDirectory)
        self.init(repository: repository)
    }

    init(repository: any VideoRepository) {
        self.repository = repository
        Task {
            await loadSearchHistory()
            await loadConfig()
        }
    }

    func loadConfig() async {
        configErrorMessage = nil
        let result = await repository.loadConfig()
        if case .failure(let error) = result {
            configErrorMessage = error.localizedDescription
        }
    }

    func retryLoadConfig() {
        Task { await loadConfig() }
    }

    func loadCoverFallbackIfPossible(for result: SearchResult) async {
        guard result.vodPic.isEmpty, coverFallbacks[result.id] == nil else { return }
        guard !coverLoadsInFlight.contains(result.id) else { return }
        coverLoadsInFlight.insert(result.id)
        defer { coverLoadsInFlight.remove(result.id) }
        let generation = searchGeneration
        if let url = await repository.coverFallbackURL(siteName: result.site, vodId: result.vodId),
           generation == searchGeneration, !Task.isCancelled {
            coverFallbacks[result.id] = url
        }
    }

    func clearActiveError() {
        searchErrorMessage = nil
        fetchErrorMessage = nil
        playbackErrorMessage = nil
    }

    func search() {
        let trimmed = keyword.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }

        cancelAll(includeSearch: true)

        submittedKeyword = trimmed
        addHistory(trimmed)

        isSearching = true
        isFetching = false
        isResolvingPlayback = false
        searchErrorMessage = nil
        fetchErrorMessage = nil
        playbackErrorMessage = nil
        works = []
        coverFallbacks = [:]
        selectedResult = nil
        fetchResponse = nil
        selectedEpisode = nil
        resolvedPlaybackUrl = nil

        searchGeneration += 1
        let gen = searchGeneration
        searchTask = Task {
            let result = await repository.search(keyword: trimmed) { response in
                await self.applySearchUpdate(response, generation: gen)
            }
            guard gen == searchGeneration, !Task.isCancelled else { return }
            switch result {
            case .success(let response):
                isSearching = false
                works = response.works
            case .failure(let error):
                isSearching = false
                searchErrorMessage = error.localizedDescription
            }
        }
    }

    private func applySearchUpdate(_ response: SearchResponse, generation: Int) {
        guard generation == searchGeneration, !Task.isCancelled else { return }
        works = response.works
    }

    var resumeEpisode: EpisodeLink? {
        let bookmark = selectedWork?.playback
        return episodes.first { $0.name == bookmark?.episode } ?? episodes.first
    }

    func selectResult(_ result: SearchResult, forceRefresh: Bool = false) {
        guard forceRefresh || selectedResult?.id != result.id else { return }

        cancelAll()

        selectedResult = result
        selectedPlaybackSourceIndex = 0
        fetchResponse = nil
        selectedEpisode = nil
        resolvedPlaybackUrl = nil
        isFetching = true
        isResolvingPlayback = false
        fetchErrorMessage = nil
        playbackErrorMessage = nil

        fetchGeneration += 1
        let gen = fetchGeneration
        fetchTask = Task {
            let result = await repository.fetch(siteName: result.site, vodId: result.vodId)
            guard gen == fetchGeneration, !Task.isCancelled else { return }
            switch result {
            case .success(let response):
                isFetching = false
                fetchResponse = response
                if let bookmark = selectedWork?.playback,
                   bookmark.site == selectedResult?.site,
                   let index = response.playSources.firstIndex(where: { $0.id == bookmark.line }) {
                    selectedPlaybackSourceIndex = index
                }
            case .failure(let error):
                isFetching = false
                fetchErrorMessage = error.localizedDescription
            }
        }
    }

    func selectEpisode(_ episode: EpisodeLink) {
        cancelAll()
        selectedEpisode = episode
        resolvedPlaybackUrl = nil
        isResolvingPlayback = true
        playbackErrorMessage = nil

        playbackGeneration += 1
        let gen = playbackGeneration
        playbackTask = Task {
            let result = await repository.resolvePlaybackUrl(url: episode.url)
            guard gen == playbackGeneration, !Task.isCancelled else { return }
            switch result {
            case .success(let url):
                isResolvingPlayback = false
                resolvedPlaybackUrl = url
                playbackRevision += 1
            case .failure(let error):
                isResolvingPlayback = false
                playbackErrorMessage = error.localizedDescription
            }
        }
    }

    var nextEpisode: EpisodeLink? {
        guard let selectedEpisode, let index = episodes.firstIndex(of: selectedEpisode),
              episodes.indices.contains(index + 1) else { return nil }
        return episodes[index + 1]
    }

    func retryPlayback() {
        guard let selectedEpisode else { return }
        selectEpisode(selectedEpisode)
    }

    func clearPlayback() {
        cancelAll()
        selectedEpisode = nil
        resolvedPlaybackUrl = nil
        playbackErrorMessage = nil
        isResolvingPlayback = false
    }

    func clearFetch() {
        cancelAll()
        selectedResult = nil
        fetchResponse = nil
        isFetching = false
    }

    func clearResults() {
        cancelAll(includeSearch: true)
        works = []
        submittedKeyword = ""
        selectedResult = nil
        fetchResponse = nil
        selectedEpisode = nil
        resolvedPlaybackUrl = nil
        isSearching = false
        isFetching = false
        isResolvingPlayback = false
    }

    func removeHistory(_ item: String) {
        searchHistory.removeAll { $0 == item }
        saveHistory()
    }

    func clearHistory() {
        searchHistory.removeAll()
        saveHistory()
    }

    private func addHistory(_ item: String) {
        searchHistory.removeAll { $0 == item }
        searchHistory.insert(item, at: 0)
        if searchHistory.count > 20 {
            searchHistory = Array(searchHistory.prefix(20))
        }
        saveHistory()
    }

    private func saveHistory() {
        historyRevision += 1
        let snapshot = searchHistory
        let previous = historySaveTask
        historySaveTask = Task {
            await previous?.value
            await repository.saveHistory(snapshot)
        }
    }

    private func loadSearchHistory() async {
        guard historyRevision == 0 else { return }
        let revision = historyRevision
        let saved = await repository.loadHistory()
        if revision == historyRevision { searchHistory = saved }
    }

    private func cancelAll(includeSearch: Bool = false) {
        if includeSearch { searchTask?.cancel() }
        fetchTask?.cancel()
        playbackTask?.cancel()
    }
}
