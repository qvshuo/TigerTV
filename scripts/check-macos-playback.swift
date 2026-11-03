import Foundation
import AVFoundation

@main
struct PlaybackSmoke {
    @MainActor
    static func main() async throws {
        guard CommandLine.arguments.count > 1 else {
            fatalError("Usage: check-macos-playback <HLS URL> <MP4 URL>")
        }
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
            controller.player.pause()
            controller.savePosition()
            guard (positions.last ?? 0) >= 3_000 else { fatalError("Resume progress was not saved: \(url)") }
            let sought = await controller.player.seek(to: CMTime(seconds: 6, preferredTimescale: 600))
            guard sought, controller.player.currentTime().seconds >= 5 else { fatalError("Seek failed: \(url)") }
            controller.close()
            guard (positions.last ?? 0) >= 5_000 else { fatalError("Final progress was not saved: \(url)") }
            guard !controller.hasItem, controller.player.currentItem == nil else { fatalError("Player not released") }
            print("AVPlayer passed: resume, playback, pause, seek, progress, release — \(url)")
        }
    }
}
