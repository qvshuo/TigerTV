import AVKit
import SwiftUI

struct ContentView: View {
    @StateObject private var viewModel = TigerTVViewModel()

    @State private var hasSearched = false
    @StateObject private var controller = AVPlayerController()
    private var player: AVPlayer? { controller.hasItem ? controller.player : nil }
    @State private var showError = false

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var activeErrorMessage: String? {
        viewModel.playbackErrorMessage ?? controller.errorMessage
    }

    var body: some View {
        ZStack {
            if !hasSearched {
                HomeScreen(
                    keyword: $viewModel.keyword,
                    configErrorMessage: viewModel.configErrorMessage,
                    history: viewModel.searchHistory,
                    onSearch: startSearch,
                    onSelectHistory: selectHistory,
                    onDeleteHistory: viewModel.removeHistory,
                    onClearHistory: viewModel.clearHistory,
                    onRetryConfig: viewModel.retryLoadConfig,
                    hotSearches: viewModel.hotSearches,
                    hotCategories: viewModel.hotSearchCategories,
                    selectedHotCategory: viewModel.selectedHotCategory,
                    isLoadingHotSearches: viewModel.isLoadingHotSearches,
                    onSelectHotCategory: viewModel.selectHotSearchCategory,
                    onRetryHotSearches: viewModel.retryHotSearches
                )
                .task { await viewModel.loadHotSearchCategories() }
                .onDisappear(perform: viewModel.cancelHotSearchWarmup)
                .task(id: "\(viewModel.selectedHotCategory):\(viewModel.hotSearchRevision)") {
                    await viewModel.loadHotSearches()
                }
                .transition(
                    .asymmetric(
                        insertion: .opacity.combined(with: .scale(scale: 0.98)),
                        removal: .opacity.combined(with: .move(edge: .leading))
                    ))
            } else {
                ResultsLayout(
                    keyword: $viewModel.keyword,
                    submittedKeyword: viewModel.submittedKeyword,
                    works: viewModel.works,
                    availableSources: viewModel.availableSources,
                    episodes: viewModel.episodes,
                    selectedPlaybackSourceIndex: viewModel.selectedPlaybackSourceIndex,
                    fetchErrorMessage: viewModel.fetchErrorMessage,
                    onRetryFetch: viewModel.retryFetch,
                    onSelectPlaybackSource: { index in
                        controller.close()
                        viewModel.selectPlaybackSource(index)
                    },
                    isSearching: viewModel.isSearching,
                    selectedResult: viewModel.selectedResult,
                    fetchResponse: viewModel.fetchResponse,
                    isFetching: viewModel.isFetching,
                    player: player,
                    selectedEpisode: viewModel.selectedEpisode,
                    isResolvingPlayback: viewModel.isResolvingPlayback,
                    coverFallbacks: viewModel.coverFallbacks,
                    onLoadCoverFallback: viewModel.loadCoverFallbackIfPossible,
                    onBack: goBack,
                    onSearch: startSearch,
                    onSelectResult: selectResult,
                    onSelectEpisode: selectEpisode,
                    resumeEpisode: viewModel.resumeEpisode,
                    searchErrorMessage: viewModel.searchErrorMessage
                )
                .transition(
                    .asymmetric(
                        insertion: .opacity.combined(with: .move(edge: .trailing)),
                        removal: .opacity.combined(with: .scale(scale: 0.98))
                    ))
            }
        }
        .background(.background)
        .animation(reduceMotion ? nil : AppMotion.page, value: hasSearched)
        .alert(
            "提示", isPresented: $showError,
            actions: {
                if viewModel.selectedEpisode != nil {
                    Button("重试") {
                        controller.close()
                        viewModel.retryPlayback()
                    }
                    Button("返回", role: .cancel) {
                        viewModel.clearPlayback()
                        controller.close()
                    }
                } else {
                    Button("确定") { viewModel.clearActiveError() }
                }
            },
            message: {
                Text(activeErrorMessage ?? "播放失败，请重试")
            }
        )
        .onDisappear { controller.close() }
        .onChange(of: activeErrorMessage) { _, newValue in
            showError = newValue != nil
        }
        .onReceive(controller.player.publisher(for: \.timeControlStatus)) { status in
            guard status == .playing,
                let asset = controller.player.currentItem?.asset as? AVURLAsset
            else { return }
            viewModel.playbackDidStart(url: asset.url.absoluteString)
        }
        .onChange(of: viewModel.playbackRevision) { _, _ in
            guard let url = viewModel.resolvedPlaybackUrl else { return }
            controller.close()
            controller.load(url: url)
            controller.play()
        }
    }

    private func startSearch() {
        let trimmed = viewModel.keyword.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        hasSearched = true
        controller.close()
        viewModel.search()
    }

    private func selectHistory(_ item: String) {
        viewModel.keyword = item
        startSearch()
    }

    private func selectResult(_ result: SearchResult) {
        controller.close()
        viewModel.selectResult(result)
    }

    private func selectEpisode(_ episode: EpisodeLink) {
        controller.close()
        viewModel.selectEpisode(episode)
    }

    private func goBack() {
        if player != nil || viewModel.selectedEpisode != nil || viewModel.isResolvingPlayback {
            viewModel.clearPlayback()
            controller.close()
            return
        }

        if viewModel.selectedResult != nil || viewModel.fetchResponse != nil || viewModel.isFetching {
            viewModel.clearFetch()
            return
        }

        if !viewModel.works.isEmpty || viewModel.isSearching {
            viewModel.clearResults()
            hasSearched = false
            return
        }

        hasSearched = false
    }
}
