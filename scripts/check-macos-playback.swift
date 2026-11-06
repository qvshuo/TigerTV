import Foundation
import AVFoundation
import AppKit

@main
struct PlaybackSmoke {
    @MainActor
    static func main() async throws {
        guard CommandLine.arguments.count > 1 else {
            fatalError("Usage: check-macos-playback <HLS URL> <MP4 URL>")
        }
        _ = NSApplication.shared
        for url in CommandLine.arguments.dropFirst() {
            let controller = AVPlayerController()
            var started = false
            var positions: [Int64] = []
            controller.load(url: url, positionMillis: 3_000, onStarted: { started = true }, onPosition: { positions.append($0) })
            controller.player.volume = 0
            var advancing = false
            for _ in 0..<100 {
                if let error = controller.player.currentItem?.error { throw error }
                if started && controller.player.currentTime().seconds > 3.2 { advancing = true; break }
                try await Task.sleep(for: .milliseconds(100))
            }
            guard advancing else { fatalError("Playback did not advance: \(url)") }
            let duration = controller.player.currentItem?.duration.seconds ?? 0
            guard duration.isFinite, duration >= 45 else { fatalError("Keyboard checks require a VOD fixture of at least 45 seconds: \(url)") }
            let view = KeyboardPlayerView()
            view.player = controller.player
            let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 640, height: 360),
                styleMask: [.titled], backing: .buffered, defer: false)
            window.isReleasedWhenClosed = false
            window.contentView = view
            guard window.makeFirstResponder(view) else { fatalError("Player did not receive keyboard focus") }
            await checkArrow(124, in: window, player: controller.player, playing: true)
            await checkArrow(123, in: window, player: controller.player, playing: true)
            controller.player.rate = 1.5
            await checkArrow(124, in: window, player: controller.player, playing: true)
            guard controller.player.rate == 1.5 else { fatalError("Arrow changed playback speed") }
            controller.player.rate = 1
            // Repeated key events must accumulate their targets while the first seek is pending.
            let repeatedTarget = controller.player.currentTime().seconds + 20
            sendArrow(124, in: window)
            sendArrow(124, in: window, repeatKey: true)
            for _ in 0..<50 {
                if abs(controller.player.currentTime().seconds - repeatedTarget) < 1 { break }
                try await Task.sleep(for: .milliseconds(100))
            }
            guard abs(controller.player.currentTime().seconds - repeatedTarget) < 1 else { fatalError("Repeated arrows lost a seek") }
            try await Task.sleep(for: .milliseconds(300))
            guard controller.player.rate > 0 else { fatalError("Repeated arrows paused playback") }
            controller.player.pause()
            await checkArrow(123, in: window, player: controller.player, playing: false)
            await checkArrow(124, in: window, player: controller.player, playing: false)
            let field = NSTextField(frame: NSRect(x: 0, y: 0, width: 200, height: 24))
            let root = NSView(frame: view.frame)
            window.contentView = root
            root.addSubview(view)
            root.addSubview(field)
            guard window.makeFirstResponder(field) else { fatalError("Input did not receive focus") }
            let pausedPosition = controller.player.currentTime().seconds
            sendArrow(123, in: window)
            try await Task.sleep(for: .milliseconds(200))
            guard abs(controller.player.currentTime().seconds - pausedPosition) < 0.1 else { fatalError("Input arrow sought the player") }
            controller.savePosition()
            guard (positions.last ?? 0) >= 3_000 else { fatalError("Resume progress was not saved: \(url)") }
            guard window.makeFirstResponder(view) else { fatalError("Player focus was not restored") }
            _ = await controller.player.seek(to: CMTime(seconds: 1, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
            await checkArrow(123, in: window, player: controller.player, playing: false)
            _ = await controller.player.seek(to: CMTime(seconds: duration - 1, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
            await checkArrow(124, in: window, player: controller.player, playing: false)
            let sought = await controller.player.seek(to: CMTime(seconds: 6, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
            guard sought, controller.player.currentTime().seconds >= 5 else { fatalError("Seek failed: \(url)") }
            view.stopHandlingKeys()
            window.contentView = nil
            window.close()
            controller.close()
            guard (positions.last ?? 0) >= 5_000 else { fatalError("Final progress was not saved: \(url)") }
            guard !controller.hasItem, controller.player.currentItem == nil else { fatalError("Player not released") }
            print("AVPlayer passed: resume, playback, keyboard seek while playing/paused, repeat, progress, release — \(url)")
        }
    }

    @MainActor
    private static func sendArrow(_ code: UInt16, in window: NSWindow, repeatKey: Bool = false) {
        let character = code == 123 ? "\u{F702}" : "\u{F703}"
        for type in [NSEvent.EventType.keyDown, .keyUp] {
            let event = NSEvent.keyEvent(with: type, location: .zero, modifierFlags: [], timestamp: 0,
                windowNumber: window.windowNumber, context: nil, characters: character,
                charactersIgnoringModifiers: character, isARepeat: repeatKey, keyCode: code)!
            NSApplication.shared.sendEvent(event)
        }
    }

    @MainActor
    private static func checkArrow(_ code: UInt16, in window: NSWindow, player: AVPlayer, playing: Bool) async {
        let before = player.currentTime().seconds
        let duration = player.currentItem?.duration.seconds ?? .infinity
        let target = max(0, min(before + (code == 123 ? -10 : 10), duration))
        sendArrow(code, in: window)
        for _ in 0..<50 {
            if abs(player.currentTime().seconds - target) < 1 { break }
            try? await Task.sleep(for: .milliseconds(100))
        }
        guard abs(player.currentTime().seconds - target) < 1 else { fatalError("Arrow \(code) did not seek ten seconds: \(before) -> \(player.currentTime().seconds), target \(target), responder \(String(describing: window.firstResponder))") }
        try? await Task.sleep(for: .milliseconds(300))
        guard playing ? player.rate > 0 : player.rate == 0 else { fatalError("Arrow changed playback intent") }
        if playing {
            let position = player.currentTime().seconds
            try? await Task.sleep(for: .milliseconds(300))
            guard player.currentTime().seconds > position else { fatalError("Playback did not advance after arrow seek") }
        }
    }
}
