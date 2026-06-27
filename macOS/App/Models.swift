import Foundation

enum TigerTVError: Error, LocalizedError {
    case cliNotFound
    case pythonNotFound
    case invalidOutput
    case cliError(String)
    case commandTimeout(String)
    case playbackResolutionFailed

    var errorDescription: String? {
        switch self {
        case .cliNotFound:
            return "未找到 tigertv-cli.py"
        case .pythonNotFound:
            return "未找到 Python 3 运行时"
        case .invalidOutput:
            return "CLI 返回了无效数据"
        case .cliError(let msg):
            return msg
        case .commandTimeout(let command):
            return "\(command) 超时，请稍后重试或换个关键词"
        case .playbackResolutionFailed:
            return "无法解析播放地址"
        }
    }
}

struct SearchResponse: Codable {
    let keyword: String
    let results: [SearchResult]
}

struct SearchResult: Codable, Identifiable, Hashable {
    let site: String
    let vod_id: Int
    let vod_name: String
    let vod_time: String?
    let vod_remarks: String?

    var id: String { "\(site)-\(vod_id)" }

    var displayTitle: String { vod_name }
}

struct FetchResponse: Codable, Identifiable, Hashable {
    let vod_id: Int
    let site: String
    let vod_play_url: [EpisodeLink]
    let vod_down_url: [EpisodeLink]

    var id: String { "\(site)-\(vod_id)" }
}

struct EpisodeLink: Codable, Identifiable, Hashable {
    let name: String
    let url: String

    var id: String { "\(name)-\(url)" }
}
