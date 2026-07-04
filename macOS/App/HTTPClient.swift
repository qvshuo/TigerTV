import Foundation

actor HTTPClient {
    static let maxResponseSize = 10 * 1024 * 1024
    static let userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"

    private let session: URLSession

    init(session: URLSession = .shared) {
        self.session = session
    }

    func fetchString(url: URL, timeout: TimeInterval) async throws -> String {
        let data = try await fetchData(url: url, timeout: timeout)
        guard let text = String(data: data, encoding: .utf8) else {
            throw TigerTVError.network("Invalid UTF-8 response")
        }
        return text
    }

    func fetchData(url: URL, timeout: TimeInterval) async throws -> Data {
        let encodedURL = encodedURL(url)
        var request = URLRequest(url: encodedURL)
        request.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = timeout

        let (asyncBytes, response) = try await session.bytes(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw TigerTVError.network("Non-HTTP response")
        }
        guard (200..<300).contains(httpResponse.statusCode) else {
            throw TigerTVError.network("HTTP \(httpResponse.statusCode)")
        }

        var data = Data()
        data.reserveCapacity(1024)
        var byteCount = 0
        for try await byte in asyncBytes {
            data.append(byte)
            byteCount += 1
            if byteCount > Self.maxResponseSize {
                throw TigerTVError.network("Response exceeds 10MB limit")
            }
        }
        return data
    }

    private func encodedURL(_ url: URL) -> URL {
        let string = url.absoluteString
        if string.allSatisfy(\.isASCII) { return url }
        guard var components = URLComponents(string: string) else { return url }
        if let encodedPath = components.path.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) {
            components.percentEncodedPath = encodedPath
        }
        if components.queryItems != nil {
            components.queryItems = components.queryItems
        }
        return components.url ?? url
    }
}
