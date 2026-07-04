import Foundation

actor TigerTVRepository {
    private let configDataSource: ConfigDataSource
    private let apiClient: MacCMSApiClient
    private let playbackResolver: PlaybackURLResolver
    private let searchCacheTtl: TimeInterval

    private var cachedConfig: SourceConfig?
    private var searchCache: [String: CacheEntry<SearchResponse>] = [:]

    init(
        configDataSource: ConfigDataSource,
        apiClient: MacCMSApiClient,
        playbackResolver: PlaybackURLResolver,
        searchCacheTtl: TimeInterval = 10 * 60
    ) {
        self.configDataSource = configDataSource
        self.apiClient = apiClient
        self.playbackResolver = playbackResolver
        self.searchCacheTtl = searchCacheTtl
    }

    func loadConfig() async -> LoadResult<SourceConfig> {
        let result = await configDataSource.loadConfig()
        if case .success(let config) = result {
            cachedConfig = config
        }
        return result
    }

    func search(keyword: String) async -> LoadResult<SearchResponse> {
        let trimmed = keyword.trimmingCharacters(in: .whitespacesAndNewlines)

        if let cached = searchCache[trimmed], cached.isFresh(ttl: searchCacheTtl) {
            return .success(cached.data)
        }

        let config: SourceConfig
        switch await loadConfig() {
        case .success(let c): config = c
        case .failure(let e): return .failure(e)
        }

        let sites = Array(config.apiSite.values)
        let semaphore = AsyncSemaphore(value: 20)
        let results = await withTaskGroup(of: [SearchResult].self) { group in
            for site in sites {
                group.addTask {
                    await self.searchOne(site: site, keyword: trimmed, semaphore: semaphore)
                }
            }
            var collected: [SearchResult] = []
            for await partial in group {
                collected.append(contentsOf: partial)
            }
            return collected
        }

        let response = SearchResponse(keyword: trimmed, results: results)
        searchCache[trimmed] = CacheEntry(data: response, createdAt: Date())
        return .success(response)
    }

    func fetch(siteName: String, vodId: Int) async -> LoadResult<FetchResponse> {
        let config: SourceConfig
        switch await loadConfig() {
        case .success(let c): config = c
        case .failure(let e): return .failure(e)
        }

        guard let site = config.apiSite.values.first(where: { $0.name == siteName }) else {
            let available = config.apiSite.values.map(\.name).sorted()
            return .failure(TigerTVError.unknownSite(siteName, available))
        }

        do {
            let detail = try await apiClient.fetchDetail(api: site.api, vodId: vodId)
            guard detail.code == 1 else {
                return .failure(TigerTVError.fetchFailed(detail.msg ?? "code \(detail.code)"))
            }
            guard let item = detail.list.first else {
                return .failure(TigerTVError.fetchFailed("空详情响应"))
            }
            let play = parseEpisodeLinks(raw: item.vodPlayUrl ?? "")
            let down = parseEpisodeLinks(raw: item.vodDownUrl ?? "")
            let response = FetchResponse(
                vodId: item.vodId,
                site: siteName,
                vodPlayUrl: play,
                vodDownUrl: down
            )
            return .success(response)
        } catch {
            return .failure(error)
        }
    }

    func resolvePlaybackUrl(url: String) async -> LoadResult<String> {
        do {
            let resolved = try await playbackResolver.resolve(url)
            return .success(resolved)
        } catch {
            return .failure(error)
        }
    }

    private func searchOne(site: SourceSite, keyword: String, semaphore: AsyncSemaphore) async -> [SearchResult] {
        await semaphore.wait()
        let results = await apiClient.search(siteName: site.name, api: site.api, keyword: keyword)
        await semaphore.signal()
        return results
    }

    private func parseEpisodeLinks(raw: String) -> [EpisodeLink] {
        guard !raw.isEmpty else { return [] }
        var links: [EpisodeLink] = []
        let groups = raw.components(separatedBy: "$$$")
        for group in groups {
            let items = group.components(separatedBy: "#")
            for item in items {
                let parts = item.split(separator: "$", maxSplits: 1, omittingEmptySubsequences: false)
                guard parts.count == 2 else { continue }
                let name = String(parts[0]).trimmingCharacters(in: .whitespaces)
                let url = String(parts[1]).trimmingCharacters(in: .whitespaces)
                if !name.isEmpty && !url.isEmpty {
                    links.append(EpisodeLink(name: name, url: url))
                }
            }
        }
        return links
    }
}

private struct CacheEntry<T> {
    let data: T
    let createdAt: Date

    func isFresh(ttl: TimeInterval) -> Bool {
        Date().timeIntervalSince(createdAt) < ttl
    }
}

private actor AsyncSemaphore {
    private var value: Int
    private var waiters: [CheckedContinuation<Void, Never>] = []

    init(value: Int) {
        self.value = value
    }

    func wait() async {
        if value > 0 {
            value -= 1
            return
        }
        await withCheckedContinuation { continuation in
            waiters.append(continuation)
        }
    }

    func signal() {
        if let continuation = waiters.first {
            waiters.removeFirst()
            continuation.resume()
        } else {
            value += 1
        }
    }
}
