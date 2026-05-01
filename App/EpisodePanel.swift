import SwiftUI

struct EpisodePanel: View {
    let response: FetchResponse
    let selectedEpisode: EpisodeLink?
    let onSelect: (EpisodeLink) -> Void

    var body: some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: AppSpacing.xs) {
                Text(response.site)
                    .font(.headline)
                Text("共 \(response.vod_play_url.count) 集")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(AppSpacing.md)

            Divider()

            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 90), spacing: AppSpacing.sm)], spacing: AppSpacing.sm) {
                    ForEach(response.vod_play_url) { link in
                        EpisodeButton(
                            link: link,
                            isSelected: selectedEpisode?.id == link.id
                        ) {
                            onSelect(link)
                        }
                        .transition(.scale(scale: 0.9).combined(with: .opacity))
                    }
                }
                .padding(AppSpacing.md)
            }
        }
        .background(.ultraThinMaterial)
    }
}

struct EpisodeLoadingPanel: View {
    let result: SearchResult

    var body: some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: AppSpacing.xs) {
                Text(result.site)
                    .font(.headline)
                Text("正在获取剧集...")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(AppSpacing.md)

            Divider()

            VStack(spacing: 0) {
                Spacer()
                ProgressView()
                    .controlSize(.small)
                Spacer()
            }
            .padding(AppSpacing.md)
        }
        .background(.ultraThinMaterial)
    }
}

private struct EpisodeButton: View {
    let link: EpisodeLink
    let isSelected: Bool
    let onSelect: () -> Void
    @State private var isHovered = false

    var body: some View {
        Button(action: onSelect) {
            Text(link.name)
                .font(.caption)
                .lineLimit(1)
                .truncationMode(.tail)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .foregroundStyle(isSelected ? .primary : .secondary)
                .background(
                    RoundedRectangle(cornerRadius: AppRadius.sm, style: .continuous)
                        .fill(
                            isSelected
                                ? Color.accentColor.opacity(0.15)
                                : isHovered
                                    ? Color.secondary.opacity(0.1)
                                    : Color.secondary.opacity(0.05)
                        )
                )
                .overlay(
                    RoundedRectangle(cornerRadius: AppRadius.sm, style: .continuous)
                        .stroke(
                            isSelected ? Color.accentColor.opacity(0.5) : Color.clear,
                            lineWidth: isSelected ? 1.5 : 0.5
                        )
                )
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .scaleEffect(isHovered ? 1.02 : 1.0)
        .animation(AppMotion.hover, value: isHovered)
        .onHover { isHovered = $0 }
    }
}
