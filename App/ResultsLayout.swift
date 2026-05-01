import SwiftUI
import AVKit

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
                }
            } else {
                VStack(spacing: 0) {
                    HStack(spacing: 12) {
                        Button(action: onBack) {
                            Image(systemName: "chevron.left")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(.primary)
                                .frame(width: 32, height: 32)
                                .background(
                                    Circle()
                                        .fill(Color.secondary.opacity(0.12))
                                )
                        }
                        .buttonStyle(.plain)

                        GlassSearchBar(keyword: $keyword, onSearch: onSearch, compact: true)

                        Spacer()
                    }
                    .padding()
                    .background(.ultraThinMaterial)

                    HStack(spacing: 0) {
                        leftContent
                            .frame(maxWidth: .infinity, maxHeight: .infinity)

                        if let response = fetchResponse {
                            EpisodePanel(
                                response: response,
                                selectedEpisode: selectedEpisode,
                                onSelect: onSelectEpisode
                            )
                            .frame(width: 300)
                        } else if isFetching, let pendingFetchResult {
                            EpisodeLoadingPanel(result: pendingFetchResult)
                                .frame(width: 300)
                        }
                    }
                }
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: NSWindow.didEnterFullScreenNotification)) { _ in
            isFullscreen = true
        }
        .onReceive(NotificationCenter.default.publisher(for: NSWindow.didExitFullScreenNotification)) { _ in
            isFullscreen = false
        }
    }

    @ViewBuilder
    private var leftContent: some View {
        if let player {
            PlayerContainer(player: player)
        } else if isResolvingPlayback {
            VStack(spacing: 16) {
                Spacer()
                ProgressView("正在解析播放地址...")
                Spacer()
            }
        } else if isSearching {
            VStack(spacing: 16) {
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
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 260), spacing: 16)], spacing: 16) {
                    ForEach(results) { result in
                        SearchResultCard(
                            result: result,
                            isSelected: selectedResult?.id == result.id,
                            isLoading: selectedResult?.id == result.id && isFetching,
                            onTap: { onSelectResult(result) }
                        )
                    }
                }
                .padding()
            }
        }
    }
}
