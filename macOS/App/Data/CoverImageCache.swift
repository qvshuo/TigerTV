import Foundation
import AppKit
import CryptoKit

/// Memory and seven-day disk cache for source posters.
final class CoverImageCache: @unchecked Sendable {
    static let ttl: TimeInterval = 7 * 86400
    static let maxResponseSize = 10 * 1024 * 1024

    static let shared = CoverImageCache()

    private let memory = NSCache<NSURL, NSImage>()
    private let directory: URL
    private let session: URLSession
    private let ioQueue = DispatchQueue(label: "art.anjing.TigerTV.CoverImageCache")

    init(directory: URL? = nil, session: URLSession? = nil) {
        let base = directory
            ?? FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first?.appendingPathComponent("TigerTV/CoverImages", isDirectory: true)
            ?? FileManager.default.temporaryDirectory.appendingPathComponent("TigerTV/CoverImages", isDirectory: true)
        memory.totalCostLimit = 64 * 1024 * 1024
        memory.countLimit = 256
        self.directory = base
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        self.session = session ?? {
            let configuration = URLSessionConfiguration.ephemeral
            configuration.timeoutIntervalForRequest = 20
            configuration.httpAdditionalHeaders = ["User-Agent": HTTPConfiguration.userAgent]
            return URLSession(configuration: configuration)
        }()
    }

    func cachedImage(for url: URL) -> NSImage? {
        memory.object(forKey: url as NSURL)
    }

    func image(for url: URL) async -> NSImage? {
        if let cached = cachedImage(for: url) {
            return cached
        }
        let file = diskFile(for: url)
        let diskImage = await withCheckedContinuation { continuation in
            ioQueue.async { continuation.resume(returning: self.freshDiskImage(at: file)) }
        }
        guard !Task.isCancelled else { return nil }
        if let fresh = diskImage {
            memory.setObject(fresh, forKey: url as NSURL, cost: imageCost(fresh))
            return fresh
        }

        var request = URLRequest(url: url)
        if let host = url.host, host == "doubanio.com" || host.hasSuffix(".doubanio.com") {
            request.setValue("https://m.douban.com/", forHTTPHeaderField: "Referer")
        }
        guard let data = await downloadImage(request), !Task.isCancelled,
              let image = NSImage(data: data) else {
            return nil
        }
        memory.setObject(image, forKey: url as NSURL, cost: imageCost(image))
        ioQueue.async { [directory] in
            try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try? data.write(to: file, options: .atomic)
        }
        return image
    }

    private func downloadImage(_ request: URLRequest) async -> Data? {
        do {
            let (bytes, response) = try await session.bytes(for: request)
            defer { bytes.task.cancel() }
            guard let response = response as? HTTPURLResponse,
                  (200..<300).contains(response.statusCode),
                  response.expectedContentLength <= Self.maxResponseSize else { return nil }
            var data = Data()
            if response.expectedContentLength > 0 {
                data.reserveCapacity(Int(response.expectedContentLength))
            }
            for try await byte in bytes {
                guard data.count < Self.maxResponseSize else { return nil }
                data.append(byte)
            }
            return data
        } catch {
            return nil
        }
    }

    private func imageCost(_ image: NSImage) -> Int {
        image.representations.map { max(1, $0.pixelsWide) * max(1, $0.pixelsHigh) * 4 }.max() ?? 1
    }

    private func diskFile(for url: URL) -> URL {
        let digest = SHA256.hash(data: Data(url.absoluteString.utf8))
        let hex = digest.map { String(format: "%02x", $0) }.joined()
        return directory.appendingPathComponent(hex)
    }

    private func freshDiskImage(at file: URL) -> NSImage? {
        let metadata = try? file.resourceValues(forKeys: [.contentModificationDateKey])
        if let modified = metadata?.contentModificationDate,
           Date().timeIntervalSince(modified) >= Self.ttl {
            try? FileManager.default.removeItem(at: file)
            return nil
        }
        return NSImage(contentsOf: file)
    }
}
