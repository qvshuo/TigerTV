import Foundation

enum HTTPClient {
    static let userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/27.2 Safari/605.1.15"

    private static let ascii = CharacterSet(charactersIn: "\u{0}"..."\u{7F}")

    /// Encode Unicode without rewriting existing escapes or signed query parameters.
    static func percentEncodedURL(from string: String) -> URL? {
        let trimmed = string.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty,
              let encoded = trimmed.addingPercentEncoding(withAllowedCharacters: ascii) else { return nil }
        return URL(string: encoded)
    }
}
