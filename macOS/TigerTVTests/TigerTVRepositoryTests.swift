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
}
