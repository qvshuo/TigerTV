import Foundation

struct SourceConfig: Codable, Sendable {
    let apiSite: [String: SourceSite]
    enum CodingKeys: String, CodingKey { case apiSite = "api_site" }
}

struct SourceSite: Codable, Sendable {
    let name: String
    let api: String
    let detail: String?
}

struct SearchResponse: Codable, Sendable {
    let keyword: String
    let works: [VideoWork]
    enum CodingKeys: String, CodingKey { case keyword = "query", works }
}

struct SearchResult: Codable, Identifiable, Hashable, Sendable {
    let site: String
    let vodId: Int
    let vodName: String
    let vodTime: String
    let vodRemarks: String
    let vodPic: String
    let vodYear: String
    let typeName: String

    var id: String { "\(site)-\(vodId)" }
    var displayTitle: String { vodName }

    enum CodingKeys: String, CodingKey {
        case site
        case vodId = "vod_id", vodName = "vod_name", vodTime = "vod_time"
        case vodRemarks = "vod_remarks", vodPic = "vod_pic", vodYear = "vod_year", typeName = "type_name"
    }
}

struct FetchResponse: Codable, Identifiable, Hashable, Sendable {
    let vodId: Int
    let site: String
    let vodName: String
    let vodPic: String
    let vodYear: String
    let typeName: String
    let vodRemarks: String
    let vodContent: String
    let vodActor: String
    let vodDirector: String
    let vodArea: String
    let vodLang: String
    let playSources: [PlaybackSource]
    let downloadSources: [PlaybackSource]

    var id: String { "\(site)-\(vodId)" }

    enum CodingKeys: String, CodingKey {
        case vodId = "vod_id", site
        case vodName = "vod_name", vodPic = "vod_pic", vodYear = "vod_year", typeName = "type_name"
        case vodRemarks = "vod_remarks", vodContent = "vod_content", vodActor = "vod_actor"
        case vodDirector = "vod_director", vodArea = "vod_area", vodLang = "vod_lang"
        case playSources = "play_sources", downloadSources = "download_sources"
    }
}

struct VideoWork: Codable, Identifiable, Hashable, Sendable {
    let id: String
    let title: String
    let sources: [SearchResult]
    var playback: [String: PlaybackBookmark] = [:]
    var lastPlayedSite: String?
    var recentPlayedSite: String?
    enum CodingKeys: String, CodingKey {
        case id, title, sources, playback, lastPlayedSite = "last_played_site", recentPlayedSite = "recent_played_site"
    }
    var defaultSource: SearchResult? {
        sources.first(where: { $0.site == lastPlayedSite })
            ?? sources.first(where: { $0.site == recentPlayedSite })
            ?? sources.first(where: { !$0.vodPic.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) ?? sources.first
    }
    var orderedSources: [SearchResult] {
        let priorities = [lastPlayedSite, recentPlayedSite].compactMap { $0 }
        var seen = Set<String>()
        return (priorities.flatMap { site in sources.filter { $0.site == site } } + sources)
            .filter { seen.insert($0.id).inserted }
    }
}

struct PlaybackSource: Codable, Identifiable, Hashable, Sendable {
    let id: String
    let name: String
    let episodes: [EpisodeLink]

    func resumeEpisode(_ bookmark: PlaybackBookmark?) -> EpisodeLink? {
        guard bookmark?.line == id else { return episodes.first }
        return episodes.first { $0.name == bookmark?.episode } ?? episodes.first
    }
}

struct EpisodeLink: Codable, Identifiable, Hashable, Sendable {
    let name: String
    let url: String
    var id: String { "\(name)-\(url)" }
}

/// Image selection has no authority over the default playback source.
@MainActor
func loadFirstCover<Value>(
    candidates: [URL], load: (URL) async -> Value?
) async -> (url: URL, value: Value)? {
    var seen = Set<URL>()
    let ordered = candidates.filter { seen.insert($0).inserted }
    for url in ordered {
        guard !Task.isCancelled else { return nil }
        let value = await load(url)
        guard !Task.isCancelled else { return nil }
        if let value { return (url, value) }
    }
    return nil
}

struct PlaybackBookmark: Codable, Hashable, Sendable {
    let line: String
    let episode: String
}

struct HotSearchCategory: Codable, Identifiable, Hashable, Sendable {
    let id: String
    let name: String
}
