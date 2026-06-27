import SwiftUI
import AVKit

// MARK: - ContentView

struct ContentView: View {
    @StateObject private var client = TigerTVClient()
    @AppStorage("searchHistory") private var searchHistoryData = "[]"

    @State private var keyword = ""
    @State private var submittedKeyword = ""
    @State private var results: [SearchResult] = []
    @State private var isSearching = false
    @State private var searchID = UUID()
    @State private var searchTask: Task<Void, Never>?

    @State private var selectedResult: SearchResult?
    @State private var pendingFetchResult: SearchResult?
    @State private var fetchResponse: FetchResponse?
    @State private var isFetching = false
    @State private var fetchID = UUID()
    @State private var fetchTask: Task<Void, Never>?

    @State private var selectedEpisode: EpisodeLink?
    @State private var player: AVPlayer?
    @State private var isResolvingPlayback = false
    @State private var playbackID = UUID()
    @State private var playbackTask: Task<Void, Never>?
    @State private var showError = false
    @State private var hasSearched = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var searchHistory: [String] {
        (try? JSONDecoder().decode([String].self, from: Data(searchHistoryData.utf8))) ?? []
    }

    var body: some View {
        ZStack {
            if !hasSearched {
                HomeScreen(
                    keyword: $keyword,
                    history: searchHistory,
                    onSearch: startSearch,
                    onSelectHistory: selectHistory,
                    onDeleteHistory: deleteHistory,
                    onClearHistory: clearHistory
                )
                .transition(.asymmetric(
                    insertion: .opacity.combined(with: .scale(scale: 0.98)),
                    removal: .opacity.combined(with: .move(edge: .leading))
                ))
            } else {
                ResultsLayout(
                    keyword: $keyword,
                    submittedKeyword: submittedKeyword,
                    results: results,
                    isSearching: isSearching,
                    selectedResult: selectedResult,
                    pendingFetchResult: pendingFetchResult,
                    fetchResponse: fetchResponse,
                    isFetching: isFetching,
                    player: player,
                    selectedEpisode: selectedEpisode,
                    isResolvingPlayback: isResolvingPlayback,
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
        .environmentObject(client)
        .alert("错误", isPresented: $showError, actions: {
            Button("确定") { client.errorMessage = nil }
        }, message: {
            Text(client.errorMessage ?? "未知错误")
        })
        .onChange(of: client.errorMessage) { _, newValue in
            showError = newValue != nil
        }
    }

    private func startSearch() {
        let trimmed = keyword.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }

        hasSearched = true
        addHistory(trimmed)
        submittedKeyword = trimmed
        searchTask?.cancel()
        fetchTask?.cancel()
        playbackTask?.cancel()
        searchID = UUID()
        fetchID = UUID()
        let currentSearchID = searchID

        player?.pause()
        playbackID = UUID()
        player = nil
        selectedEpisode = nil
        fetchResponse = nil
        pendingFetchResult = nil
        selectedResult = nil
        results = []
        isSearching = true

        searchTask = Task {
            do {
                let response = try await client.search(keyword: trimmed)
                await MainActor.run {
                    guard currentSearchID == searchID else { return }
                    results = response
                    isSearching = false
                }
            } catch is CancellationError {
                await MainActor.run {
                    guard currentSearchID == searchID else { return }
                    isSearching = false
                }
            } catch {
                await MainActor.run {
                    guard currentSearchID == searchID else { return }
                    isSearching = false
                    client.errorMessage = error.localizedDescription
                }
            }
        }
    }

    private func selectHistory(_ item: String) {
        keyword = item
        startSearch()
    }

    private func addHistory(_ item: String) {
        var history = searchHistory.filter { $0 != item }
        history.insert(item, at: 0)
        history = Array(history.prefix(20))
        saveHistory(history)
    }

    private func deleteHistory(_ item: String) {
        saveHistory(searchHistory.filter { $0 != item })
    }

    private func clearHistory() {
        saveHistory([])
    }

    private func saveHistory(_ history: [String]) {
        if let data = try? JSONEncoder().encode(history), let text = String(data: data, encoding: .utf8) {
            searchHistoryData = text
        }
    }

    private func selectResult(_ result: SearchResult) {
        if selectedResult?.id == result.id, fetchResponse?.id == result.id || pendingFetchResult?.id == result.id {
            return
        }

        fetchTask?.cancel()
        playbackTask?.cancel()
        selectedResult = result
        pendingFetchResult = result
        fetchResponse = nil
        fetchID = UUID()
        let currentFetchID = fetchID
        isFetching = true

        player?.pause()
        playbackID = UUID()
        player = nil
        selectedEpisode = nil
        isResolvingPlayback = false

        fetchTask = Task {
            do {
                let response = try await client.fetch(site: result.site, vodID: result.vod_id)
                await MainActor.run {
                    guard currentFetchID == fetchID else { return }
                    fetchResponse = response
                    pendingFetchResult = nil
                    isFetching = false
                }
            } catch is CancellationError {
                await MainActor.run {
                    guard currentFetchID == fetchID else { return }
                    pendingFetchResult = nil
                    isFetching = false
                }
            } catch {
                await MainActor.run {
                    guard currentFetchID == fetchID else { return }
                    fetchResponse = nil
                    pendingFetchResult = nil
                    isFetching = false
                    client.errorMessage = error.localizedDescription
                }
            }
        }
    }

    private func selectEpisode(_ episode: EpisodeLink) {
        playbackTask?.cancel()
        selectedEpisode = episode
        player?.pause()
        player = nil
        isResolvingPlayback = true
        playbackID = UUID()
        let currentPlaybackID = playbackID

        playbackTask = Task {
            do {
                let url = try await PlaybackURLResolver().resolve(from: episode.url)
                let newPlayer = AVPlayer(url: url)
                await MainActor.run {
                    guard currentPlaybackID == playbackID else { return }
                    player = newPlayer
                    isResolvingPlayback = false
                    newPlayer.play()
                }
            } catch is CancellationError {
                await MainActor.run {
                    guard currentPlaybackID == playbackID else { return }
                    isResolvingPlayback = false
                }
            } catch {
                await MainActor.run {
                    guard currentPlaybackID == playbackID else { return }
                    isResolvingPlayback = false
                    client.errorMessage = error.localizedDescription
                }
            }
        }
    }

    private func goBack() {
        if player != nil || selectedEpisode != nil || isResolvingPlayback {
            playbackTask?.cancel()
            player?.pause()
            playbackID = UUID()
            player = nil
            selectedEpisode = nil
            isResolvingPlayback = false
            return
        }

        if selectedResult != nil || fetchResponse != nil || pendingFetchResult != nil {
            fetchTask?.cancel()
            playbackTask?.cancel()
            fetchID = UUID()
            selectedResult = nil
            pendingFetchResult = nil
            fetchResponse = nil
            isFetching = false
            playbackID = UUID()
            return
        }

        if !results.isEmpty || isSearching {
            searchTask?.cancel()
            fetchTask?.cancel()
            playbackTask?.cancel()
            searchID = UUID()
            fetchID = UUID()
            playbackID = UUID()
            isSearching = false
            isFetching = false
            isResolvingPlayback = false
            results = []
            submittedKeyword = ""
            hasSearched = false
            return
        }

        hasSearched = false
    }
}
