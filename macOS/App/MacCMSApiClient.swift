import Foundation
import os.log

private let logger = Logger(subsystem: "art.anjing.TigerTV", category: "MacCMSApiClient")

actor MacCMSApiClient {
    private let httpClient: HTTPClient
    private let decoder: JSONDecoder

    init(httpClient: HTTPClient = HTTPClient()) {
        self.httpClient = httpClient
        self.decoder = JSONDecoder()
    }

    func search(siteName: String, api: String, keyword: String) async -> [SearchResult] {
        guard let url = buildURL(api: api, params: ["ac": "list", "wd": keyword, "pagesize": "100"]) else {
            return []
        }
        do {
            let data = try await httpClient.fetchData(url: url, timeout: 20)
            let response = try decoder.decode(MacCMSListResponse.self, from: data)
            guard response.code == 1 else { return [] }
            return response.list.map { item in
                SearchResult(
                    site: siteName,
                    vodId: item.vodId,
                    vodName: item.vodName,
                    vodTime: item.vodTime,
                    vodRemarks: item.vodRemarks
                )
            }
        } catch {
            logger.warning("Search failed for \(siteName): \(error.localizedDescription)")
            return []
        }
    }

    func fetchDetail(api: String, vodId: Int) async throws -> MacCMSDetailResponse {
        guard let url = buildURL(api: api, params: ["ac": "detail", "ids": String(vodId)]) else {
            throw TigerTVError.network("Invalid API URL")
        }
        let data = try await httpClient.fetchData(url: url, timeout: 20)
        return try decoder.decode(MacCMSDetailResponse.self, from: data)
    }

    private func buildURL(api: String, params: [String: String]) -> URL? {
        guard var components = URLComponents(string: api) else { return nil }
        var queryItems = components.queryItems ?? []
        for (key, value) in params {
            queryItems.append(URLQueryItem(name: key, value: value))
        }
        components.queryItems = queryItems
        return components.url
    }
}

struct MacCMSListResponse: Codable, Sendable {
    let code: Int
    let msg: String?
    let list: [MacCMSListItem]
}

struct MacCMSListItem: Codable, Sendable {
    let vodId: Int
    let vodName: String
    let vodTime: String
    let vodRemarks: String

    enum CodingKeys: String, CodingKey {
        case vodId = "vod_id"
        case vodName = "vod_name"
        case vodTime = "vod_time"
        case vodRemarks = "vod_remarks"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        vodId = try container.decode(Int.self, forKey: .vodId)
        vodName = try container.decode(String.self, forKey: .vodName)
        vodTime = try container.decodeIfPresent(String.self, forKey: .vodTime) ?? ""
        vodRemarks = try container.decodeIfPresent(String.self, forKey: .vodRemarks) ?? ""
    }
}

struct MacCMSDetailResponse: Codable, Sendable {
    let code: Int
    let msg: String?
    let list: [MacCMSDetailItem]
}

struct MacCMSDetailItem: Codable, Sendable {
    let vodId: Int
    let vodName: String?
    let vodPlayUrl: String?
    let vodDownUrl: String?

    enum CodingKeys: String, CodingKey {
        case vodId = "vod_id"
        case vodName = "vod_name"
        case vodPlayUrl = "vod_play_url"
        case vodDownUrl = "vod_down_url"
    }
}
