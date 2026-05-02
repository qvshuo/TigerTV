import SwiftUI
import AVKit
import AppKit

struct ResultsLayout: View {
    @Binding var keyword: String
    let submittedKeyword: String
    let results: [SearchResult]
    let isSearching: Bool
    let selectedResult: SearchResult?
    let pendingFetchResult: SearchResult?
    let fetchResponse: FetchResponse?
    let isFetching: Bool
    let player: AVPlayer?
    let selectedEpisode: EpisodeLink?
    let isResolvingPlayback: Bool
    let onBack: () -> Void
    let onSearch: () -> Void
    let onSelectResult: (SearchResult) -> Void
    let onSelectEpisode: (EpisodeLink) -> Void

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
                VStack(spacing: 0) {
                    HStack(spacing: AppSpacing.sm) {
                        Button(action: onBack) {
                            Image(systemName: "chevron.left")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(.primary)
                                .frame(width: 32, height: 32)
                                .background(
                                    Circle()
                                        .fill(Color.secondary.opacity(0.1))
                                )
                        }
                        .buttonStyle(.plain)
                        .hoverLift()

                        GlassSearchBar(keyword: $keyword, onSearch: onSearch, compact: true)

                        Spacer()
                    }
                    .padding(AppSpacing.md)
                    .background(.ultraThinMaterial)
                    .overlay(alignment: .bottom) {
                        Rectangle()
                            .fill(Color.secondary.opacity(0.1))
                            .frame(height: 1)
                    }

                    HStack(spacing: 0) {
                        leftContent
                            .frame(maxWidth: .infinity, maxHeight: .infinity)

                        if isFetching, let pendingFetchResult {
                            EpisodeLoadingPanel(result: pendingFetchResult)
                                .frame(minWidth: 280, idealWidth: 320, maxWidth: 360)
                                .overlay(
                                    Rectangle()
                                        .fill(Color.secondary.opacity(0.1))
                                        .frame(width: 1)
                                        .frame(maxHeight: .infinity),
                                    alignment: .leading
                                )
                                .transition(.opacity)
                        } else if let response = fetchResponse {
                            EpisodePanel(
                                response: response,
                                selectedEpisode: selectedEpisode,
                                onSelect: onSelectEpisode
                            )
                            .frame(minWidth: 280, idealWidth: 320, maxWidth: 360)
                            .overlay(
                                Rectangle()
                                    .fill(Color.secondary.opacity(0.1))
                                    .frame(width: 1)
                                    .frame(maxHeight: .infinity),
                                alignment: .leading
                            )
                            .transition(.move(edge: .trailing).combined(with: .opacity))
                        }
                    }
                }
                .transition(.opacity)
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

    @ViewBuilder
    private var leftContent: some View {
        if let player {
            PlayerContainer(player: player)
        } else if isResolvingPlayback {
            VStack(spacing: AppSpacing.md) {
                Spacer()
                ProgressView("正在解析播放地址...")
                Spacer()
            }
        } else if isSearching {
            VStack(spacing: AppSpacing.md) {
                Spacer()
                ProgressView()
                Text("正在搜索「\(submittedKeyword)」...")
                    .foregroundStyle(.secondary)
                Spacer()
            }
        } else if results.isEmpty {
            ContentUnavailableView("未找到结果", systemImage: "magnifyingglass")
                .font(.title3)
        } else {
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 260), spacing: AppSpacing.md)], spacing: AppSpacing.md) {
                    ForEach(results) { result in
                        SearchResultCard(
                            result: result,
                            isSelected: selectedResult?.id == result.id,
                            isLoading: selectedResult?.id == result.id && isFetching,
                            onTap: { onSelectResult(result) }
                        )
                    }
                }
                .padding(AppSpacing.md)
            }
        }
    }
}

private struct WindowAccessor: NSViewRepresentable {
    @Binding var window: NSWindow?

    func makeNSView(context: Context) -> NSView {
        let view = NSView()
        DispatchQueue.main.async {
            window = view.window
        }
        return view
    }

    func updateNSView(_ view: NSView, context: Context) {
        DispatchQueue.main.async {
            window = view.window
        }
    }
}
