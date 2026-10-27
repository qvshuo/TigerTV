import Foundation
import TigerTVCore

protocol VideoRepository: Sendable {
    func loadConfig() async -> LoadResult<SourceConfig>
    func search(keyword: String) async -> LoadResult<SearchResponse>
    func fetch(siteName: String, vodId: Int) async -> LoadResult<FetchResponse>
    func resolvePlaybackUrl(url: String) async -> LoadResult<String>
    func coverFallbackURL(siteName: String, vodId: Int) async -> URL?
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

    private func request<T: Decodable & Sendable>(_ operation: String, _ arguments: [String: Any] = [:], as: T.Type) async -> LoadResult<T> {
        do {
            let data = try JSONSerialization.data(withJSONObject: arguments.merging(["version": 1, "operation": operation]) { _, new in new })
            let id = facade.begin(request: String(decoding: data, as: UTF8.self))
            defer { facade.cancel(id: id) }
            while true {
                try Task.checkCancellation()
                if let response = facade.takeResult(id: id) {
                    let result = try JSONDecoder().decode(Envelope<T>.self, from: Data(response.utf8))
                    if let error = result.error { return .failure(.network(error)) }
                    guard let value = result.data else { return .failure(.invalidResponse) }
                    return .success(value)
                }
                try await Task.sleep(for: .milliseconds(20))
            }
        } catch {
            return .failure(.network(error.localizedDescription))
        }
    }

    func loadHistory() async -> [String] {
        if case .success(let history) = await request("history", as: [String].self) { return history }
        return []
    }
    func saveHistory(_ items: [String]) async {
        _ = await request("saveHistory", ["items": items], as: Bool.self)
    }
    func loadConfig() async -> LoadResult<SourceConfig> { await request("config", as: SourceConfig.self) }
    func search(keyword: String) async -> LoadResult<SearchResponse> { await request("search", ["keyword": keyword], as: SearchResponse.self) }
    func fetch(siteName: String, vodId: Int) async -> LoadResult<FetchResponse> { await request("fetch", ["site": siteName, "vod_id": vodId], as: FetchResponse.self) }
    func resolvePlaybackUrl(url: String) async -> LoadResult<String> { await request("resolve", ["url": url], as: String.self) }
    func coverFallbackURL(siteName: String, vodId: Int) async -> URL? {
        if case .success(let url) = await request("cover", ["site": siteName, "vod_id": vodId], as: String.self) {
            return HTTPClient.percentEncodedURL(from: url)
        }
        return nil
    }
}
