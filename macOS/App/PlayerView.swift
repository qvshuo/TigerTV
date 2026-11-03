import SwiftUI
import AVKit
import Combine
import AppKit

/// Owns the AVPlayer item across SwiftUI view updates.
@MainActor
final class AVPlayerController: ObservableObject {
    let player = AVPlayer()
    @Published private(set) var hasItem = false
    @Published private(set) var errorMessage: String?
    private var observers = Set<AnyCancellable>()
    private var timeObserver: Any?
    private var didStart = false
    private var restoring = false
    private var ended = false
    private var onPosition: ((Int64) -> Void)?
    private var lastSavedPosition: Int64?

    func load(url: String, positionMillis: Int64, onStarted: @escaping () -> Void, onPosition: @escaping (Int64) -> Void) {
        close()
        guard let url = HTTPClient.percentEncodedURL(from: url) else { return }
        self.onPosition = onPosition
        restoring = true
        let asset = AVURLAsset(url: url, options: [AVURLAssetHTTPUserAgentKey: HTTPClient.userAgent])
        let item = AVPlayerItem(asset: asset)
        player.replaceCurrentItem(with: item)
        item.publisher(for: \.status)
            .receive(on: DispatchQueue.main)
            .sink { [weak self, weak item] status in
                MainActor.assumeIsolated {
                    guard let self, let item, self.player.currentItem === item else { return }
                    if status == .failed {
                        self.savePosition()
                        NSLog("Playback failed: %@", item.error?.localizedDescription ?? "Unknown error")
                        self.errorMessage = "播放失败，请重试"
                    } else if status == .readyToPlay && self.restoring {
                        let duration = item.duration.seconds
                        let seconds = Double(max(0, positionMillis)) / 1000
                        let target = duration.isFinite && seconds >= duration ? 0 : seconds
                        self.player.seek(to: CMTime(seconds: target, preferredTimescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero) { [weak self, weak item] finished in
                            Task { @MainActor in
                                guard let self, let item, self.player.currentItem === item else { return }
                                if !finished {
                                    _ = await self.player.seek(to: .zero)
                                    guard self.player.currentItem === item else { return }
                                }
                                self.restoring = false
                                self.player.play()
                            }
                        }
                    }
                }
            }.store(in: &observers)
        player.publisher(for: \.timeControlStatus)
            .receive(on: DispatchQueue.main)
            .sink { [weak self, weak item] status in
                MainActor.assumeIsolated {
                    guard let self, let item, self.player.currentItem === item, !self.restoring else { return }
                    if status == .playing && !self.didStart {
                        self.didStart = true
                        onStarted()
                    } else if status == .paused {
                        self.savePosition()
                    }
                }
            }.store(in: &observers)
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 5, preferredTimescale: 1000), queue: .main) { [weak self, weak item] _ in
            MainActor.assumeIsolated {
                guard let self, let item, self.player.currentItem === item else { return }
                self.savePosition()
            }
        }
        NotificationCenter.default.publisher(for: .AVPlayerItemTimeJumped, object: item)
            .receive(on: DispatchQueue.main)
            .sink { [weak self, weak item] _ in
                MainActor.assumeIsolated {
                    guard let self, let item, self.player.currentItem === item else { return }
                    if self.ended && self.player.currentTime() < item.duration { self.ended = false }
                    self.savePosition()
                }
            }.store(in: &observers)
        NotificationCenter.default.publisher(for: .AVPlayerItemDidPlayToEndTime, object: item)
            .receive(on: DispatchQueue.main)
            .sink { [weak self, weak item] _ in
                MainActor.assumeIsolated {
                    guard let self, let item, self.player.currentItem === item, self.didStart else { return }
                    self.ended = true
                    self.lastSavedPosition = 0
                    self.onPosition?(0)
                }
            }.store(in: &observers)
        NotificationCenter.default.publisher(for: .AVPlayerItemFailedToPlayToEndTime, object: item)
            .receive(on: DispatchQueue.main)
            .sink { [weak self, weak item] notification in
                MainActor.assumeIsolated {
                    guard let self, let item, self.player.currentItem === item else { return }
                    self.savePosition()
                    NSLog("Playback interrupted: %@", String(describing: notification.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey]))
                    self.errorMessage = "播放失败，请重试"
                }
            }.store(in: &observers)
        for name in [NSApplication.didResignActiveNotification, NSApplication.willTerminateNotification] {
            NotificationCenter.default.publisher(for: name)
                .receive(on: DispatchQueue.main)
                .sink { [weak self] _ in MainActor.assumeIsolated { self?.savePosition() } }
                .store(in: &observers)
        }
        hasItem = true
    }

    func savePosition() {
        guard didStart, !restoring, !ended else { return }
        let seconds = player.currentTime().seconds
        guard seconds.isFinite, seconds >= 0, seconds < Double(Int64.max) / 1000 else { return }
        let position = Int64(seconds * 1000)
        guard position != lastSavedPosition else { return }
        lastSavedPosition = position
        onPosition?(position)
    }

    func close() {
        savePosition()
        if let timeObserver { player.removeTimeObserver(timeObserver) }
        timeObserver = nil
        observers.removeAll()
        onPosition = nil
        lastSavedPosition = nil
        didStart = false
        restoring = false
        ended = false
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
