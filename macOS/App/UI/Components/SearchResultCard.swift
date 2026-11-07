import SwiftUI

struct SearchResultCard: View {
    let work: VideoWork
    let result: SearchResult
    let isSelected: Bool
    let isLoading: Bool
    let coverFallbacks: [String: URL]
    let onLoadCoverFallback: (SearchResult) async -> Void
    @State private var coverImage: NSImage?
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            GroupBox {
                MediaCardContent(title: result.vodYear.isEmpty ? result.vodName : "\(result.vodName) (\(result.vodYear))", footer: result.vodTime) {
                    cover
                } information: {
                    HStack(spacing: AppSpacing.xs) {
                        Text("\(work.sources.count) 个资源").foregroundStyle(Color.accentColor)
                        Text(result.vodRemarks).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .buttonStyle(.plain)
        .modifier(MediaCardHover())
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .overlay(alignment: .topTrailing) {
            if isLoading {
                AppLoadingIndicator(compact: true)
                    .padding(AppSpacing.sm)
            }
        }
        .task(id: work.sources) {
            for source in work.orderedSources where source.vodPic.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                guard !Task.isCancelled else { return }
                await onLoadCoverFallback(source)
            }
        }
        .task(id: candidateURLs) {
            // New fallback URLs must not replace an already successful cover.
            guard coverImage == nil else { return }
            if let loaded = await loadFirstCover(
                candidates: candidateURLs,
                load: { url in
                    await CoverImageCache.shared.image(for: url)
                })
            {
                coverImage = loaded.value
            }
        }
    }

    private var candidateURLs: [URL] {
        var seen = Set<URL>()
        return work.orderedSources.compactMap { source in
            HTTPConfiguration.percentEncodedURL(from: source.vodPic) ?? coverFallbacks[source.id]
        }.filter { seen.insert($0).inserted }
    }

    private var cover: some View {
        Color.clear
            .overlay {
                if let image = coverImage {
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
