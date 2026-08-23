import XCTest
@testable import TigerTVKit

final class TigerTVRepositoryTests: XCTestCase {
    private var cacheDir: URL!
    private var httpClient: HTTPClient!

    override func setUp() {
        super.setUp()
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        httpClient = HTTPClient(session: URLSession(configuration: config))
        cacheDir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: cacheDir)
        super.tearDown()
    }

    private func makeRepository() -> TigerTVRepository {
        TigerTVRepository(
            configDataSource: ConfigDataSource(cacheDirectory: cacheDir, httpClient: httpClient),
            apiClient: MacCMSApiClient(httpClient: httpClient),
            playbackResolver: PlaybackURLResolver(httpClient: httpClient)
        )
    }

    func testSearchReturnsMergedResultsFromMultipleSites() async {
        let config = """
        {
          "api_site": {
            "a": {"name": "🎬-爱奇艺-", "api": "https://a.com"},
            "b": {"name": "🎬金鹰点播", "api": "https://b.com"}
          }
        }
        """.data(using: .utf8)!

        MockURLProtocol.requestHandler = { request in
            let url = request.url!.absoluteString
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            if url.contains("LunaTV-config.json") {
                return (response, config)
            }
            if url.contains("a.com") {
                return (response, #"{"code":1,"list":[{"vod_id":1,"vod_name":"逐玉"}]}"#.data(using: .utf8)!)
            }
            if url.contains("b.com") {
                return (response, #"{"code":1,"list":[{"vod_id":2,"vod_name":"逐玉"}]}"#.data(using: .utf8)!)
            }
            XCTFail("Unexpected request: \(url)")
            return (response, Data())
        }

        let result = await makeRepository().search(keyword: "逐玉")
        guard case .success(let response) = result else {
            XCTFail("Expected success")
            return
        }
        XCTAssertEqual(response.results.count, 2)
    }

    func testFetchParsesEpisodeGroups() async {
        let config = """
        {
          "api_site": {
            "a": {"name": "🎬-爱奇艺-", "api": "https://a.com"}
          }
        }
        """.data(using: .utf8)!

        MockURLProtocol.requestHandler = { request in
            let url = request.url!.absoluteString
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            if url.contains("LunaTV-config.json") {
                return (response, config)
            }
            let json = #"{"code":1,"list":[{"vod_id":73480,"vod_play_url":"第01集$https://a.com/1.html#第02集$https://a.com/2.html$$$备用第01集$https://a.com/alt1.html"}]}"#
            return (response, json.data(using: .utf8)!)
        }

        let result = await makeRepository().fetch(siteName: "🎬-爱奇艺-", vodId: 73480)
        guard case .success(let response) = result else {
            if case .failure(let error) = result {
                XCTFail("Expected success, got \(error)")
            } else {
                XCTFail("Expected success")
            }
            return
        }
        XCTAssertEqual(response.vodPlayUrl.count, 3)
        XCTAssertEqual(response.vodPlayUrl[0].name, "第01集")
    }

    func testUnknownSiteReturnsError() async {
        let config = """
        {
          "api_site": {
            "a": {"name": "🎬-爱奇艺-", "api": "https://a.com"}
          }
        }
        """.data(using: .utf8)!

        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, config)
        }

        let result = await makeRepository().fetch(siteName: "不存在", vodId: 1)
        guard case .failure = result else {
            XCTFail("Expected failure")
            return
        }
    }

    func testCoverFallbackURLStorePersistsAcrossInstancesAndExpires() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }

        let store = CoverFallbackURLStore(directory: dir)
        store.store("https://pic.example.com/a.jpg", forKey: "site-1")

        // 新实例（模拟进程重启）应能读到。
        let reopened = CoverFallbackURLStore(directory: dir)
        XCTAssertEqual(reopened.url(forKey: "site-1"), "https://pic.example.com/a.jpg")

        // 过期条目返回 nil 并被清除。
        let expiredDir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: expiredDir) }
        let expiredStore = CoverFallbackURLStore(directory: expiredDir)
        expiredStore.store("https://pic.example.com/old.jpg", forKey: "site-2", fetchedAt: Date().addingTimeInterval(-8 * 86400))

        let reopenedExpired = CoverFallbackURLStore(directory: expiredDir)
        XCTAssertNil(reopenedExpired.url(forKey: "site-2"))
    }

    func testCoverFallbackFetchesDetailAndCaches() async {
        let config = """
        {
          "api_site": {
            "a": {"name": "🎬-爱奇艺-", "api": "https://a.com"}
          }
        }
        """.data(using: .utf8)!

        // 只 enqueue 一个 detail 响应：第二次调用必须命中缓存而非再发请求。
        var detailRequestCount = 0
        MockURLProtocol.requestHandler = { request in
            let url = request.url!.absoluteString
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            if url.contains("LunaTV-config.json") {
                return (response, config)
            }
            if url.contains("ac=detail") {
                detailRequestCount += 1
                let json = #"{"code":1,"list":[{"vod_id":73480,"vod_pic":"https://pic.example.com/封面/73480.jpg"}]}"#
                return (response, json.data(using: .utf8)!)
            }
            XCTFail("Unexpected request: \(url)")
            return (response, Data())
        }

        let repository = makeRepository()
        let first = await repository.coverFallbackURL(siteName: "🎬-爱奇艺-", vodId: 73480)
        XCTAssertEqual(first?.absoluteString, "https://pic.example.com/%E5%B0%81%E9%9D%A2/73480.jpg")

        let second = await repository.coverFallbackURL(siteName: "🎬-爱奇艺-", vodId: 73480)
        XCTAssertEqual(second?.absoluteString, first?.absoluteString)
        XCTAssertEqual(detailRequestCount, 1, "第二次调用应命中缓存，不再发请求")
    }

    func testCoverFallbackReturnsNilWithoutCachingOnEmptyPic() async {
        let config = """
        {
          "api_site": {
            "a": {"name": "🎬-爱奇艺-", "api": "https://a.com"}
          }
        }
        """.data(using: .utf8)!

        var detailRequestCount = 0
        MockURLProtocol.requestHandler = { request in
            let url = request.url!.absoluteString
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            if url.contains("LunaTV-config.json") {
                return (response, config)
            }
            if url.contains("ac=detail") {
                detailRequestCount += 1
                return (response, #"{"code":1,"list":[{"vod_id":1,"vod_pic":""}]}"#.data(using: .utf8)!)
            }
            XCTFail("Unexpected request: \(url)")
            return (response, Data())
        }

        let repository = makeRepository()
        let first = await repository.coverFallbackURL(siteName: "🎬-爱奇艺-", vodId: 1)
        XCTAssertNil(first)
        // 失败不缓存：再次调用会重试（发第二个请求）。
        _ = await repository.coverFallbackURL(siteName: "🎬-爱奇艺-", vodId: 1)
        XCTAssertEqual(detailRequestCount, 2)
    }
}
