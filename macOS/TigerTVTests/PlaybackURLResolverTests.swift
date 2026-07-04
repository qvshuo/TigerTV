import XCTest
@testable import TigerTVKit

final class PlaybackURLResolverTests: XCTestCase {
    private var resolver: PlaybackURLResolver!

    override func setUp() {
        super.setUp()
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        resolver = PlaybackURLResolver(httpClient: HTTPClient(session: URLSession(configuration: config)))
    }

    private func serveHTML(_ html: String) {
        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, html.data(using: .utf8)!)
        }
    }

    func testDirectM3U8ReturnsItself() async throws {
        let url = "https://cdn.example.com/playlist/index.m3u8?sign=abc"
        let result = try await resolver.resolve(url)
        XCTAssertEqual(result, url)
    }

    func testAbsoluteM3U8InHTMLIsExtracted() async throws {
        serveHTML(#"""
        <script>
          var src = "https://cdn.example.com/playlist/index.m3u8?sign=abc123";
        </script>
        """#)
        let result = try await resolver.resolve("https://example.com/player.html")
        XCTAssertEqual(result, "https://cdn.example.com/playlist/index.m3u8?sign=abc123")
    }

    func testRelativeM3U8InHTMLIsResolved() async throws {
        serveHTML(#"""
        <script>
          var src = "/hls/video.m3u8?token=xyz";
        </script>
        """#)
        let result = try await resolver.resolve("https://example.com/player.html")
        XCTAssertEqual(result, "https://example.com/hls/video.m3u8?token=xyz")
    }

    func testEscapedSlashesAreNormalized() async throws {
        serveHTML(#"""
        <script>
          var src = "https:\/\/cdn.example.com\/playlist\/escaped.m3u8?sign=escaped123";
        </script>
        """#)
        let result = try await resolver.resolve("https://example.com/player.html")
        XCTAssertEqual(result, "https://cdn.example.com/playlist/escaped.m3u8?sign=escaped123")
    }

    func testEncodedCharactersAreDecoded() async throws {
        serveHTML(#"""
        <script>
          var src = "https://cdn.example.com/playlist/index.m3u8?sign=a%2Fb%2Bc%3D";
        </script>
        """#)
        let result = try await resolver.resolve("https://example.com/player.html")
        XCTAssertEqual(result, "https://cdn.example.com/playlist/index.m3u8?sign=a/b+c=")
    }

    func testDirectMp4ReturnsItself() async throws {
        let url = "https://cdn.example.com/video.mp4"
        let result = try await resolver.resolve(url)
        XCTAssertEqual(result, url)
    }

    func testRawM3u8ContentReturnedFromNonM3u8Url() async throws {
        serveHTML("#EXTM3U\n#EXTINF:10,\nhttp://cdn.test/seg.ts\n")
        let requestUrl = "https://example.com/play.php?id=1"
        let result = try await resolver.resolve(requestUrl)
        XCTAssertEqual(result, requestUrl)
    }
}
