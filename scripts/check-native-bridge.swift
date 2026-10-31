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
        let categories = try await request(#"{"version":1,"operation":"hotSearchCategories"}"#)
        let categoryEnvelope = try JSONSerialization.jsonObject(with: Data(categories.utf8)) as? [String: Any]
        let categoryList = categoryEnvelope?["data"] as? [[String: String]]
        guard categoryList?.compactMap({ $0["id"] }) == ["chinese", "tvshow", "american", "korean", "japanese", "animation"] else {
            fatalError(categories)
        }
        let missingCategory = try await request(#"{"version":1,"operation":"hotSearches"}"#)
        guard missingCategory.contains("error") else { fatalError("Missing category was accepted") }
        _ = try await request(#"{"version":1,"operation":"saveHistory","items":["逐玉🎬"]}"#)
        let response = try await request(#"{"version":1,"operation":"history"}"#)
        guard response.contains("逐玉🎬") else { fatalError(response) }
        _ = try await request(#"{"version":1,"operation":"recordPlayback","work_id":"work","site":"🎬一","line":"0","episode":"第02集"}"#)
        _ = try await request(#"{"version":1,"operation":"recordPlayback","work_id":"work","site":"🎬二","line":"2","episode":"02"}"#)
        _ = try await request(#"{"version":1,"operation":"recordPlayback","work_id":"work","site":"🎬一","line":"0","episode":"第03集"}"#)
        let saved = try JSONSerialization.jsonObject(with: Data(contentsOf: directory.appendingPathComponent("last-played-sources.json"))) as? [String: Any]
        let works = saved?["byWork"] as? [String: [String: Any]]
        let resources = works?["work"]?["resources"] as? [String: [String: String]]
        guard resources?["🎬一"]?["episode"] == "第03集",
              resources?["🎬二"]?["episode"] == "02",
              works?["work"]?["site"] as? String == "🎬一" else { fatalError("Resource bookmarks were not preserved") }
        let id = facade.begin(request: #"{"version":1,"operation":"history"}"#)
        facade.cancel(id: id)
        guard facade.takeResult(id: id) == nil else { fatalError("Cancelled request leaked") }
        print("Swift → Kotlin/Native bridge passed: Unicode, per-resource bookmarks, persistence, cancellation, release")
    }
}
