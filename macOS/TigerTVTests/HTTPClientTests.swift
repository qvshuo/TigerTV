import XCTest
@testable import TigerTVKit

final class HTTPClientTests: XCTestCase {
    func testPercentEncodedURLPassesThroughASCII() {
        let url = HTTPClient.percentEncodedURL(from: "https://pic.example.com/cover/73480.jpg?a=1")
        XCTAssertEqual(url?.absoluteString, "https://pic.example.com/cover/73480.jpg?a=1")
    }

    func testPercentEncodedURLEncodesNonASCIIPath() {
        // 上游封面路径含中文时 URL(string:) 返回 nil 会静默丢图，必须编码成功。
        let url = HTTPClient.percentEncodedURL(from: "https://pic.example.com/封面/逐玉.jpg")
        XCTAssertNotNil(url)
        XCTAssertEqual(url?.host, "pic.example.com")
        XCTAssertTrue(url!.path.contains("逐玉"))
    }

    func testPercentEncodedURLTrimsWhitespaceAndRejectsEmpty() {
        XCTAssertEqual(
            HTTPClient.percentEncodedURL(from: "  https://pic.example.com/a.jpg\n")?.absoluteString,
            "https://pic.example.com/a.jpg"
        )
        XCTAssertNil(HTTPClient.percentEncodedURL(from: "   "))
        XCTAssertNil(HTTPClient.percentEncodedURL(from: ""))
    }
}
