import SwiftUI
import AVKit
import AppKit

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

                        GlassSearchBar(keyword: $keyword, onSearch: onSearch, compact: true, isSearching: isSearching)

                        Spacer()
                    }
                    .padding(AppSpacing.md)
                    .background(.ultraThinMaterial)
                    .overlay(alignment: .bottom) {
                        Rectangle()
                            .fill(Color.secondary.opacity(0.1))
                            .frame(height: 1)
                    }

                    ZStack {
                        leftContent
                            .opacity(selectedResult == nil ? 1 : 0)
                            .allowsHitTesting(selectedResult == nil)
                            .accessibilityHidden(selectedResult != nil)
                        if let selectedResult { detailContent(selectedResult) }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
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
    private func detailContent(_ result: SearchResult) -> some View {
        if let player {
            PlayerContainer(player: player)
        } else if isResolvingPlayback {
            AppLoadingState(message: "正在解析播放地址…")
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: AppSpacing.lg) {
                    detailOverview(result)

                    VStack(alignment: .leading, spacing: AppSpacing.md) {
                        sectionTitle("资源")
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 160))], spacing: AppSpacing.sm) {
                            ForEach(availableSources) { source in
                                detailChoice(source.site, selected: source.id == result.id) { onSelectResult(source) }
                            }
                        }
                    }
                    .padding(AppSpacing.lg)
                    .glassBackground(radius: AppRadius.md)

                    if isFetching {
                        AppLoadingState(message: "加载中…")
                            .frame(height: AppMetrics.loadingRegion)
                            .glassBackground(radius: AppRadius.md)
                    } else if let error = fetchErrorMessage {
                        VStack(alignment: .leading, spacing: AppSpacing.sm) {
                            Text(error).foregroundStyle(.red)
                            Button("重试", action: onRetryFetch)
                        }
                    } else if let response = fetchResponse {
                        VStack(alignment: .leading, spacing: AppSpacing.lg) {
                            if !response.playSources.isEmpty {
                                VStack(alignment: .leading, spacing: AppSpacing.md) {
                                    sectionTitle("线路")
                                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 140))], spacing: AppSpacing.sm) {
                                        ForEach(Array(response.playSources.enumerated()), id: \.element.id) { index, source in
                                            detailChoice(source.name, selected: index == selectedPlaybackSourceIndex) {
                                                onSelectPlaybackSource(index)
                                            }
                                        }
                                    }
                                }
                                Divider().opacity(0.5)
                            }
                            sectionTitle("选集")
                            if episodes.isEmpty {
                                Text("暂无剧集").foregroundStyle(.secondary)
                            } else {
                                LazyVGrid(columns: [GridItem(.adaptive(minimum: 95))], spacing: AppSpacing.sm) {
                                    ForEach(Array(episodes.enumerated()), id: \.offset) { _, episode in
                                        detailChoice(episode.name, selected: selectedEpisode == episode) { onSelectEpisode(episode) }
                                    }
                                }
                            }
                        }
                        .padding(AppSpacing.lg)
                        .glassBackground(radius: AppRadius.md)
                    }
                }
                .frame(maxWidth: 1120, alignment: .leading)
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
        let urls = [detail?.vodPic, result.vodPic].compactMap { $0 }.compactMap(HTTPClient.percentEncodedURL)
            + [coverFallbacks[result.id]].compactMap { $0 }
        let candidates = urls.filter { seen.insert($0).inserted }
        return HStack(alignment: .top, spacing: AppSpacing.lg) {
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
                    Button { onSelectEpisode(episode) } label: {
                        Label("播放 · \(episode.name)", systemImage: "play.fill")
                            .font(.headline.weight(.semibold))
                            .frame(height: AppMetrics.controlHeight - AppSpacing.sm, alignment: .center)
                            .padding(.horizontal, AppSpacing.sm)
                    }
                    .buttonStyle(.glassProminent)
                    .controlSize(.regular)
                    .buttonBorderShape(.capsule)
                    .tint(Color.accentColor)
                    .foregroundStyle(.white)
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
        .padding(AppSpacing.lg)
        .glassBackground(radius: AppRadius.lg)
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

    private func detailChoice(_ title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: AppSpacing.xs) {
                if selected { Image(systemName: "checkmark.circle.fill").font(.caption) }
                Text(title).lineLimit(1)
            }
            .font(.callout.weight(selected ? .semibold : .regular))
            .frame(maxWidth: .infinity, minHeight: AppMetrics.controlHeight)
            .padding(.horizontal, AppSpacing.sm)
            .foregroundStyle(selected ? Color.accentColor : Color.primary)
            .background(selected ? Color.accentColor.opacity(0.12) : Color.secondary.opacity(0.06), in: RoundedRectangle(cornerRadius: AppRadius.sm))
            .overlay(RoundedRectangle(cornerRadius: AppRadius.sm).stroke(selected ? Color.accentColor.opacity(0.35) : .clear))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .hoverLift()
    }

    @ViewBuilder
    private var leftContent: some View {
        if isSearching && works.isEmpty {
            Color.clear
        } else if works.isEmpty {
            ContentUnavailableView("未找到结果", systemImage: "magnifyingglass")
                .font(.title3)
        } else {
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 200), spacing: AppSpacing.md)], spacing: AppSpacing.md) {
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
                        }
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
                    LinearGradient(colors: [Color.accentColor.opacity(0.15), Color.secondary.opacity(0.1)], startPoint: .topLeading, endPoint: .bottomTrailing)
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
