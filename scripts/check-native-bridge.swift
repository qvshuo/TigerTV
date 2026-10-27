import Foundation
import TigerTVCore

@main
struct BridgeSmoke {
    static func main() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let facade = NativeFacade(directory: directory.path)
        defer { facade.close() }
        func request(_ text: String) async throws -> String {
            let id = facade.begin(request: text)
            defer { facade.cancel(id: id) }
            for _ in 0..<500 {
                if let value = facade.takeResult(id: id) { return value }
                try await Task.sleep(for: .milliseconds(10))
            }
            throw NSError(domain: "BridgeSmoke", code: 1)
        }
        _ = try await request(#"{"version":1,"operation":"saveHistory","items":["逐玉🎬"]}"#)
        let response = try await request(#"{"version":1,"operation":"history"}"#)
        guard response.contains("逐玉🎬") else { fatalError(response) }
        let id = facade.begin(request: #"{"version":1,"operation":"history"}"#)
        facade.cancel(id: id)
        guard facade.takeResult(id: id) == nil else { fatalError("Cancelled request leaked") }
        print("Swift → Kotlin/Native bridge passed: Unicode, persistence, cancellation, release")
    }
}
