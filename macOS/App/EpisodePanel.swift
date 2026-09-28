import SwiftUI

struct EpisodePanel: View {
    let response: FetchResponse
    var sources: [SearchResult] = []
    var selectedResult: SearchResult? = nil
    let selectedEpisode: EpisodeLink?
    var onSwitchSource: (SearchResult) -> Void = { _ in }
    let onSelect: (EpisodeLink) -> Void

    var body: some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: AppSpacing.xs) {
                // 合并组内多源时展示源切换控件；单源组与旧行为一致（仅站点名）。
                if sources.count > 1, let selectedResult {
                    SourcePicker(
                        sources: sources,
                        current: selectedResult,
                        onSwitch: onSwitchSource
                    )
                } else {
                    Text(response.site)
                        .font(.headline)
                }
                Text("共 \(response.vodPlayUrl.count) 集")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(AppSpacing.md)

            Divider()

            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 90), spacing: AppSpacing.sm)], spacing: AppSpacing.sm) {
                    ForEach(response.vodPlayUrl) { link in
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

/// 合并组内多源切换：菜单形式列出全部源，当前源带勾选，选中即触发该源剧集重新 fetch。
private struct SourcePicker: View {
    let sources: [SearchResult]
    let current: SearchResult
    let onSwitch: (SearchResult) -> Void

    var body: some View {
        Menu {
            ForEach(sources) { source in
                Button {
                    if source.id != current.id { onSwitch(source) }
                } label: {
                    HStack {
                        Text(source.site)
                        if source.id == current.id {
                            Image(systemName: "checkmark")
                        }
                    }
                }
            }
        } label: {
            HStack(spacing: 4) {
                Text(current.site)
                    .font(.headline)
                    .lineLimit(1)
                Image(systemName: "chevron.up.chevron.down")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .menuStyle(.borderlessButton)
        .fixedSize()
        .help("切换源（共 \(sources.count) 个）")
    }
}

/// 当前源 fetch 失败时的面板：保留源切换入口（容错核心），并提供重试。
struct EpisodeErrorPanel: View {
    let result: SearchResult
    var sources: [SearchResult] = []
    let message: String
    var onSwitchSource: (SearchResult) -> Void = { _ in }
    var onRetry: () -> Void = {}

    var body: some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: AppSpacing.xs) {
                if sources.count > 1 {
                    SourcePicker(sources: sources, current: result, onSwitch: onSwitchSource)
                } else {
                    Text(result.site)
                        .font(.headline)
                }
                Text("获取剧集失败")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(AppSpacing.md)

            Divider()

            VStack(spacing: AppSpacing.md) {
                Spacer()
                Text(message)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .lineLimit(3)
                HStack(spacing: AppSpacing.sm) {
                    Button("重试", action: onRetry)
                        .buttonStyle(.borderedProminent)
                    // 多源时提示可换源（实际切换用上面的 SourcePicker）。
                    if sources.count > 1, let other = sources.first(where: { $0.id != result.id }) {
                        Button("换源试试") { onSwitchSource(other) }
                            .buttonStyle(.bordered)
                    }
                }
                Spacer()
            }
            .padding(AppSpacing.md)
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
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

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
        .scaleEffect(reduceMotion ? 1.0 : (isHovered ? 1.02 : 1.0))
        .animation(reduceMotion ? nil : AppMotion.hover, value: isHovered)
        .onHover { isHovered = $0 }
    }
}
