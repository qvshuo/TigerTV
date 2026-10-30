import Foundation

enum HTTPClient {
    static let userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/27.2 Safari/605.1.15"

    /// 百分号编码非 ASCII URL 字符串（含首尾空白 trim），供网络请求与封面图等
    /// 外部字符串 URL 统一使用——上游封面路径常含非 ASCII 字符，直接 `URL(string:)`
    /// 会得到 nil 而静默丢失图片。
    static func percentEncodedURL(from string: String) -> URL? {
        let trimmed = string.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        if trimmed.allSatisfy(\.isASCII), let url = URL(string: trimmed) { return url }
        guard var components = URLComponents(string: trimmed) else { return nil }
        if let encodedPath = components.path.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) {
            components.percentEncodedPath = encodedPath
        }
        // 非 ASCII query 必须通过 queryItems 重建（URLComponents 在 set queryItems
        // 时会对每个值做 percent-encoding）；原 queryItems 直接赋回是 no-op。
        if let items = components.queryItems {
            // 先清空再赋值，强制 URLComponents 重新编码非 ASCII 字符。
            components.queryItems = nil
            components.queryItems = items
        }
        return components.url
    }
}
