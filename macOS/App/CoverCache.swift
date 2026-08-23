import Foundation
import AppKit
import CryptoKit

/// 兜底封面 URL 的持久化缓存（7 天 TTL）：进程重启后无需再发 detail 请求。
/// 文件原子写入；读取时惰性清除过期项。
final class CoverFallbackURLStore: @unchecked Sendable {
    struct Entry: Codable {
        let url: String
        let fetchedAt: Date
    }

    static let ttl: TimeInterval = 7 * 86400

    private let fileURL: URL?
    private let queue = DispatchQueue(label: "art.anjing.TigerTV.CoverFallbackURLStore")
    private var entries: [String: Entry]

    /// - Parameter directory: 缓存目录；nil 表示仅内存（测试用）。
    init(directory: URL? = nil) {
        guard let directory else {
            fileURL = nil
            entries = [:]
            return
        }
        let file = directory.appendingPathComponent("cover-fallback-cache.json")
        self.fileURL = file
        // 目录不存在时原子写会静默失败，必须显式创建。
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        if let data = try? Data(contentsOf: file),
           let decoded = try? JSONDecoder().decode([String: Entry].self, from: data) {
            entries = decoded
        } else {
            entries = [:]
        }
    }

    func url(forKey key: String) -> String? {
        queue.sync {
            guard let entry = entries[key] else { return nil }
            guard Date().timeIntervalSince(entry.fetchedAt) < Self.ttl else {
                entries.removeValue(forKey: key)
                saveLocked()
                return nil
            }
            return entry.url
        }
    }

    func store(_ urlString: String, forKey key: String, fetchedAt: Date = Date()) {
        queue.sync {
            entries[key] = Entry(url: urlString, fetchedAt: fetchedAt)
            saveLocked()
        }
    }

    /// 必须在持有 queue 时调用。
    private func saveLocked() {
        guard let fileURL,
              let data = try? JSONEncoder().encode(entries) else { return }
        try? data.write(to: fileURL, options: .atomic)
    }
}

/// 封面图片字节缓存：NSCache 内存层 + 磁盘目录层（按文件修改日期判 7 天过期）
/// + 网络回源。解决 AsyncImage 无磁盘缓存导致的返回结果页重新下载问题。
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
        self.directory = base
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        // ephemeral + 20s 超时：与 API 请求超时对齐，且不污染共享会话的 cookie/缓存语义。
        self.session = session ?? {
            let configuration = URLSessionConfiguration.ephemeral
            configuration.timeoutIntervalForRequest = 20
            return URLSession(configuration: configuration)
        }()
    }

    func image(for url: URL) async -> NSImage? {
        if let cached = memory.object(forKey: url as NSURL) {
            return cached
        }
        let file = diskFile(for: url)
        if let fresh = ioQueue.sync(execute: { freshDiskImage(at: file) }) {
            memory.setObject(fresh, forKey: url as NSURL)
            return fresh
        }

        guard let (data, response) = try? await session.data(from: url, delegate: nil),
              let httpResponse = response as? HTTPURLResponse,
              (200..<300).contains(httpResponse.statusCode),
              data.count <= Self.maxResponseSize,
              let image = NSImage(data: data) else {
            return nil
        }
        memory.setObject(image, forKey: url as NSURL)
        ioQueue.async { [directory] in
            try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try? data.write(to: file, options: .atomic)
        }
        return image
    }

    private func diskFile(for url: URL) -> URL {
        let digest = SHA256.hash(data: Data(url.absoluteString.utf8))
        let hex = digest.map { String(format: "%02x", $0) }.joined()
        return directory.appendingPathComponent(hex)
    }

    /// 过期文件就地删除并返回 nil；未过期则解码返回。
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
