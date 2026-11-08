import Foundation
import AVFoundation

/// Exercises the production ViewModel and player handoff without a second business core.
@MainActor
private final class StateRepository: VideoRepository {
    let searchResponse: SearchResponse
    let details: [String: FetchResponse]
    var calls = 0
    var delayDetail = false
    var pendingDetail: CheckedContinuation<LoadResult<FetchResponse>, Never>?
    var onSearchUpdate: (@Sendable (SearchResponse) async -> Void)?
    var pendingSearch: CheckedContinuation<LoadResult<SearchResponse>, Never>?
    var positions: [PlaybackSession] = []
    var starts: [String] = []
    var historySaved = false

    init(root: URL) throws {
        let decoder = JSONDecoder()
        searchResponse = try decoder.decode(SearchResponse.self, from: Data(contentsOf: root.appendingPathComponent("docs/fixtures/search.sample.json")))
        let raw = try Data(contentsOf: root.appendingPathComponent("docs/fixtures/fetch-playback.sample.json"))
        var details: [String: FetchResponse] = [:]
        for source in searchResponse.works[0].sources {
            var json = try JSONSerialization.jsonObject(with: raw) as! [String: Any]
            json["site"] = source.site
            json["vod_id"] = source.vodId
            details[source.site] = try decoder.decode(FetchResponse.self, from: JSONSerialization.data(withJSONObject: json))
        }
        self.details = details
    }
    func loadConfig() async -> LoadResult<SourceConfig> { calls += 1; return .success(SourceConfig(apiSite: [:])) }
    func search(keyword: String, onUpdate: @escaping @Sendable (SearchResponse) async -> Void) async -> LoadResult<SearchResponse> {
        calls += 1
        if keyword == "old" {
            onSearchUpdate = onUpdate
            return await withCheckedContinuation { pendingSearch = $0 }
        }
        await onUpdate(searchResponse)
        return .success(searchResponse)
    }
    func fetch(siteName: String, vodId: Int) async -> LoadResult<FetchResponse> {
        calls += 1
        if delayDetail { delayDetail = false; return await withCheckedContinuation { pendingDetail = $0 } }
        return .success(details[siteName]!)
    }
    func coverFallbackURL(siteName: String, vodId: Int) async -> URL? { calls += 1; return nil }
    func recordPlayback(workId: String, site: String, line: String, episode: String) async { starts.append(site) }
    func recordPlaybackPosition(workId: String, site: String, line: String, episode: String, positionMillis: Int64) async {
        positions.append(PlaybackSession(workId: workId, site: site, line: line, episode: episode, positionMillis: positionMillis, revision: 0))
    }
    func loadHotSearchCategories() async -> [HotSearchCategory] { [] }
    func warmHotSearches(category: String) async {}
    func loadHotSearches(category: String) async -> [HotSearchItem] { [] }
    func loadHistory() async -> [String] { [] }
    func addHistory(_ item: String) async {
        if item == "flush-test" {
            try? await Task.sleep(for: .milliseconds(50))
            historySaved = true
        }
    }
    func removeHistory(_ item: String) async {}
    func clearHistory() async {}
}

@main
struct MacStateCheck {
    @MainActor
    private static func settle(_ condition: () -> Bool) async throws {
        for _ in 0..<1000 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(1))
        }
        fatalError("Timed out waiting for ViewModel state")
    }

    @MainActor
    static func main() async throws {
        let root = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
        let repository = try StateRepository(root: root)
        let model = TigerTVViewModel(repository: repository)
        model.keyword = "test"
        model.search()
        try await settle { !model.isSearching && !model.works.isEmpty }
        let sources = model.works[0].sources
        model.selectResult(sources[0])
        try await settle { !model.isFetching }
        let calls = repository.calls
        let revisionBeforeStaleSelection = model.playbackRevision
        model.selectEpisode(EpisodeLink(name: "stale", url: "https://old.test/episode.mp4"))
        precondition(model.playbackRevision == revisionBeforeStaleSelection && model.selectedEpisode == nil)
        for episode in model.episodes {
            model.selectEpisode(episode)
            precondition(model.selectedEpisode?.url == episode.url)
            let revision = model.playbackRevision
            model.retryPlayback()
            precondition(model.playbackRevision == revision + 1)
            precondition(model.selectedEpisode?.url == episode.url && model.selectedResult == sources[0])
            let controller = AVPlayerController()
            controller.load(url: model.selectedEpisode!.url, positionMillis: 0, onStarted: {}, onPosition: { _ in })
            let asset = controller.player.currentItem?.asset as? AVURLAsset
            precondition(asset?.url == HTTPConfiguration.percentEncodedURL(from: episode.url))
            controller.close()
            precondition(controller.player.currentItem == nil)
        }
        let invalidPlayer = AVPlayerController()
        for url in ["", "   ", "relative/video.mp4", "https://", "data:text/plain,video"] {
            invalidPlayer.load(url: url, positionMillis: 0,
                onStarted: { preconditionFailure("Invalid URL started playback") },
                onPosition: { _ in preconditionFailure("Invalid URL wrote progress") })
            precondition(invalidPlayer.errorMessage != nil && invalidPlayer.player.currentItem == nil)
        }
        invalidPlayer.close()
        precondition(invalidPlayer.errorMessage == nil)
        precondition(repository.calls == calls, "Playback selection or retry called the repository")

        model.selectEpisode(model.episodes[0])
        let original = model.playbackSession!
        model.playbackDidStart(original)
        await model.flushPersistence()
        model.retryPlayback()
        model.playbackDidStart(model.playbackSession!)
        model.playbackDidStart(model.playbackSession!)
        await model.flushPersistence()
        precondition(repository.starts == [sources[0].site, sources[0].site])
        model.selectResult(sources[1])
        try await settle { !model.isFetching }
        model.selectEpisode(model.episodes[0])
        model.playbackDidStart(original)
        model.savePlaybackPosition(original, positionMillis: 755_000)
        await model.flushPersistence()
        precondition(repository.starts == [sources[0].site, sources[0].site])
        precondition(repository.positions.last?.site == sources[0].site)
        precondition(model.works[0].playbackPositions[sources[1].site] == nil)

        repository.delayDetail = true
        model.selectResult(sources[0])
        try await settle { repository.pendingDetail != nil }
        model.selectResult(sources[1])
        try await settle { !model.isFetching }
        repository.pendingDetail?.resume(returning: .failure(.invalidResponse))
        repository.pendingDetail = nil
        try await Task.sleep(for: .milliseconds(20))
        precondition(model.fetchResponse?.site == sources[1].site && model.fetchErrorMessage == nil)

        model.keyword = "old"
        model.search()
        try await settle { repository.pendingSearch != nil }
        model.keyword = "new"
        model.search()
        try await settle { !model.isSearching }
        repository.pendingSearch?.resume(returning: .failure(.invalidResponse))
        repository.pendingSearch = nil
        try await Task.sleep(for: .milliseconds(20))
        precondition(!model.works.isEmpty && model.searchErrorMessage == nil)
        model.keyword = "old"
        model.search()
        try await settle { repository.pendingSearch != nil }
        model.clearResults()
        // A detached callback carries no cancellation flag; the generation must reject it.
        await repository.onSearchUpdate?(repository.searchResponse)
        repository.pendingSearch?.resume(returning: .success(repository.searchResponse))
        repository.pendingSearch = nil
        try await Task.sleep(for: .milliseconds(20))
        precondition(model.works.isEmpty && !model.isSearching)

        repository.delayDetail = true
        model.selectResult(sources[0])
        try await settle { repository.pendingDetail != nil }
        model.cancelRequests()
        repository.pendingDetail?.resume(returning: .success(repository.details[sources[0].site]!))
        repository.pendingDetail = nil
        try await Task.sleep(for: .milliseconds(20))
        precondition(model.fetchResponse == nil && !model.isFetching)
        model.keyword = "flush-test"
        model.search()
        model.cancelRequests()
        await model.flushPersistence()
        precondition(repository.historySaved, "Closing must flush search history as well as playback")
        print("macOS state passed: raw URL handoff/retry, stale responses, playback session isolation, release")
    }
}
