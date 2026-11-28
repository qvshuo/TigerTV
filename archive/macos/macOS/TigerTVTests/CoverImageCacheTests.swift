import Foundation
import XCTest
@testable import TigerTVKit

private final class CoverURLProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let url = request.url!
        let oversized = url.path == "/large"
        let data = oversized
            ? Data(repeating: 0, count: CoverImageCache.maxResponseSize + 1)
            : Data(base64Encoded: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")!
        let headers = url.path == "/declared-large"
            ? ["Content-Length": String(CoverImageCache.maxResponseSize + 1)] : [:]
        let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: headers)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

final class CoverImageCacheTests: XCTestCase {
    func testDownloadAcceptsImageAndRejectsOversizedResponseWithoutLength() async throws {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [CoverURLProtocol.self]
        let session = URLSession(configuration: config)
        defer { session.invalidateAndCancel() }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let cache = CoverImageCache(directory: directory, session: session)
        let small = await cache.image(for: URL(string: "https://cover.test/small")!)
        XCTAssertNotNil(small)
        let large = await cache.image(for: URL(string: "https://cover.test/large")!)
        XCTAssertNil(large)
        let declaredLarge = await cache.image(for: URL(string: "https://cover.test/declared-large")!)
        XCTAssertNil(declaredLarge)
    }
}
