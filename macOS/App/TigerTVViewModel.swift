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

    /// 空封面卡片的懒加载兜底 URL（key 为 result.id）。
    /// 仅在卡片可见时按需填充；失败不入表，卡片重入视口时自然重试。
    @Published private(set) var coverFallbacks: [String: URL] = [:]
    private var coverLoadsInFlight: Set<String> = []

    private var searchTask: Task<Void, Never>?
    private var fetchTask: Task<Void, Never>?
    private var playbackTask: Task<Void, Never>?

    // Generation token：区分"当前活跃 task"与"已被取消的旧 task"。
    // 旧 task 的 HTTP 请求无法真正中断（见 HTTPClient），其完成回调若直接
    // 写共享 @Published 标志位会污染新 task 的状态。token 在每次发起新请求时
    // 自增并被捕获；旧 task 完成后比对 token，不等则丢弃结果。
    private var searchGeneration = 0
    private var fetchGeneration = 0
    private var playbackGeneration = 0

    convenience init() {
        // 安全解包 cachesDirectory（测试宿主下可能为空），避免 force unwrap 崩溃。
        let cachesDir = FileManager.default
            .urls(for: .cachesDirectory, in: .userDomainMask)
            .first ?? FileManager.default.temporaryDirectory
        let cacheDirectory = cachesDir
            .appendingPathComponent(Bundle.main.bundleIdentifier ?? "art.anjing.TigerTV")
        let repository = TigerTVRepository(
            configDataSource: ConfigDataSource(cacheDirectory: cacheDirectory),
            apiClient: MacCMSApiClient(),
            playbackResolver: PlaybackURLResolver(),
            coverURLStore: CoverFallbackURLStore(directory: cacheDirectory)
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

    /// 卡片可见时调用：`vod_pic` 为空才发起 detail 兜底请求。
    func loadCoverFallbackIfPossible(for result: SearchResult) async {
        guard result.vodPic.isEmpty, coverFallbacks[result.id] == nil else { return }
        guard !coverLoadsInFlight.contains(result.id) else { return }
        coverLoadsInFlight.insert(result.id)
        defer { coverLoadsInFlight.remove(result.id) }
        if let url = await repository.coverFallbackURL(siteName: result.site, vodId: result.vodId) {
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

        searchGeneration += 1
        let gen = searchGeneration
        searchTask = Task {
            let result = await repository.search(keyword: trimmed)
            // 仅当仍是当前 generation 且未被取消时才提交结果；
            // 否则丢弃，避免旧请求污染新请求的 UI 状态。
            guard gen == searchGeneration, !Task.isCancelled else { return }
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

        fetchGeneration += 1
        let gen = fetchGeneration
        fetchTask = Task {
            let result = await repository.fetch(siteName: result.site, vodId: result.vodId)
            guard gen == fetchGeneration, !Task.isCancelled else { return }
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

        playbackGeneration += 1
        let gen = playbackGeneration
        playbackTask = Task {
            let result = await repository.resolvePlaybackUrl(url: episode.url)
            guard gen == playbackGeneration, !Task.isCancelled else { return }
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
