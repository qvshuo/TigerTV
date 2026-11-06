import XCTest
@testable import TigerTVKit

final class HTTPConfigurationTests: XCTestCase {
    func testPercentEncodedURLPassesThroughASCII() {
        let url = HTTPConfiguration.percentEncodedURL(from: "https://pic.example.com/cover/73480.jpg?a=1")
        XCTAssertEqual(url?.absoluteString, "https://pic.example.com/cover/73480.jpg?a=1")
    }

    func testPercentEncodedURLEncodesNonASCIIPath() {
        let url = HTTPConfiguration.percentEncodedURL(from: "https://pic.example.com/封面/逐玉.jpg")
        XCTAssertNotNil(url)
        XCTAssertEqual(url?.host, "pic.example.com")
        XCTAssertTrue(url!.path.contains("逐玉"))
    }

    func testUnicodePathPreservesSignedQueryBytes() {
        let raw = "https://example.test/封面.jpg?token=a%2Bb%2Fc&literal=a+b&escaped=%252F"
        XCTAssertEqual(HTTPConfiguration.percentEncodedURL(from: raw)?.absoluteString,
            "https://example.test/%E5%B0%81%E9%9D%A2.jpg?token=a%2Bb%2Fc&literal=a+b&escaped=%252F")
    }

    func testMixedEscapedPathAndUnicodeQueryDoNotDoubleEncode() {
        let raw = "https://example.test/%E5%B0%81%E9%9D%A2/图.jpg?query=逐玉&literal=a+b"
        XCTAssertEqual(HTTPConfiguration.percentEncodedURL(from: raw)?.absoluteString,
            "https://example.test/%E5%B0%81%E9%9D%A2/%E5%9B%BE.jpg?query=%E9%80%90%E7%8E%89&literal=a+b")
    }

    func testPercentEncodedURLTrimsWhitespaceAndRejectsEmpty() {
        XCTAssertEqual(
            HTTPConfiguration.percentEncodedURL(from: "  https://pic.example.com/a.jpg\n")?.absoluteString,
            "https://pic.example.com/a.jpg"
        )
        XCTAssertNil(HTTPConfiguration.percentEncodedURL(from: "   "))
        XCTAssertNil(HTTPConfiguration.percentEncodedURL(from: ""))
    }
}
