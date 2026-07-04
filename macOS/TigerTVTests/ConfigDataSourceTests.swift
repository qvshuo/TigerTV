import XCTest
@testable import TigerTVKit

final class ConfigDataSourceTests: XCTestCase {
    private var cacheDir: URL!
    private var httpClient: HTTPClient!

    override func setUp() {
        super.setUp()
        cacheDir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        httpClient = HTTPClient(session: URLSession(configuration: config))
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: cacheDir)
        super.tearDown()
    }

    private func source() -> ConfigDataSource {
        ConfigDataSource(cacheDirectory: cacheDir, httpClient: httpClient)
    }

    private func writeCache(_ json: String, timestamp: Int) throws {
        try FileManager.default.createDirectory(at: cacheDir, withIntermediateDirectories: true)
        try json.data(using: .utf8)!.write(to: cacheDir.appendingPathComponent("tigertv-config-cache.json"))
        try String(timestamp).write(to: cacheDir.appendingPathComponent("tigertv-config-cache.timestamp"), atomically: true, encoding: .utf8)
    }

    func testUsesFreshCacheWithoutNetwork() async throws {
        let json = """
        {
          "api_site": {
            "a": {"name": "🎬-爱奇艺-", "api": "https://a.com"}
          }
        }
        """
        try writeCache(json, timestamp: Int(Date().timeIntervalSince1970))

        var networkCalled = false
        MockURLProtocol.requestHandler = { _ in
            networkCalled = true
            throw NSError(domain: "test", code: 1)
        }

        let result = await source().loadConfig()
        XCTAssertFalse(networkCalled)
        guard case .success(let config) = result else {
            XCTFail("Expected success")
            return
        }
        XCTAssertEqual(config.apiSite.count, 1)
    }

    func testFetchesRemoteWhenCacheMissing() async {
        let json = """
        {
          "api_site": {
            "a": {"name": "🎬-爱奇艺-", "api": "https://a.com"},
            "b": {"name": "🔞隐藏资源", "api": "https://b.com", "_comment": "disabled"}
          }
        }
        """.data(using: .utf8)!

        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, json)
        }

        let result = await source().loadConfig()
        guard case .success(let config) = result else {
            XCTFail("Expected success")
            return
        }
        XCTAssertEqual(config.apiSite.count, 1)
        XCTAssertTrue(config.apiSite.values.first?.name.contains("🎬") ?? false)
    }

    func testFallsBackToExpiredCache() async throws {
        let json = """
        {
          "api_site": {
            "a": {"name": "🎬-爱奇艺-", "api": "https://a.com"}
          }
        }
        """
        try writeCache(json, timestamp: Int(Date().timeIntervalSince1970) - 100_000)

        MockURLProtocol.requestHandler = { _ in
            throw NSError(domain: "test", code: 1)
        }

        let result = await source().loadConfig()
        guard case .success(let config) = result else {
            XCTFail("Expected fallback success")
            return
        }
        XCTAssertEqual(config.apiSite.count, 1)
    }
}
