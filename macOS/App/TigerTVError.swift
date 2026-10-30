import Foundation

enum TigerTVError: LocalizedError, Sendable {
    case network(String)
    case invalidResponse

    var errorDescription: String? {
        switch self {
        case .network(let message): return "网络错误：\(message)"
        case .invalidResponse: return "服务器返回了无效数据"
        }
    }
}
