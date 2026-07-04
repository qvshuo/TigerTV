import XCTest
@testable import TigerTVKit

final class HTTPClientTests: XCTestCase {
    private var httpClient: HTTPClient!

    override func setUp() {
        super.setUp()
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        httpClient = HTTPClient(session: URLSession(configuration: config))
    }

    func testEnforcesMaxResponseSize() async {
        let bigData = Data(repeating: 0, count: HTTPClient.maxResponseSize + 1)
        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, bigData)
        }

        do {
            _ = try await httpClient.fetchData(url: URL(string: "https://example.com/big")!, timeout: 10)
            XCTFail("Expected error for oversized response")
        } catch {
            // expected
        }
    }

    func testSetsUserAgent() async throws {
        let expectation = expectation(description: "handler called")
        MockURLProtocol.requestHandler = { request in
            XCTAssertEqual(request.value(forHTTPHeaderField: "User-Agent"), HTTPClient.userAgent)
            expectation.fulfill()
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, Data())
        }

        _ = try await httpClient.fetchData(url: URL(string: "https://example.com/ua")!, timeout: 10)
        await fulfillment(of: [expectation])
    }
}
