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
            controller.load(url: url)
            controller.player.volume = 0
            controller.play()
            var advancing = false
            for _ in 0..<100 {
                if let error = controller.player.currentItem?.error { throw error }
                if controller.player.currentTime().seconds > 0.2 { advancing = true; break }
                try await Task.sleep(for: .milliseconds(100))
            }
            guard advancing else { fatalError("Playback did not advance: \(url)") }
            controller.player.pause()
            let sought = await controller.player.seek(to: CMTime(seconds: 6, preferredTimescale: 600))
            guard sought, controller.player.currentTime().seconds >= 5 else { fatalError("Seek failed: \(url)") }
            controller.close()
            guard !controller.hasItem, controller.player.currentItem == nil else { fatalError("Player not released") }
            print("AVPlayer passed: playback, pause, seek, release — \(url)")
        }
    }
}
