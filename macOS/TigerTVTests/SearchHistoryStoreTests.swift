import XCTest
@testable import TigerTVKit

final class SearchHistoryStoreTests: XCTestCase {
    private var defaults: UserDefaults!
    private var suiteName: String!
    private var store: SearchHistoryStore!

    override func setUp() {
        super.setUp()
        suiteName = UUID().uuidString
        defaults = UserDefaults(suiteName: suiteName)
        store = SearchHistoryStore(defaults: defaults, key: "history")
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        super.tearDown()
    }

    func testSaveAndLoad() async {
        await store.save(["a", "b"])
        let loaded = await store.load()
        XCTAssertEqual(loaded, ["a", "b"])
    }

    func testMaxItems() async {
        let items = (1...25).map { "item\($0)" }
        await store.save(items)
        let loaded = await store.load()
        XCTAssertEqual(loaded.count, 20)
        XCTAssertEqual(loaded.first, "item1")
    }
}
