import XCTest
@testable import TigerTVKit

final class WorkDetailContractTests: XCTestCase {
    private func fixture(_ name: String) throws -> Data {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
        return try Data(contentsOf: root.appendingPathComponent("docs/fixtures/" + name))
    }

    func testPlaybackBookmarkContract() throws {
        let response = try JSONDecoder().decode(SearchResponse.self, from: fixture("search-playback.sample.json"))
        XCTAssertEqual(response.works.first?.playback, PlaybackBookmark(site: "🎬二", line: "0", episode: "第02集"))
    }

    func testGroupedSearchRetainsIndependentSourceIds() throws {
        let response = try JSONDecoder().decode(SearchResponse.self, from: fixture("search.sample.json"))
        let work = try XCTUnwrap(response.works.first)
        XCTAssertEqual(response.works.count, 1)
        XCTAssertEqual(work.sources.map(\.vodId), [1, 99])
        XCTAssertEqual(work.defaultSource?.site, "🎬二")
    }

    func testLastPlayedSourceOverridesPosterAndMissingSourceFallsBack() throws {
        var work = try XCTUnwrap(JSONDecoder().decode(SearchResponse.self, from: fixture("search.sample.json")).works.first)
        work.lastPlayedSite = work.sources[0].site
        XCTAssertEqual(work.defaultSource?.site, "🎬一")
        XCTAssertEqual(work.orderedSources.first?.site, "🎬一")
        work.lastPlayedSite = "removed-source-9"
        XCTAssertEqual(work.defaultSource?.site, "🎬二")
    }

    func testUnwatchedWorkUsesRecentSiteWithoutLosingOtherSourcesPoster() throws {
        var work = try XCTUnwrap(JSONDecoder().decode(SearchResponse.self, from: fixture("search.sample.json")).works.first)
        work.recentPlayedSite = work.sources[0].site // This source has no poster.
        XCTAssertEqual(work.defaultSource?.site, "🎬一")
        let availableCovers = work.orderedSources.filter { !$0.vodPic.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        XCTAssertEqual(availableCovers.first?.site, "🎬二")
        work.lastPlayedSite = work.sources[1].site
        XCTAssertEqual(work.defaultSource?.site, "🎬二")
    }

    func testDetailDecodesMetadataAndIndependentLines() throws {
        let response = try JSONDecoder().decode(FetchResponse.self, from: fixture("fetch.sample.json"))
        XCTAssertEqual(response.vodContent, "作品简介")
        XCTAssertEqual(response.vodActor, "主演")
        XCTAssertEqual(response.playSources.map(\.id), ["0", "2"])
        XCTAssertEqual(response.playSources.map { $0.episodes.count }, [2, 1])
    }

    func testOldPayloadsAreRejected() throws {
        let search = Data(#"{"keyword":"逐玉","results":[]}"#.utf8)
        XCTAssertThrowsError(try JSONDecoder().decode(SearchResponse.self, from: search))
        let detail = Data(#"{"vod_id":1,"site":"A","vod_play_url":[],"vod_down_url":[]}"#.utf8)
        XCTAssertThrowsError(try JSONDecoder().decode(FetchResponse.self, from: detail))
    }
}
