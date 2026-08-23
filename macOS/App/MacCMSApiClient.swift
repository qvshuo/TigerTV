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
                    vodRemarks: item.vodRemarks,
                    vodPic: item.vodPic
                )
            }
        } catch {
            // 解码异常（含 list 元素非对象）视为该站失败，空结果继续，与 CLI 对 `{"list":[null]}` 的处理一致。
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

/// 宽松 Int 解码：容忍 int / float / 数字字符串，与 CLI `int(code)` / `int(vod_id)` 及 Android `LenientIntSerializer` 对齐。
///
/// 背景：部分 MacCMS 站返回 `"vod_id":"136872"`（字符串）或 `"code":1.0`（浮点）。
/// 严格 `decode(Int.self)` 会失败并导致整站搜索结果被丢弃。
private extension KeyedDecodingContainer {
    func decodeLenientInt(forKey key: Key) -> Int {
        if let value = try? decode(Int.self, forKey: key) {
            return value
        }
        if let value = try? decode(Double.self, forKey: key) {
            return Int(value)
        }
        if let value = try? decode(String.self, forKey: key) {
            if let intValue = Int(value) { return intValue }
            if let doubleValue = Double(value) { return Int(doubleValue) }
        }
        return 0
    }
}

struct MacCMSListResponse: Codable, Sendable {
    let code: Int
    let msg: String?
    let list: [MacCMSListItem]

    enum CodingKeys: String, CodingKey {
        case code, msg, list
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        code = container.decodeLenientInt(forKey: .code)
        msg = try container.decodeIfPresent(String.self, forKey: .msg)
        list = try container.decodeIfPresent([MacCMSListItem].self, forKey: .list) ?? []
    }
}

struct MacCMSListItem: Codable, Sendable {
    let vodId: Int
    let vodName: String
    let vodTime: String
    let vodRemarks: String
    let vodPic: String

    enum CodingKeys: String, CodingKey {
        case vodId = "vod_id"
        case vodName = "vod_name"
        case vodTime = "vod_time"
        case vodRemarks = "vod_remarks"
        case vodPic = "vod_pic"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        vodId = container.decodeLenientInt(forKey: .vodId)
        vodName = try container.decodeIfPresent(String.self, forKey: .vodName) ?? ""
        vodTime = try container.decodeIfPresent(String.self, forKey: .vodTime) ?? ""
        vodRemarks = try container.decodeIfPresent(String.self, forKey: .vodRemarks) ?? ""
        vodPic = try container.decodeIfPresent(String.self, forKey: .vodPic) ?? ""
    }
}

struct MacCMSDetailResponse: Codable, Sendable {
    let code: Int
    let msg: String?
    let list: [MacCMSDetailItem]

    enum CodingKeys: String, CodingKey {
        case code, msg, list
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        code = container.decodeLenientInt(forKey: .code)
        msg = try container.decodeIfPresent(String.self, forKey: .msg)
        list = try container.decodeIfPresent([MacCMSDetailItem].self, forKey: .list) ?? []
    }
}

struct MacCMSDetailItem: Codable, Sendable {
    let vodId: Int
    let vodName: String?
    let vodPic: String?
    let vodPlayUrl: String?
    let vodDownUrl: String?

    enum CodingKeys: String, CodingKey {
        case vodId = "vod_id"
        case vodName = "vod_name"
        case vodPic = "vod_pic"
        case vodPlayUrl = "vod_play_url"
        case vodDownUrl = "vod_down_url"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        vodId = container.decodeLenientInt(forKey: .vodId)
        vodName = try container.decodeIfPresent(String.self, forKey: .vodName)
        vodPic = try container.decodeIfPresent(String.self, forKey: .vodPic)
        vodPlayUrl = try container.decodeIfPresent(String.self, forKey: .vodPlayUrl)
        vodDownUrl = try container.decodeIfPresent(String.self, forKey: .vodDownUrl)
    }
}
