import XCTest
@testable import TigerTVKit

final class SearchMergerTests: XCTestCase {

    private func make(_ site: String, _ id: Int, _ name: String,
                      time: String = "", remarks: String = "", pic: String = "") -> SearchResult {
        SearchResult(site: site, vodId: id, vodName: name, vodTime: time, vodRemarks: remarks, vodPic: pic)
    }

    // MARK: 归一化与年份

    func testNormalizeNameTrimsWhitespaceCaseAndFullWidth() {
        XCTAssertEqual(normalizeVodName("  逐　玉 "), "逐玉")
        XCTAssertEqual(normalizeVodName("Ｆｕｌｌ Ｗｉｄｔｈ １２３"), "fullwidth123")
        XCTAssertEqual(normalizeVodName("Foo Bar"), "foobar")
    }

    func testVodYearExtraction() {
        XCTAssertEqual(vodYear("2026-01-01"), "2026")
        XCTAssertEqual(vodYear("2025"), "2025")
        XCTAssertEqual(vodYear(""), "")
        XCTAssertEqual(vodYear("abc-2026"), "")
        XCTAssertEqual(vodYear("20 26"), "")
    }

    // MARK: 合并行为

    func testSameNameSameYearMerges() {
        let a = make("🎬-爱奇艺-", 1, "逐玉", time: "2026-01-01")
        let b = make("🎬金鹰点播", 2, "逐玉", time: "2026-01-02")
        let merged = mergeSearchResults([a, b])
        XCTAssertEqual(merged.count, 1)
        XCTAssertEqual(merged[0].sources.count, 2)
        XCTAssertEqual(merged[0].extraCount, 1)
    }

    func testSameNameDifferentYearDoesNotMerge() {
        let a = make("🎬A", 1, "逐玉", time: "2026-01-01")
        let b = make("🎬B", 2, "逐玉", time: "2020-01-01")
        XCTAssertEqual(mergeSearchResults([a, b]).count, 2)
    }

    func testSameNameMissingYearMergesByNameOnly() {
        let a = make("🎬A", 1, "逐玉")
        let b = make("🎬B", 2, " 逐　玉 ")
        let merged = mergeSearchResults([a, b])
        XCTAssertEqual(merged.count, 1)
        XCTAssertEqual(merged[0].sources.count, 2)
    }

    func testDistinctNamesDoNotMerge() {
        let a = make("🎬A", 1, "逐玉")
        let b = make("🎬B", 2, "苍兰诀")
        XCTAssertEqual(mergeSearchResults([a, b]).count, 2)
    }

    // MARK: 主源选择

    func testPrimaryPrefersCover() {
        let noCover = make("🎬A", 1, "逐玉", time: "2026-01-09", remarks: "完结")
        let withCover = make("🎬B", 2, "逐玉", time: "2026-01-01", pic: "https://p/x.jpg")
        let merged = mergeSearchResults([noCover, withCover])
        XCTAssertEqual(merged[0].primary.site, "🎬B")
    }

    func testPrimaryPrefersRemarksWhenNoCover() {
        let noRemarks = make("🎬A", 1, "逐玉", time: "2026-01-09")
        let withRemarks = make("🎬B", 2, "逐玉", time: "2026-01-01", remarks: "更新至10集")
        let merged = mergeSearchResults([noRemarks, withRemarks])
        XCTAssertEqual(merged[0].primary.site, "🎬B")
    }

    func testPrimaryPrefersNewerTimeWhenTied() {
        let older = make("🎬A", 1, "逐玉", time: "2025-12-28", remarks: "完结")
        let newer = make("🎬B", 2, "逐玉", time: "2026-01-01", remarks: "完结")
        let merged = mergeSearchResults([older, newer])
        XCTAssertEqual(merged[0].primary.site, "🎬B")
    }

    func testPrimaryFallsBackToFirstWhenFullyTied() {
        let first = make("🎬A", 1, "逐玉")
        let second = make("🎬B", 2, "逐玉")
        let merged = mergeSearchResults([first, second])
        XCTAssertEqual(merged[0].primary.site, "🎬A")
    }

    // MARK: 顺序与透传

    func testGroupOrderFollowsFirstAppearance() {
        // 「逐玉」先出现（位置0），「苍兰诀」位置1，逐玉另一源在位置2；
        // 合并后「逐玉」组应仍在「苍兰诀」前。
        let a1 = make("🎬A", 1, "逐玉")
        let b1 = make("🎬B", 2, "苍兰诀")
        let a2 = make("🎬C", 3, "逐玉")
        let merged = mergeSearchResults([a1, b1, a2])
        XCTAssertEqual(merged.count, 2)
        XCTAssertEqual(merged[0].primary.vodName, "逐玉")
        XCTAssertEqual(merged[0].sources.count, 2)
        XCTAssertEqual(merged[1].primary.vodName, "苍兰诀")
    }

    func testSingleSourceGroupPassthrough() {
        let a = make("🎬A", 1, "逐玉")
        let merged = mergeSearchResults([a])
        XCTAssertEqual(merged.count, 1)
        XCTAssertEqual(merged[0].extraCount, 0)
        XCTAssertEqual(merged[0].primary, a)
        XCTAssertEqual(merged[0].sources, [a])
    }

    func testEmptyInput() {
        XCTAssertTrue(mergeSearchResults([]).isEmpty)
    }
}
