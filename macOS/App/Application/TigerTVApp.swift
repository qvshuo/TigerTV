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

/// Flush search history and playback writes before a normal Quit.
@MainActor
final class TigerTVAppDelegate: NSObject, NSApplicationDelegate, ObservableObject {
    var persistenceFlushers: [UUID: @MainActor () async -> Void] = [:]

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        let flushers = Array(persistenceFlushers.values)
        guard !flushers.isEmpty else { return .terminateNow }
        Task {
            for flush in flushers { await flush() }
            sender.reply(toApplicationShouldTerminate: true)
        }
        return .terminateLater
    }
}
