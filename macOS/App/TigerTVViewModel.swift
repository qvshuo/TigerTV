import Foundation
import SwiftUI

@MainActor
final class TigerTVViewModel: ObservableObject {
    private let repository: TigerTVRepository
    private let historyStore: SearchHistoryStore

    @Published var keyword = ""
    @Published private(set) var submittedKeyword = ""
    @Published private(set) var results: [SearchResult] = []
    @Published private(set) var selectedResult: SearchResult?
    @Published private(set) var fetchResponse: FetchResponse?
    @Published private(set) var selectedEpisode: EpisodeLink?
    @Published private(set) var resolvedPlaybackUrl: String?

    @Published private(set) var isSearching = false
    @Published private(set) var isFetching = false
    @Published private(set) var isResolvingPlayback = false

    @Published private(set) var configErrorMessage: String?
    @Published private(set) var searchErrorMessage: String?
    @Published private(set) var fetchErrorMessage: String?
    @Published private(set) var playbackErrorMessage: String?

    @Published private(set) var searchHistory: [String] = []

    private var searchTask: Task<Void, Never>?
    private var fetchTask: Task<Void, Never>?
    private var playbackTask: Task<Void, Never>?

    convenience init() {
        let cacheDirectory = FileManager.default
            .urls(for: .cachesDirectory, in: .userDomainMask)
            .first!
            .appendingPathComponent(Bundle.main.bundleIdentifier ?? "art.anjing.TigerTV")
        let repository = TigerTVRepository(
            configDataSource: ConfigDataSource(cacheDirectory: cacheDirectory),
            apiClient: MacCMSApiClient(),
            playbackResolver: PlaybackURLResolver()
        )
        self.init(repository: repository, historyStore: SearchHistoryStore())
    }

    init(repository: TigerTVRepository, historyStore: SearchHistoryStore) {
        self.repository = repository
        self.historyStore = historyStore
        Task {
            await loadConfig()
            await loadSearchHistory()
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

    func clearActiveError() {
        searchErrorMessage = nil
        fetchErrorMessage = nil
        playbackErrorMessage = nil
    }

    func search() {
        let trimmed = keyword.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }

        cancelAll()

        submittedKeyword = trimmed
        addHistory(trimmed)

        isSearching = true
        isFetching = false
        isResolvingPlayback = false
        searchErrorMessage = nil
        fetchErrorMessage = nil
        playbackErrorMessage = nil
        results = []
        selectedResult = nil
        fetchResponse = nil
        selectedEpisode = nil
        resolvedPlaybackUrl = nil

        searchTask = Task {
            let result = await repository.search(keyword: trimmed)
            guard !Task.isCancelled else {
                isSearching = false
                return
            }
            switch result {
            case .success(let response):
                isSearching = false
                results = response.results
            case .failure(let error):
                isSearching = false
                searchErrorMessage = error.localizedDescription
            }
        }
    }

    func selectResult(_ result: SearchResult) {
        guard selectedResult?.id != result.id else { return }

        cancelAll()

        selectedResult = result
        fetchResponse = nil
        selectedEpisode = nil
        resolvedPlaybackUrl = nil
        isFetching = true
        isResolvingPlayback = false
        fetchErrorMessage = nil
        playbackErrorMessage = nil

        fetchTask = Task {
            let result = await repository.fetch(siteName: result.site, vodId: result.vodId)
            guard !Task.isCancelled else {
                isFetching = false
                return
            }
            switch result {
            case .success(let response):
                isFetching = false
                fetchResponse = response
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

        playbackTask = Task {
            let result = await repository.resolvePlaybackUrl(url: episode.url)
            guard !Task.isCancelled else {
                isResolvingPlayback = false
                return
            }
            switch result {
            case .success(let url):
                isResolvingPlayback = false
                resolvedPlaybackUrl = url
            case .failure(let error):
                isResolvingPlayback = false
                playbackErrorMessage = error.localizedDescription
            }
        }
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
        cancelAll()
        results = []
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
        Task {
            await historyStore.save(searchHistory)
        }
    }

    private func loadSearchHistory() async {
        searchHistory = await historyStore.load()
    }

    private func cancelAll() {
        searchTask?.cancel()
        fetchTask?.cancel()
        playbackTask?.cancel()
    }
}
