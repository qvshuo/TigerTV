import SwiftUI

struct SearchResultCard: View {
    let work: VideoWork
    let result: SearchResult
    let isSelected: Bool
    let isLoading: Bool
    let coverFallbacks: [String: URL]
    let onLoadCoverFallback: (SearchResult) async -> Void
    @State private var coverImage: NSImage?
    @State private var loadedCoverURL: URL?
    let onTap: () -> Void
    @State private var isHovered = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.sm) {
            cover

            Text(result.displayTitle)
                .font(.headline)
                .fontWeight(.semibold)
                .lineLimit(1)

            HStack(spacing: AppSpacing.xs) {
                Text("\(work.sources.count) 个资源")
                    .font(.caption)
                    .fontWeight(.medium)
                    .lineLimit(1)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(
                        RoundedRectangle(cornerRadius: 6, style: .continuous)
                            .fill(Color.accentColor.opacity(0.15))
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 6, style: .continuous)
                            .stroke(Color.accentColor.opacity(0.3), lineWidth: 1)
                    )

                if !result.vodRemarks.isEmpty {
                    Text(result.vodRemarks)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }

            if !result.vodTime.isEmpty || !result.vodYear.isEmpty {
                Text(result.vodTime.isEmpty ? result.vodYear : result.vodTime)
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
                    .lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(AppSpacing.md)
        .glassBackground(
            radius: AppRadius.md,
            strokeOpacity: 0.22,
            shadowRadius: isHovered ? 10 : 6,
            isActive: isSelected,
            activeStrokeOpacity: 0.65
        )
        .scaleEffect(reduceMotion ? 1.0 : (isHovered && !isSelected ? 1.012 : 1.0))
        .offset(y: reduceMotion ? 0 : (isHovered && !isSelected ? -1.5 : 0))
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
        .onHover { isHovered = $0 }
        .animation(reduceMotion ? nil : AppMotion.hover, value: isHovered)
        .animation(reduceMotion ? nil : AppMotion.select, value: isSelected)
        .overlay(alignment: .topTrailing) {
            if isLoading {
                AppLoadingIndicator(compact: true)
                    .padding(AppSpacing.sm)
                    .glassBackground(radius: AppRadius.sm)
                    .padding(AppSpacing.sm)
            }
        }
        .task(id: work.sources) {
            for source in work.sources where source.vodPic.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                guard !Task.isCancelled else { return }
                await onLoadCoverFallback(source)
            }
        }
        .task(id: candidateURLs) {
            // New fallback URLs must not replace an already successful cover.
            if let loadedCoverURL, candidateURLs.first == loadedCoverURL, coverImage != nil { return }
            if let loaded = await loadFirstCover(candidates: candidateURLs, load: { url in
                await CoverImageCache.shared.image(for: url)
            }) {
                coverImage = loaded.value
                loadedCoverURL = loaded.url
            }
        }
    }

    private var candidateURLs: [URL] {
        var seen = Set<URL>()
        return work.orderedSources.compactMap { source in
            HTTPClient.percentEncodedURL(from: source.vodPic) ?? coverFallbacks[source.id]
        }.filter { seen.insert($0).inserted }
    }

    private var cover: some View {
        Color.clear
            .overlay {
                if let image = coverImage ?? candidateURLs.lazy.compactMap({ CoverImageCache.shared.cachedImage(for: $0) }).first {
                    Image(nsImage: image).resizable().scaledToFill()
                } else {
                    coverPlaceholder
                }
            }
            .aspectRatio(2.0 / 3.0, contentMode: .fit)
            .frame(maxWidth: .infinity)
            .clipShape(RoundedRectangle(cornerRadius: AppRadius.sm, style: .continuous))
    }

    private var coverPlaceholder: some View {
        ZStack {
            LinearGradient(
                colors: [Color.primary.opacity(0.06), Color.black.opacity(0.18)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            Text(String(result.displayTitle.prefix(1)))
                .font(.system(size: 44, weight: .bold))
                .foregroundStyle(.primary.opacity(0.25))
        }
    }
}
