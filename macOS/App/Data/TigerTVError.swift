import Foundation

enum TigerTVError: LocalizedError, Sendable {
    case request(String)
    case invalidResponse

    var errorDescription: String? {
        switch self {
        case .request(let message): return message
        case .invalidResponse: return "资源数据异常，请重试"
        }
    }
}
