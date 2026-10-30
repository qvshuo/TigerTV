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
            configuration.httpAdditionalHeaders = ["User-Agent": HTTPClient.userAgent]
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
        if let fresh = ioQueue.sync(execute: { freshDiskImage(at: file) }) {
            memory.setObject(fresh, forKey: url as NSURL, cost: imageCost(fresh))
            return fresh
        }

        guard let (data, response) = try? await session.data(from: url, delegate: nil),
              let httpResponse = response as? HTTPURLResponse,
              (200..<300).contains(httpResponse.statusCode),
              data.count <= Self.maxResponseSize,
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
