import AVKit
import AppKit
import SwiftUI

struct ResultsLayout: View {
    @Binding var keyword: String
    let submittedKeyword: String
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
                    leftContent
                        .opacity(selectedResult == nil ? 1 : 0)
                        .allowsHitTesting(selectedResult == nil)
                        .accessibilityHidden(selectedResult != nil)
                    if let selectedResult { detailContent(selectedResult) }
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

    @ViewBuilder
    private func detailContent(_ result: SearchResult) -> some View {
        if let player {
            PlayerContainer(player: player)
        } else if isResolvingPlayback {
            AppLoadingState(message: "正在准备播放…")
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: AppSpacing.lg) {
                    detailOverview(result)

                    GroupBox {
                        VStack(alignment: .leading, spacing: AppSpacing.md) {
                            sectionTitle("资源")
                            LazyVGrid(
                                columns: [GridItem(.adaptive(minimum: 160), spacing: AppSpacing.sm)],
                                spacing: AppSpacing.sm
                            ) {
                                ForEach(availableSources) { source in
                                    detailChoice(source.site, selected: source.id == result.id) {
                                        onSelectResult(source)
                                    }
                                }
                            }
                        }
                        .padding(AppSpacing.md)
                    }

                    GroupBox {
                        VStack(alignment: .leading, spacing: AppSpacing.lg) {
                            VStack(alignment: .leading, spacing: AppSpacing.md) {
                                sectionTitle("线路")
                                Group {
                                    if isFetching {
                                        AppLoadingState(message: "正在加载资源…", compact: true)
                                            .frame(height: AppMetrics.resourceStatusHeight)
                                    } else if let error = fetchErrorMessage {
                                        AppFailureState(message: error, onRetry: onRetryFetch, compact: true)
                                    } else if let response = fetchResponse, !response.playSources.isEmpty {
                                        LazyVGrid(
                                            columns: [GridItem(.adaptive(minimum: 140), spacing: AppSpacing.sm)],
                                            spacing: AppSpacing.sm
                                        ) {
                                            ForEach(Array(response.playSources.enumerated()), id: \.element.id) {
                                                index, source in
                                                detailChoice(
                                                    source.name, selected: index == selectedPlaybackSourceIndex
                                                ) {
                                                    onSelectPlaybackSource(index)
                                                }
                                            }
                                        }
                                    } else {
                                        Text("暂无线路").foregroundStyle(.secondary)
                                    }
                                }
                                .frame(minHeight: AppMetrics.resourceStatusHeight)
                            }
                            Divider().opacity(0.5)
                            sectionTitle("选集")
                            if isFetching || fetchErrorMessage != nil {
                                Color.clear.frame(height: AppMetrics.loadingRegion)
                            } else if episodes.isEmpty {
                                Text("暂无播放内容").foregroundStyle(.secondary)
                            } else {
                                FlowLayout(spacing: AppSpacing.sm, lineSpacing: AppSpacing.sm) {
                                    ForEach(Array(episodes.enumerated()), id: \.offset) { _, episode in
                                        detailChoice(episode.name, selected: selectedEpisode == episode, compact: true)
                                        {
                                            onSelectEpisode(episode)
                                        }
                                    }
                                }
                            }
                        }
                        .padding(AppSpacing.md)
                    }
                }
                .frame(maxWidth: AppMetrics.contentWidth, alignment: .leading)
                .frame(maxWidth: .infinity)
                .padding(AppSpacing.lg)
            }
        }
    }

    private func detailOverview(_ result: SearchResult) -> some View {
        let detail = fetchResponse?.id == result.id ? fetchResponse : nil
        let metadata = [detail?.vodYear ?? result.vodYear, detail?.typeName ?? result.typeName, detail?.vodArea ?? ""]
            .filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        let status = detail?.vodRemarks ?? result.vodRemarks
        var seen = Set<URL>()
        let urls =
            [detail?.vodPic, result.vodPic].compactMap { $0 }.compactMap(HTTPClient.percentEncodedURL)
            + [coverFallbacks[result.id]].compactMap { $0 }
        let candidates = urls.filter { seen.insert($0).inserted }
        return GroupBox {
            HStack(alignment: .top, spacing: AppSpacing.lg) {
                SourceDetailPoster(title: result.vodName, candidates: candidates)
                    .frame(width: AppMetrics.posterWidth, height: AppMetrics.posterHeight)
                    .id(result.id)
                VStack(alignment: .leading, spacing: AppSpacing.md) {
                    Text(detail?.vodName.isEmpty == false ? (detail?.vodName ?? result.vodName) : result.vodName)
                        .font(.system(size: 30, weight: .bold, design: .rounded))
                        .textSelection(.enabled)
                    if !metadata.isEmpty {
                        Text(metadata.joined(separator: " · ")).font(.callout).foregroundStyle(.secondary)
                    }
                    HStack(spacing: AppSpacing.sm) {
                        if let language = detail?.vodLang, !language.isEmpty {
                            Text(language).font(.caption).foregroundStyle(.secondary)
                        }
                        if !status.isEmpty {
                            Text(status).font(.caption.weight(.semibold))
                                .padding(.horizontal, AppSpacing.sm).padding(.vertical, AppSpacing.xs)
                                .background(Color.accentColor.opacity(0.12), in: Capsule())
                                .foregroundStyle(Color.accentColor)
                        }
                    }
                    if let episode = resumeEpisode ?? episodes.first {
                        Button {
                            onSelectEpisode(episode)
                        } label: {
                            Label("播放 · \(episode.name)", systemImage: "play.fill")
                                .font(.body.weight(.semibold))
                                .padding(AppSpacing.xs)
                        }
                        .buttonStyle(.glassProminent)
                        .controlSize(.large)
                        .buttonBorderShape(.capsule)
                        .tint(Color.accentColor)
                        .padding(.vertical, AppSpacing.xs)
                    }
                    if let director = detail?.vodDirector, !director.isEmpty {
                        detailCredit("导演", value: director)
                    }
                    if let actor = detail?.vodActor, !actor.isEmpty {
                        detailCredit("主演", value: actor)
                    }
                    if let content = detail?.vodContent, !content.isEmpty {
                        detailCredit("简介", value: content, lineLimit: nil)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(AppSpacing.md)
        }
    }

    private func detailCredit(_ label: String, value: String, lineLimit: Int? = 2) -> some View {
        HStack(alignment: .top, spacing: AppSpacing.sm) {
            Text(label).foregroundStyle(.tertiary).frame(width: 32, alignment: .leading)
            Text(value).foregroundStyle(.secondary).lineLimit(lineLimit).textSelection(.enabled)
        }
        .font(.callout)
    }

    private func sectionTitle(_ title: String) -> some View {
        HStack {
            Text(title).font(.headline)
            Spacer()
        }
    }

    private func detailChoice(_ title: String, selected: Bool, compact: Bool = false, action: @escaping () -> Void)
        -> some View
    {
        Button(action: action) {
            HStack(spacing: AppSpacing.xs) {
                if selected { Image(systemName: "checkmark") }
                Text(title).font(.callout).lineLimit(1)
            }
            .frame(minWidth: compact ? 72 : nil, maxWidth: compact ? 168 : .infinity)
            .padding(.vertical, AppSpacing.xs)
        }
        .buttonStyle(.glass)
        .controlSize(.large)
        .buttonBorderShape(.capsule)
        .tint(selected ? Color.accentColor : nil)
        .fixedSize(horizontal: compact, vertical: false)
        .help(title)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .animation(reduceMotion ? nil : AppMotion.control, value: selected)
    }

    @ViewBuilder
    private var leftContent: some View {
        if isSearching && works.isEmpty {
            Color.clear
        } else if let searchErrorMessage {
            AppFailureState(message: searchErrorMessage, onRetry: onSearch)
        } else if works.isEmpty {
            ContentUnavailableView("未找到相关作品", systemImage: "magnifyingglass")
                .font(.title3)
        } else {
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 200), spacing: AppSpacing.md)], spacing: AppSpacing.md)
                {
                    ForEach(works) { work in
                        if let result = work.defaultSource {
                            SearchResultCard(
                                work: work,
                                result: result,
                                isSelected: selectedResult?.id == result.id,
                                isLoading: selectedResult?.id == result.id && isFetching,
                                coverFallbacks: coverFallbacks,
                                onLoadCoverFallback: onLoadCoverFallback,
                                onTap: { onSelectResult(result) }
                            )
                            .transition(.opacity)
                        }
                    }
                }
                .animation(reduceMotion ? nil : AppMotion.control, value: works.map(\.id))
                .frame(maxWidth: AppMetrics.contentWidth)
                .padding(AppSpacing.lg)
                .frame(maxWidth: .infinity)
            }
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

/// Detail posters only use URLs belonging to the currently selected source.
private struct SourceDetailPoster: View {
    let title: String
    let candidates: [URL]
    @State private var image: NSImage?

    var body: some View {
        Color.clear.overlay {
            if let image {
                Image(nsImage: image).resizable().scaledToFill()
            } else {
                ZStack {
                    LinearGradient(
                        colors: [Color.accentColor.opacity(0.15), Color.secondary.opacity(0.1)],
                        startPoint: .topLeading, endPoint: .bottomTrailing)
                    Text(String(title.prefix(1))).font(.system(size: 44, weight: .light)).foregroundStyle(.secondary)
                }
            }
        }
        .clipped()
        .clipShape(RoundedRectangle(cornerRadius: AppRadius.md))
        .shadow(color: .black.opacity(0.12), radius: 12, y: 5)
        .task(id: candidates) {
            image = nil
            let loaded = await loadFirstCover(candidates: candidates) { await CoverImageCache.shared.image(for: $0) }
            guard !Task.isCancelled else { return }
            image = loaded?.value
        }
    }
}
