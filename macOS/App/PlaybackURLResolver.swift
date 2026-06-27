import Foundation

struct PlaybackURLResolver {
    func resolve(from urlString: String) async throws -> URL {
        guard let url = URL(string: urlString) else {
            throw TigerTVError.playbackResolutionFailed
        }

        let lowerPath = url.pathExtension.lowercased()
        if lowerPath == "m3u8" || lowerPath == "mp4" {
            return url
        }

        let (data, _) = try await URLSession.shared.data(from: url)
        guard let html = String(data: data, encoding: .utf8) else {
            throw TigerTVError.playbackResolutionFailed
        }

        // Try absolute m3u8 URL.
        let absolutePattern = #"(https?://[^\s"'<>]+\.m3u8(?:\?[^\s"'<>]+)?)"#
        if let regex = try? NSRegularExpression(pattern: absolutePattern, options: []),
           let match = regex.firstMatch(in: html, options: [], range: NSRange(html.startIndex..., in: html)),
           let range = Range(match.range, in: html) {
            let m3u8URL = String(html[range])
            if let result = URL(string: m3u8URL) {
                return result
            }
        }

        // Try relative m3u8 path.
        let relativePattern = #"["']([^"']*\.m3u8(?:\?[^"']+)?)["']"#
        if let regex = try? NSRegularExpression(pattern: relativePattern, options: []),
           let match = regex.firstMatch(in: html, options: [], range: NSRange(html.startIndex..., in: html)),
           let range = Range(match.range(at: 1), in: html) {
            let relative = String(html[range])
            if let resolved = URL(string: relative, relativeTo: url)?.absoluteURL {
                return resolved
            }
        }

        throw TigerTVError.playbackResolutionFailed
    }
}
