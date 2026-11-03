import SwiftUI

@main
struct TigerTVApp: App {
    @NSApplicationDelegateAdaptor(TigerTVAppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView().environmentObject(appDelegate)
        }
        .defaultSize(width: 1200, height: 780)
    }
}

/// Give pending native persistence requests time to finish before a normal Quit.
@MainActor
final class TigerTVAppDelegate: NSObject, NSApplicationDelegate, ObservableObject {
    var playbackFlushers: [UUID: @MainActor () async -> Void] = [:]

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        let flushers = Array(playbackFlushers.values)
        guard !flushers.isEmpty else { return .terminateNow }
        Task {
            for flush in flushers { await flush() }
            sender.reply(toApplicationShouldTerminate: true)
        }
        return .terminateLater
    }
}
