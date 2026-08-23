import Foundation

actor TigerTVRepository {
    private let configDataSource: ConfigDataSource
    private let apiClient: MacCMSApiClient
    private let playbackResolver: PlaybackURLResolver
    private let searchCacheTtl: TimeInterval

    private var cachedConfig: SourceConfig?
    private var searchCache: [String: CacheEntry<SearchResponse>] = [:]
    private var coverCache: [String: URL] = [:]
    private let coverSemaphore = AsyncSemaphore(value: 4)
    /// 兜底 URL 的磁盘持久层（7 天 TTL）；nil 表示仅进程内缓存。
    private let coverURLStore: CoverFallbackURLStore?

    init(
        configDataSource: ConfigDataSource,
        apiClient: MacCMSApiClient,
        playbackResolver: PlaybackURLResolver,
        searchCacheTtl: TimeInterval = 10 * 60,
        coverURLStore: CoverFallbackURLStore? = nil
    ) {
        self.configDataSource = configDataSource
        self.apiClient = apiClient
        self.playbackResolver = playbackResolver
        self.searchCacheTtl = searchCacheTtl
        self.coverURLStore = coverURLStore
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
            return .failure(TigerTVError.network(error.localizedDescription))
        }
    }

    func resolvePlaybackUrl(url: String) async -> LoadResult<String> {
        do {
            let resolved = try await playbackResolver.resolve(url)
            return .success(resolved)
        } catch let error as TigerTVError {
            return .failure(error)
        } catch {
            return .failure(.network(error.localizedDescription))
        }
    }

    /// 搜索封面懒加载兜底：多数站点 `ac=list` 响应不含 `vod_pic`（仅 detail 返回），
    /// 空封面卡片可见时按需取 detail 补齐。命中内存缓存不发请求；失败不缓存，
    /// 卡片再次进入视口时自然重试。并发受信号量限制，避免滚动时打爆站点。
    func coverFallbackURL(siteName: String, vodId: Int) async -> URL? {
        let key = "\(siteName)-\(vodId)"
        if let cached = coverCache[key] { return cached }

        // 磁盘持久层命中：回填内存缓存即可，无需发 detail 请求（7 天 TTL 内稳定有效）。
        if let persisted = coverURLStore?.url(forKey: key),
           let url = HTTPClient.percentEncodedURL(from: persisted) {
            coverCache[key] = url
            return url
        }

        let config: SourceConfig
        switch await loadConfig() {
        case .success(let c): config = c
        case .failure: return nil
        }
        guard let site = config.apiSite.values.first(where: { $0.name == siteName }) else {
            return nil
        }

        await coverSemaphore.wait()
        // 拿到许可后二次检查：排队期间同 key 可能已被其他任务填入缓存。
        if let cached = coverCache[key] {
            await coverSemaphore.signal()
            return cached
        }
        do {
            let detail = try await apiClient.fetchDetail(api: site.api, vodId: vodId)
            await coverSemaphore.signal()
            guard detail.code == 1,
                  let pic = detail.list.first?.vodPic?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !pic.isEmpty else {
                return nil
            }
            let url = HTTPClient.percentEncodedURL(from: pic)
            if let url {
                coverCache[key] = url
                coverURLStore?.store(pic, forKey: key)
            }
            return url
        } catch {
            await coverSemaphore.signal()
            return nil
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
