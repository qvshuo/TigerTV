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
            .first!
            .appendingPathComponent("TigerTV"),
        httpClient: HTTPClient = HTTPClient()
    ) {
        self.cacheDirectory = cacheDirectory
        self.httpClient = httpClient
        self.decoder = JSONDecoder()
        self.encoder = JSONEncoder()
    }

    func loadConfig() async -> LoadResult<SourceConfig> {
        lastFetchError = nil

        let cached = try? readCache()
        if let cached, isCacheFresh() {
            if let filtered = filterConfig(cached) {
                return .success(filtered)
            }
        }

        var remote = await fetchRemote(urlString: Self.configCdnURL, timeout: 10)
        if remote == nil {
            remote = await fetchRemote(urlString: Self.configURL, timeout: 5)
        }
        if let remote {
            try? writeCache(remote)
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
        try data.write(to: cacheFile())
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
        let filtered = config.apiSite.filter { _, site in
            site.name.contains(Self.movieEmoji) && site.comment == nil
        }
        return filtered.isEmpty ? nil : SourceConfig(cacheTime: config.cacheTime, apiSite: filtered)
    }
}
