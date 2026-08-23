import Foundation

struct SourceConfig: Codable, Sendable {
    let cacheTime: Int?
    let apiSite: [String: SourceSite]

    enum CodingKeys: String, CodingKey {
        case cacheTime = "cache_time"
        case apiSite = "api_site"
    }
}

struct SourceSite: Codable, Sendable {
    let name: String
    let api: String
    let detail: String?
    let comment: String?

    enum CodingKeys: String, CodingKey {
        case name, api, detail
        case comment = "_comment"
    }
}

struct SearchResponse: Codable, Sendable {
    let keyword: String
    let results: [SearchResult]
}

struct SearchResult: Codable, Identifiable, Hashable, Sendable {
    let site: String
    let vodId: Int
    let vodName: String
    let vodTime: String
    let vodRemarks: String
    let vodPic: String

    var id: String { "\(site)-\(vodId)" }
    var displayTitle: String { vodName }

    enum CodingKeys: String, CodingKey {
        case site
        case vodId = "vod_id"
        case vodName = "vod_name"
        case vodTime = "vod_time"
        case vodRemarks = "vod_remarks"
        case vodPic = "vod_pic"
    }

    init(site: String, vodId: Int, vodName: String, vodTime: String = "", vodRemarks: String = "", vodPic: String = "") {
        self.site = site
        self.vodId = vodId
        self.vodName = vodName
        self.vodTime = vodTime
        self.vodRemarks = vodRemarks
        self.vodPic = vodPic
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        site = try container.decode(String.self, forKey: .site)
        vodId = try container.decode(Int.self, forKey: .vodId)
        vodName = try container.decode(String.self, forKey: .vodName)
        vodTime = try container.decodeIfPresent(String.self, forKey: .vodTime) ?? ""
        vodRemarks = try container.decodeIfPresent(String.self, forKey: .vodRemarks) ?? ""
        vodPic = try container.decodeIfPresent(String.self, forKey: .vodPic) ?? ""
    }
}

struct FetchResponse: Identifiable, Hashable, Sendable {
    let vodId: Int
    let site: String
    let vodPlayUrl: [EpisodeLink]
    let vodDownUrl: [EpisodeLink]

    var id: String { "\(site)-\(vodId)" }

    init(vodId: Int, site: String, vodPlayUrl: [EpisodeLink], vodDownUrl: [EpisodeLink]) {
        self.vodId = vodId
        self.site = site
        self.vodPlayUrl = vodPlayUrl
        self.vodDownUrl = vodDownUrl
    }
}

struct EpisodeLink: Codable, Identifiable, Hashable, Sendable {
    let name: String
    let url: String

    var id: String { "\(name)-\(url)" }
}
