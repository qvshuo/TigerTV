import Foundation

actor HTTPClient {
    static let maxResponseSize = 10 * 1024 * 1024
    static let userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.4 Safari/605.1.15"

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

        // 一次性读取 + 后置大小校验，与 CLI 的 `read(MAX_RESPONSE_SIZE + 1)` 等价：
        // 避免 URLSession.bytes 逐字节循环带来的性能损耗（200KB → 20 万次迭代）。
        // 协作取消：Task.cancel() 会触发 data(for:) 内部取消并抛 CancellationError。
        let (data, response) = try await session.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw TigerTVError.network("Non-HTTP response")
        }
        guard (200..<300).contains(httpResponse.statusCode) else {
            throw TigerTVError.network("HTTP \(httpResponse.statusCode)")
        }
        if data.count > Self.maxResponseSize {
            throw TigerTVError.network("Response exceeds 10MB limit")
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
        // 非 ASCII query 必须通过 queryItems 重建（URLComponents 在 set queryItems
        // 时会对每个值做 percent-encoding）；原 queryItems 直接赋回是 no-op。
        if let items = components.queryItems {
            // 先清空再赋值，强制 URLComponents 重新编码非 ASCII 字符。
            components.queryItems = nil
            components.queryItems = items
        }
        return components.url ?? url
    }
}
