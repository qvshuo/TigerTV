import XCTest
@testable import TigerTVKit

final class WorkCoverTests: XCTestCase {
    @MainActor
    func testFailedCoverFallsThroughToNextSource() async {
        let broken = URL(string: "https://a.test/broken.jpg")!
        let good = URL(string: "https://b.test/poster.jpg")!
        var attempts: [URL] = []
        let first = await loadFirstCover(candidates: [broken, good, good]) { url in
            attempts.append(url)
            return url == good ? "image" : nil
        }
        XCTAssertEqual(attempts, [broken, good])
        XCTAssertEqual(first?.url, good)

    }

    @MainActor
    func testAllFailuresReturnPlaceholder() async {
        let candidate = URL(string: "https://a.test/poster.jpg")!
        var attempts: [URL] = []
        let result: (url: URL, value: String)? = await loadFirstCover(candidates: [candidate, candidate]) { url in
            attempts.append(url)
            return nil
        }
        XCTAssertNil(result)
        XCTAssertEqual(attempts, [candidate])
    }

    @MainActor
    func testCancelledCardDiscardsLateImageAndDoesNotLoadNextCandidate() async {
        let urls = [URL(string: "https://a.test/1.jpg")!, URL(string: "https://a.test/2.jpg")!]
        var attempts: [URL] = []
        let operation = Task {
            await loadFirstCover(candidates: urls) { url in
                attempts.append(url)
                withUnsafeCurrentTask { $0?.cancel() }
                return "late image"
            }
        }
        let result = await operation.value
        XCTAssertNil(result)
        XCTAssertEqual(attempts, [urls[0]])
    }
}
