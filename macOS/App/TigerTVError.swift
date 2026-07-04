import Foundation

enum TigerTVError: LocalizedError, Sendable {
    case configUnavailable(String)
    case unknownSite(String, [String])
    case fetchFailed(String)
    case playbackResolutionFailed
    case network(String)
    case invalidResponse

    var errorDescription: String? {
        switch self {
        case .configUnavailable(let details):
            return "配置加载失败：\(details)"
        case .unknownSite(let name, let available):
            return "未知站点：\(name)。可用站点：\(available.joined(separator: "、"))"
        case .fetchFailed(let message):
            return "获取剧集失败：\(message)"
        case .playbackResolutionFailed:
            return "无法解析播放地址"
        case .network(let message):
            return "网络错误：\(message)"
        case .invalidResponse:
            return "服务器返回了无效数据"
        }
    }
}
