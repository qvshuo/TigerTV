import Foundation

actor ConfigDataSource {
    private static let configURL = "https://raw.githubusercontent.com/qvshuo/TigerTV/refs/heads/main/skills/references/LunaTV-config.json"
    private static let configCdnURL = "https://cdn.jsdelivr.net/gh/qvshuo/TigerTV@main/skills/references/LunaTV-config.json"
    private static let cacheTtl: TimeInterval = 86400
    private static let movieEmoji = "🎬"

    private let cacheDirectory: URL
    private let httpClient: HTTPClient
    private let decoder: JSONDecoder
    private let encoder: JSONEncoder
    private var lastFetchError: String?

    init(
        cacheDirectory: URL = FileManager.default
            .urls(for: .cachesDirectory, in: .userDomainMask)
            .first ?? FileManager.default.temporaryDirectory.appendingPathComponent("TigerTV"),
        httpClient: HTTPClient = HTTPClient()
    ) {
        self.cacheDirectory = cacheDirectory
        self.httpClient = httpClient
        self.decoder = JSONDecoder()
        self.encoder = JSONEncoder()
    }

    func loadConfig() async -> LoadResult<SourceConfig> {
        lastFetchError = nil

        // 读取时再过滤：缓存保留原始完整配置（含 _comment 站点），与 CLI 行为一致。
        // 这样过滤规则变更后无需等缓存过期即可生效，且缓存可回放历史。
        if let cached = try? readCache(), isCacheFresh() {
            if let filtered = filterConfig(cached) {
                return .success(filtered)
            }
        }

        var remote = await fetchRemote(urlString: Self.configCdnURL, timeout: 10)
        if remote == nil {
            remote = await fetchRemote(urlString: Self.configURL, timeout: 5)
        }
        if let remote {
            try? writeCache(remote)  // 缓存原始（未过滤）配置
            if let filtered = filterConfig(remote) {
                return .success(filtered)
            }
        }

        if let cached = try? readCache(), let filtered = filterConfig(cached) {
            return .success(filtered)
        }

        let details = lastFetchError ?? "CDN、RAW 配置均不可用且无缓存"
        return .failure(TigerTVError.configUnavailable(details))
    }

    private func cacheFile() -> URL {
        cacheDirectory.appendingPathComponent("tigertv-config-cache.json")
    }

    private func timestampFile() -> URL {
        cacheDirectory.appendingPathComponent("tigertv-config-cache.timestamp")
    }

    private func ensureCacheDirectory() throws {
        try FileManager.default.createDirectory(
            at: cacheDirectory,
            withIntermediateDirectories: true
        )
    }

    private func readCache() throws -> SourceConfig? {
        let file = cacheFile()
        guard FileManager.default.fileExists(atPath: file.path) else { return nil }
        let data = try Data(contentsOf: file)
        return try decoder.decode(SourceConfig.self, from: data)
    }

    private func writeCache(_ config: SourceConfig) throws {
        try ensureCacheDirectory()
        let data = try encoder.encode(config)
        // 原子写入：避免崩溃留下半截 JSON 使 isCacheFresh 误判。
        try data.write(to: cacheFile(), options: .atomic)
        let timestamp = String(Int(Date().timeIntervalSince1970))
        try timestamp.write(to: timestampFile(), atomically: true, encoding: .utf8)
    }

    private func isCacheFresh() -> Bool {
        let file = timestampFile()
        guard FileManager.default.fileExists(atPath: file.path) else { return false }
        guard let text = try? String(contentsOf: file, encoding: .utf8),
              let timestamp = TimeInterval(text) else { return false }
        return Date().timeIntervalSince1970 - timestamp < Self.cacheTtl
    }

    private func fetchRemote(urlString: String, timeout: TimeInterval) async -> SourceConfig? {
        guard let url = URL(string: urlString) else {
            lastFetchError = "\(urlString) 不是有效 URL"
            return nil
        }
        do {
            let text = try await httpClient.fetchString(url: url, timeout: timeout)
            return try decoder.decode(SourceConfig.self, from: Data(text.utf8))
        } catch {
            lastFetchError = "\(urlString) 获取失败：\(error.localizedDescription)"
            return nil
        }
    }

    private func filterConfig(_ config: SourceConfig) -> SourceConfig? {
        // 过滤：含 🎬、无 _comment、api 非空（与 CLI `if api and "🎬" in name and "_comment" not in value` 一致）。
        // 去重：按 api URL 去重（与 CLI `api_name_map[api] = name` 一致），避免同 api 站点发重复请求。
        var seenApi = Set<String>()
        var filtered: [String: SourceSite] = [:]
        for (_, site) in config.apiSite {
            guard !site.api.isEmpty,
                  site.name.contains(Self.movieEmoji),
                  site.comment == nil,
                  !seenApi.contains(site.api) else { continue }
            seenApi.insert(site.api)
            filtered[site.api] = site
        }
        return filtered.isEmpty ? nil : SourceConfig(cacheTime: config.cacheTime, apiSite: filtered)
    }
}
