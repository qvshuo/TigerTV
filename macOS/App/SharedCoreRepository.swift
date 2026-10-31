import Foundation
import TigerTVCore

protocol VideoRepository: Sendable {
    func loadConfig() async -> LoadResult<SourceConfig>
    func search(keyword: String, onUpdate: @escaping @Sendable (SearchResponse) async -> Void) async -> LoadResult<SearchResponse>
    func fetch(siteName: String, vodId: Int) async -> LoadResult<FetchResponse>
    func resolvePlaybackUrl(url: String) async -> LoadResult<String>
    func coverFallbackURL(siteName: String, vodId: Int) async -> URL?
    func recordPlayback(workId: String, site: String, line: String, episode: String) async
    func loadHotSearchCategories() async -> [HotSearchCategory]
    func warmHotSearches(category: String) async
    func loadHotSearches(category: String) async -> [String]
    func loadHistory() async -> [String]
    func saveHistory(_ items: [String]) async
}

/// Owns one Kotlin/Native service. No subprocess or JVM is involved.
actor SharedCoreRepository: VideoRepository {
    private let facade: NativeFacade

    init(directory: URL, dataDirectory: URL) {
        facade = NativeFacade(cacheDirectory: directory.path, dataDirectory: dataDirectory.path)
    }

    isolated deinit { facade.close() }

    private struct Envelope<T: Decodable>: Decodable {
        let data: T?
        let error: String?
    }

    private func request<T: Decodable & Sendable>(_ operation: String, _ arguments: [String: Any] = [:], as: T.Type, onSearchUpdate: (@Sendable (SearchResponse) async -> Void)? = nil) async -> LoadResult<T> {
        do {
            let data = try JSONSerialization.data(withJSONObject: arguments.merging(["version": 1, "operation": operation]) { _, new in new })
            let id = facade.begin(request: String(decoding: data, as: UTF8.self))
            defer { facade.cancel(id: id) }
            while true {
                try Task.checkCancellation()
                if let update = facade.takeSearchUpdate(id: id), let onSearchUpdate {
                    await onSearchUpdate(try JSONDecoder().decode(SearchResponse.self, from: Data(update.utf8)))
                }
                if let response = facade.takeResult(id: id) {
                    let result = try JSONDecoder().decode(Envelope<T>.self, from: Data(response.utf8))
                    if let error = result.error {
                        NSLog("Core %@ failed: %@", operation, error)
                        return .failure(.request(error))
                    }
                    guard let value = result.data else { return .failure(.invalidResponse) }
                    return .success(value)
                }
                try await Task.sleep(for: .milliseconds(20))
            }
        } catch {
            if !(error is CancellationError) { NSLog("Core %@ failed: %@", operation, error.localizedDescription) }
            return .failure(.request(error.localizedDescription))
        }
    }

    func recordPlayback(workId: String, site: String, line: String, episode: String) async {
        _ = await request("recordPlayback", ["work_id": workId, "site": site, "line": line, "episode": episode], as: Bool.self)
    }
    func warmHotSearches(category: String) async {
        _ = await request("warmHotSearches", ["category": category], as: Bool.self)
    }
    func loadHotSearchCategories() async -> [HotSearchCategory] {
        if case .success(let categories) = await request("hotSearchCategories", as: [HotSearchCategory].self) { return categories }
        return []
    }
    func loadHotSearches(category: String) async -> [String] {
        if case .success(let titles) = await request("hotSearches", ["category": category], as: [String].self) { return titles }
        return []
    }
    func loadHistory() async -> [String] {
        if case .success(let history) = await request("history", as: [String].self) { return history }
        return []
    }
    func saveHistory(_ items: [String]) async {
        _ = await request("saveHistory", ["items": items], as: Bool.self)
    }
    func loadConfig() async -> LoadResult<SourceConfig> { await request("config", as: SourceConfig.self) }
    func search(keyword: String, onUpdate: @escaping @Sendable (SearchResponse) async -> Void) async -> LoadResult<SearchResponse> { await request("search", ["query": keyword], as: SearchResponse.self, onSearchUpdate: onUpdate) }
    func fetch(siteName: String, vodId: Int) async -> LoadResult<FetchResponse> { await request("fetch", ["site": siteName, "video_id": vodId], as: FetchResponse.self) }
    func resolvePlaybackUrl(url: String) async -> LoadResult<String> { await request("resolve", ["url": url], as: String.self) }
    func coverFallbackURL(siteName: String, vodId: Int) async -> URL? {
        if case .success(let url) = await request("cover", ["site": siteName, "video_id": vodId], as: String.self) {
            return HTTPClient.percentEncodedURL(from: url)
        }
        return nil
    }
}
