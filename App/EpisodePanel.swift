import SwiftUI

struct EpisodePanel: View {
    let response: FetchResponse
    let selectedEpisode: EpisodeLink?
    let onSelect: (EpisodeLink) -> Void

    var body: some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 4) {
                Text(response.site)
                    .font(.headline)
                Text("共 \(response.vod_play_url.count) 集")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()

            Divider()

            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 90), spacing: 8)], spacing: 8) {
                    ForEach(response.vod_play_url) { link in
                        EpisodeButton(link: link, isSelected: selectedEpisode?.id == link.id) {
                            onSelect(link)
                        }
                    }
                }
                .padding()
            }
        }
        .background(.ultraThinMaterial)
    }
}

struct EpisodeLoadingPanel: View {
    let result: SearchResult

    var body: some View {
        VStack(spacing: 16) {
            VStack(alignment: .leading, spacing: 4) {
                Text(result.site)
                    .font(.headline)
                Text("正在获取剧集...")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()

            ProgressView()
            Spacer()
        }
        .background(.ultraThinMaterial)
    }
}

private struct EpisodeButton: View {
    let link: EpisodeLink
    let isSelected: Bool
    let onSelect: () -> Void

    var body: some View {
        Button(action: onSelect) {
            Text(link.name)
                .font(.caption)
                .lineLimit(1)
                .truncationMode(.tail)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .background(
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(isSelected ? Color.accentColor.opacity(0.2) : Color.white.opacity(0.05))
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .stroke(isSelected ? Color.accentColor : Color.secondary.opacity(0.28), lineWidth: 1)
                )
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
