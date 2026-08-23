import SwiftUI

struct SearchResultCard: View {
    let result: SearchResult
    let isSelected: Bool
    let isLoading: Bool
    var fallbackCoverURL: URL? = nil
    var onLoadCoverFallback: () async -> Void = {}
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
                Text(result.site)
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

            if !result.vodTime.isEmpty {
                Text(result.vodTime)
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
                ProgressView()
                    .controlSize(.small)
                    .padding(AppSpacing.sm)
                    .glassBackground(radius: AppRadius.sm)
                    .padding(AppSpacing.sm)
            }
        }
        .task(id: result.id) {
            // 仅空封面卡片进入视口时触发 detail 兜底请求；有封面或已取到兜底则跳过。
            guard result.vodPic.isEmpty, fallbackCoverURL == nil else { return }
            await onLoadCoverFallback()
        }
    }

    /// 封面图：优先 list 自带 vod_pic，为空时用 ViewModel 兜底缓存的 detail 封面；
    /// 加载中显示占位背景，失败或无 URL 回落到标题首字占位。
    /// 字节层走 CoverImageCache（NSCache + 磁盘 7 天 TTL），返回结果页不再重新下载。
    ///
    /// ⚠️ 布局必须以 `Color.clear.aspectRatio(.fit)` 为锚：直接对 scaledToFill 的图片
    /// 再叠一层 aspectRatio(.fill)，大分辨率图会让外层采纳子视图理想尺寸导致溢出容器。
    private var cover: some View {
        Color.clear
            .overlay {
                if let url = coverURL {
                    CachedRemoteImage(url: url) {
                        coverPlaceholder
                    }
                } else {
                    coverPlaceholder
                }
            }
            .aspectRatio(2.0 / 3.0, contentMode: .fit)
            .frame(maxWidth: .infinity)
            .clipShape(RoundedRectangle(cornerRadius: AppRadius.sm, style: .continuous))
    }

    /// 基于两级缓存的远程封面：内存命中即时显示，未命中异步加载期间显示占位图。
    private struct CachedRemoteImage<Placeholder: View>: View {
        let url: URL
        @ViewBuilder let placeholder: () -> Placeholder

        @State private var image: NSImage?
        @Environment(\.accessibilityReduceMotion) private var reduceMotion

        var body: some View {
            Group {
                if let image {
                    Image(nsImage: image)
                        .resizable()
                        .scaledToFill()
                } else {
                    placeholder()
                }
            }
            .task(id: url) {
                if let loaded = await CoverImageCache.shared.image(for: url) {
                    withAnimation(reduceMotion ? nil : .easeOut(duration: 0.2)) {
                        image = loaded
                    }
                }
            }
        }
    }

    private var coverURL: URL? {
        if !result.vodPic.isEmpty { return HTTPClient.percentEncodedURL(from: result.vodPic) }
        return fallbackCoverURL
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
