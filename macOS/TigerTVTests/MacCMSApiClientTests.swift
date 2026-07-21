import XCTest
@testable import TigerTVKit

final class MacCMSApiClientTests: XCTestCase {
    private var httpClient: HTTPClient!

    override func setUp() {
        super.setUp()
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        httpClient = HTTPClient(session: URLSession(configuration: config))
    }

    override func tearDown() {
        MockURLProtocol.requestHandler = nil
        super.tearDown()
    }

    private func makeClient() -> MacCMSApiClient {
        MacCMSApiClient(httpClient: httpClient)
    }

    private func enqueueJSON(_ body: String) {
        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: 200,
                httpVersion: nil,
                headerFields: ["Content-Type": "application/json"]
            )!
            return (response, body.data(using: .utf8)!)
        }
    }

    func testFloatCodeIsCoercedToInt() async {
        enqueueJSON(#"{"code":1.0,"msg":"ok","list":[{"vod_id":1,"vod_name":"x","vod_time":"","vod_remarks":""}]}"#)
        let results = await makeClient().search(
            siteName: "🎬-测试站-",
            api: "https://example.com/api.php/provide/vod",
            keyword: "666"
        )
        XCTAssertEqual(results.count, 1)
        XCTAssertEqual(results[0].vodId, 1)
    }

    func testStringVodIdIsCoercedToInt() async {
        // 暴风资源等站点把 vod_id 返回成字符串，与 CLI int(...) 一致。
        enqueueJSON(#"{"code":1,"msg":"ok","list":[{"vod_id":"102405","vod_name":"x","vod_time":"","vod_remarks":""}]}"#)
        let results = await makeClient().search(
            siteName: "🎬-测试站-",
            api: "https://example.com/api.php/provide/vod",
            keyword: "666"
        )
        XCTAssertEqual(results.count, 1)
        XCTAssertEqual(results[0].vodId, 102405)
    }

    func testStringCodeIsCoercedToInt() async {
        enqueueJSON(#"{"code":"1","msg":"ok","list":[{"vod_id":9,"vod_name":"y","vod_time":"","vod_remarks":""}]}"#)
        let results = await makeClient().search(
            siteName: "🎬-测试站-",
            api: "https://example.com/api.php/provide/vod",
            keyword: "666"
        )
        XCTAssertEqual(results.count, 1)
        XCTAssertEqual(results[0].vodId, 9)
    }

    func testBaofengStyleStringVodIdKeepsResults() async {
        let body = """
        {"code":1,"msg":"数据列表","list":[
          {"vod_id":"136872","vod_name":"逐玉","vod_time":"2026-03-21 20:41:19","vod_remarks":"更新至第40集"}
        ]}
        """
        enqueueJSON(body)
        let results = await makeClient().search(
            siteName: "🎬暴风资源",
            api: "https://example.com/api.php/provide/vod",
            keyword: "逐玉"
        )
        XCTAssertEqual(results.count, 1)
        XCTAssertEqual(results[0].vodId, 136872)
        XCTAssertEqual(results[0].vodName, "逐玉")
        XCTAssertEqual(results[0].site, "🎬暴风资源")
    }

    func testFetchDetailAcceptsStringVodId() async throws {
        enqueueJSON(#"{"code":1,"msg":"ok","list":[{"vod_id":"73480","vod_name":"逐玉","vod_play_url":"第01集$https://a.com/1.m3u8","vod_down_url":""}]}"#)
        let detail = try await makeClient().fetchDetail(
            api: "https://example.com/api.php/provide/vod",
            vodId: 73480
        )
        XCTAssertEqual(detail.code, 1)
        XCTAssertEqual(detail.list.first?.vodId, 73480)
        XCTAssertEqual(detail.list.first?.vodPlayUrl, "第01集$https://a.com/1.m3u8")
    }

    func testMalformedListElementsAreSwallowedInSearch() async {
        enqueueJSON(#"{"code":1,"msg":"ok","list":[null]}"#)
        let results = await makeClient().search(
            siteName: "🎬-测试站-",
            api: "https://example.com/api.php/provide/vod",
            keyword: "666"
        )
        XCTAssertEqual(results.count, 0)
    }

    func testMalformedListElementsArePropagatedInFetch() async {
        enqueueJSON(#"{"code":1,"msg":"ok","list":[null]}"#)
        do {
            _ = try await makeClient().fetchDetail(
                api: "https://example.com/api.php/provide/vod",
                vodId: 1
            )
            XCTFail("Expected decode error")
        } catch {
            // Expected: typed decode fails on non-object list elements.
        }
    }
}
