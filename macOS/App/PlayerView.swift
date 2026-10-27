import SwiftUI
import AVKit

/// Mirrors core.PlayerController while keeping the AVKit surface native.
@MainActor
final class AVPlayerController: ObservableObject {
    let player = AVPlayer()
    @Published private(set) var hasItem = false

    func load(url: String) {
        guard let url = HTTPClient.percentEncodedURL(from: url) else { return }
        player.replaceCurrentItem(with: AVPlayerItem(url: url))
        hasItem = true
    }
    func play() { player.play() }
    func pause() { player.pause() }
    func seek(positionMillis: Int64) {
        player.seek(to: CMTime(value: max(0, positionMillis), timescale: 1000))
    }
    func setVolume(_ volume: Float) { player.volume = min(1, max(0, volume)) }
    func close() { player.pause(); player.replaceCurrentItem(with: nil); hasItem = false }
}

struct PlayerContainer: View {
    let player: AVPlayer

    var body: some View {
        VideoPlayer(player: player)
            .aspectRatio(16 / 9, contentMode: .fit)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
