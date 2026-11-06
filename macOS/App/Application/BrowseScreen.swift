import AVKit
import AppKit
import SwiftUI

struct BrowseScreen: View {
    @Binding var keyword: String
    let works: [VideoWork]
    let availableSources: [SearchResult]
    let episodes: [EpisodeLink]
    let selectedPlaybackSourceIndex: Int
    let fetchErrorMessage: String?
    let onRetryFetch: () -> Void
    let onSelectPlaybackSource: (Int) -> Void
    let isSearching: Bool
    let selectedResult: SearchResult?
    let fetchResponse: FetchResponse?
    let isFetching: Bool
    let player: AVPlayer?
    let selectedEpisode: EpisodeLink?
    let isResolvingPlayback: Bool
    let coverFallbacks: [String: URL]
    let onLoadCoverFallback: (SearchResult) async -> Void
    let onBack: () -> Void
    let onSearch: () -> Void
    let onSelectResult: (SearchResult) -> Void
    let onSelectEpisode: (EpisodeLink) -> Void
    var resumeEpisode: EpisodeLink? = nil
    var searchErrorMessage: String? = nil

    @State private var isFullscreen = false
    @State private var hostWindow: NSWindow?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var isVideoFullscreen: Bool {
        isFullscreen && player != nil
    }

    var body: some View {
        ZStack {
            if isVideoFullscreen {
                if let player {
                    PlayerContainer(player: player)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .background(.black)
                        .transition(.opacity)
                }
            } else {
                ZStack {
                    ResultsScreen(
                        works: works, isSearching: isSearching, selectedResult: selectedResult,
                        isFetching: isFetching, coverFallbacks: coverFallbacks,
                        onLoadCoverFallback: onLoadCoverFallback, onSearch: onSearch,
                        onSelectResult: onSelectResult, searchErrorMessage: searchErrorMessage
                    )
                    .opacity(selectedResult == nil ? 1 : 0)
                    .allowsHitTesting(selectedResult == nil)
                    .accessibilityHidden(selectedResult != nil)
                    if let selectedResult {
                        if let player {
                            PlayerContainer(player: player)
                        } else if isResolvingPlayback {
                            AppLoadingState(message: "正在准备播放…")
                        } else {
                            DetailScreen(
                                result: selectedResult, availableSources: availableSources, episodes: episodes,
                                selectedPlaybackSourceIndex: selectedPlaybackSourceIndex,
                                fetchErrorMessage: fetchErrorMessage, onRetryFetch: onRetryFetch,
                                onSelectPlaybackSource: onSelectPlaybackSource, fetchResponse: fetchResponse,
                                isFetching: isFetching, selectedEpisode: selectedEpisode,
                                coverFallbacks: coverFallbacks, onSelectResult: onSelectResult,
                                onSelectEpisode: onSelectEpisode, resumeEpisode: resumeEpisode
                            )
                        }
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .transition(.opacity)
            }
        }
        .toolbar {
            if !isVideoFullscreen {
                ToolbarItem(placement: .navigation) {
                    Button("返回", systemImage: "chevron.left", action: onBack)
                }
                ToolbarItem(placement: .principal) {
                    SearchBar(keyword: $keyword, onSearch: onSearch, compact: true, isSearching: isSearching)
                        .frame(minWidth: 280, idealWidth: 560, maxWidth: 560)
                }
                .sharedBackgroundVisibility(.hidden)
            }
        }
        .background(WindowAccessor(window: $hostWindow))
        .animation(reduceMotion ? nil : AppMotion.page, value: isVideoFullscreen)
        .onReceive(NotificationCenter.default.publisher(for: NSWindow.didEnterFullScreenNotification)) { notification in
            guard notification.object as? NSWindow === hostWindow else { return }
            isFullscreen = true
        }
        .onReceive(NotificationCenter.default.publisher(for: NSWindow.didExitFullScreenNotification)) { notification in
            guard notification.object as? NSWindow === hostWindow else { return }
            isFullscreen = false
        }
    }
}

private struct WindowAccessor: NSViewRepresentable {
    @Binding var window: NSWindow?

    func makeNSView(context: Context) -> NSView {
        let view = NSView()
        DispatchQueue.main.async {
            guard let host = view.window, window !== host else { return }
            window = host
        }
        return view
    }

    func updateNSView(_ view: NSView, context: Context) {
        DispatchQueue.main.async {
            guard let host = view.window, window !== host else { return }
            window = host
        }
    }
}
