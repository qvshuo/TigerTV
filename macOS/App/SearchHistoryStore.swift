import Foundation

// UserDefaults 本身线程安全，存储的均为不可变 `let`，`@unchecked Sendable` 是诚实的：
// 之所以不改为 actor，是因为 UserDefaults 非 Sendable，传入 actor init 会触发
// Swift 6 严格并发检查（调用方在非隔离上下文无法 send 非 Sendable 值）。
final class SearchHistoryStore: @unchecked Sendable {
    private let defaults: UserDefaults
    private let key: String
    private let maxItems: Int

    init(
        defaults: UserDefaults = .standard,
        key: String = "tigertv.searchHistory",
        maxItems: Int = 20
    ) {
        self.defaults = defaults
        self.key = key
        self.maxItems = maxItems
    }

    func load() async -> [String] {
        guard let string = defaults.string(forKey: key),
              let data = string.data(using: .utf8) else { return [] }
        do {
            return try JSONDecoder().decode([String].self, from: data)
        } catch {
            return []
        }
    }

    func save(_ history: [String]) async {
        let trimmed = Array(history.prefix(maxItems))
        do {
            let data = try JSONEncoder().encode(trimmed)
            guard let string = String(data: data, encoding: .utf8) else { return }
            defaults.set(string, forKey: key)
        } catch {
            // ignore
        }
    }
}
