import SwiftUI
import AVKit
import Combine

/// Owns the AVPlayer item across SwiftUI view updates.
@MainActor
final class AVPlayerController: ObservableObject {
    let player = AVPlayer()
    @Published private(set) var hasItem = false
    @Published private(set) var errorMessage: String?
    private var observers = Set<AnyCancellable>()

    func load(url: String) {
        guard let url = HTTPClient.percentEncodedURL(from: url) else { return }
        close()
        let asset = AVURLAsset(url: url, options: [AVURLAssetHTTPUserAgentKey: HTTPClient.userAgent])
        let item = AVPlayerItem(asset: asset)
        player.replaceCurrentItem(with: item)
        item.publisher(for: \.status)
            .receive(on: DispatchQueue.main)
            .sink { [weak self, weak item] status in
                MainActor.assumeIsolated {
                    guard status == .failed, let item, self?.player.currentItem === item else { return }
                    NSLog("Playback failed: %@", item.error?.localizedDescription ?? "Unknown error")
                    self?.errorMessage = "播放失败，请重试"
                }
            }.store(in: &observers)
        NotificationCenter.default.publisher(for: .AVPlayerItemFailedToPlayToEndTime, object: item)
            .receive(on: DispatchQueue.main)
            .sink { [weak self, weak item] notification in
                MainActor.assumeIsolated {
                    guard let item, self?.player.currentItem === item else { return }
                    NSLog("Playback interrupted: %@", String(describing: notification.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey]))
                    self?.errorMessage = "播放失败，请重试"
                }
            }.store(in: &observers)
        hasItem = true
    }
    func play() { player.play() }
    func close() {
        observers.removeAll()
        player.pause()
        player.replaceCurrentItem(with: nil)
        hasItem = false
        errorMessage = nil
    }
}

struct PlayerContainer: View {
    let player: AVPlayer

    var body: some View {
        VideoPlayer(player: player)
            .aspectRatio(16 / 9, contentMode: .fit)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
