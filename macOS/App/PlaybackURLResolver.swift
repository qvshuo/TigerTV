import Foundation

actor PlaybackURLResolver {
    private let httpClient: HTTPClient

    init(httpClient: HTTPClient = HTTPClient()) {
        self.httpClient = httpClient
    }

    func resolve(_ urlString: String) async throws -> String {
        guard let url = URL(string: urlString) else {
            throw TigerTVError.playbackResolutionFailed
        }

        let pathLower = url.path.lowercased()
        if pathLower.hasSuffix(".m3u8") || pathLower.hasSuffix(".mp4") {
            return urlString
        }

        let data = try await httpClient.fetchData(url: url, timeout: 10)
        guard let html = String(data: data, encoding: .utf8) else {
            throw TigerTVError.playbackResolutionFailed
        }

        let stripped = html.trimmingCharacters(in: .whitespacesAndNewlines)
        if stripped.hasPrefix("#EXTM3U") {
            return urlString
        }

        let normalized = html.replacingOccurrences(of: "\\/", with: "/")

        let absolutePattern = #"(https?://[^\s"'<>]+\.m3u8(?:\?[^\s"'<>]+)?)"#
        if let regex = try? NSRegularExpression(pattern: absolutePattern, options: .caseInsensitive),
           let match = regex.firstMatch(
               in: normalized,
               options: [],
               range: NSRange(normalized.startIndex..., in: normalized)
           ),
           let range = Range(match.range(at: 1), in: normalized) {
            return percentDecoded(String(normalized[range]))
        }

        let relativePattern = #"["']([^"']*\.m3u8(?:\?[^"']+)?)["']"#
        if let regex = try? NSRegularExpression(pattern: relativePattern, options: .caseInsensitive),
           let match = regex.firstMatch(
               in: normalized,
               options: [],
               range: NSRange(normalized.startIndex..., in: normalized)
           ),
           let range = Range(match.range(at: 1), in: normalized) {
            let relative = percentDecoded(String(normalized[range]))
            guard let resolved = URL(string: relative, relativeTo: url)?.absoluteURL else {
                throw TigerTVError.playbackResolutionFailed
            }
            return resolved.absoluteString
        }

        throw TigerTVError.playbackResolutionFailed
    }

    private func percentDecoded(_ string: String) -> String {
        string.removingPercentEncoding ?? string
    }
}
