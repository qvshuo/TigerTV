import AVKit
import SwiftUI

struct PlayerContainer: View {
    let player: AVPlayer

    var body: some View {
        NativePlayerView(player: player)
            .aspectRatio(16 / 9, contentMode: .fit)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct NativePlayerView: NSViewRepresentable {
    let player: AVPlayer

    func makeNSView(context: Context) -> KeyboardPlayerView {
        let view = KeyboardPlayerView()
        view.player = player
        return view
    }

    func updateNSView(_ view: KeyboardPlayerView, context: Context) {
        if view.player !== player { view.player = player }
    }

    static func dismantleNSView(_ view: KeyboardPlayerView, coordinator: ()) {
        view.stopHandlingKeys()
        view.player = nil
    }
}

final class KeyboardPlayerView: AVPlayerView {
    private weak var seekItem: AVPlayerItem?
    private var seekTarget: Double?
    private var seekRevision = 0
    private var keyMonitor: Any?

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        stopHandlingKeys()
        guard window != nil else { return }
        // AVKit's internal controls can consume arrows before the responder chain.
        keyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            let handled = MainActor.assumeIsolated {
                guard let self, let window = self.window, event.window === window,
                    let responder = window.firstResponder as? NSView,
                    responder === self || responder.isDescendant(of: self) else { return false }
                return self.seek(with: event)
            }
            return handled ? nil : event
        }
    }

    func stopHandlingKeys() {
        if let keyMonitor { NSEvent.removeMonitor(keyMonitor) }
        keyMonitor = nil
        seekItem = nil
        seekTarget = nil
        seekRevision += 1
    }

    private func seek(with event: NSEvent) -> Bool {
        guard event.type == .keyDown, event.keyCode == 123 || event.keyCode == 124,
            event.modifierFlags.intersection([.command, .control, .option, .shift]).isEmpty,
            let player, let item = player.currentItem, item.status == .readyToPlay else { return false }
        let current = seekItem === item ? (seekTarget ?? player.currentTime().seconds) : player.currentTime().seconds
        guard current.isFinite else { return false }
        let duration = item.duration.seconds
        let target = max(0, min(current + (event.keyCode == 123 ? -10 : 10), duration.isFinite ? duration : .infinity))
        seekItem = item
        seekTarget = target
        seekRevision += 1
        let revision = seekRevision
        // Seek directly: AVPlayerView's default arrow handling pauses to step frames.
        player.seek(to: CMTime(seconds: target, preferredTimescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero) { [weak self, weak item] _ in
            Task { @MainActor in
                guard let self, let item, self.seekRevision == revision, self.player?.currentItem === item else { return }
                self.seekTarget = nil
            }
        }
        return true
    }
}
