import SwiftUI
import AVKit

struct ContentView: View {
    @StateObject private var viewModel = TigerTVViewModel()

    @State private var hasSearched = false
    @State private var player: AVPlayer?
    @State private var showError = false

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var activeErrorMessage: String? {
        viewModel.searchErrorMessage ?? viewModel.fetchErrorMessage ?? viewModel.playbackErrorMessage
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
                    onRetryConfig: viewModel.retryLoadConfig
                )
                .transition(.asymmetric(
                    insertion: .opacity.combined(with: .scale(scale: 0.98)),
                    removal: .opacity.combined(with: .move(edge: .leading))
                ))
            } else {
                ResultsLayout(
                    keyword: $viewModel.keyword,
                    submittedKeyword: viewModel.submittedKeyword,
                    results: viewModel.results,
                    isSearching: viewModel.isSearching,
                    selectedResult: viewModel.selectedResult,
                    fetchResponse: viewModel.fetchResponse,
                    isFetching: viewModel.isFetching,
                    player: player,
                    selectedEpisode: viewModel.selectedEpisode,
                    isResolvingPlayback: viewModel.isResolvingPlayback,
                    onBack: goBack,
                    onSearch: startSearch,
                    onSelectResult: selectResult,
                    onSelectEpisode: selectEpisode
                )
                .transition(.asymmetric(
                    insertion: .opacity.combined(with: .move(edge: .trailing)),
                    removal: .opacity.combined(with: .scale(scale: 0.98))
                ))
            }
        }
        .animation(reduceMotion ? nil : AppMotion.page, value: hasSearched)
        .alert("错误", isPresented: $showError, actions: {
            Button("确定") {
                viewModel.clearActiveError()
            }
        }, message: {
            Text(activeErrorMessage ?? "未知错误")
        })
        .onChange(of: activeErrorMessage) { _, newValue in
            showError = newValue != nil
        }
        .onChange(of: viewModel.resolvedPlaybackUrl) { _, url in
            guard let url, let playbackURL = URL(string: url) else { return }
            player?.pause()
            let newPlayer = AVPlayer(url: playbackURL)
            player = newPlayer
            newPlayer.play()
        }
    }

    private func startSearch() {
        let trimmed = viewModel.keyword.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        hasSearched = true
        player?.pause()
        player = nil
        viewModel.search()
    }

    private func selectHistory(_ item: String) {
        viewModel.keyword = item
        startSearch()
    }

    private func selectResult(_ result: SearchResult) {
        player?.pause()
        player = nil
        viewModel.selectResult(result)
    }

    private func selectEpisode(_ episode: EpisodeLink) {
        player?.pause()
        player = nil
        viewModel.selectEpisode(episode)
    }

    private func goBack() {
        if player != nil || viewModel.selectedEpisode != nil || viewModel.isResolvingPlayback {
            viewModel.clearPlayback()
            player?.pause()
            player = nil
            return
        }

        if viewModel.selectedResult != nil || viewModel.fetchResponse != nil || viewModel.isFetching {
            viewModel.clearFetch()
            return
        }

        if !viewModel.results.isEmpty || viewModel.isSearching {
            viewModel.clearResults()
            hasSearched = false
            return
        }

        hasSearched = false
    }
}
