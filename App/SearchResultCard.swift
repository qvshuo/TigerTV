import SwiftUI

struct SearchResultCard: View {
    let result: SearchResult
    let isSelected: Bool
    let isLoading: Bool
    let onTap: () -> Void
    @State private var isHovered = false

    var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.sm) {
            HStack(spacing: AppSpacing.sm) {
                Text(result.displayTitle)
                    .font(.title3)
                    .fontWeight(.semibold)
                    .lineLimit(1)

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
            }

            if let remarks = result.vod_remarks, !remarks.isEmpty {
                Text(remarks)
                    .font(.body)
                    .foregroundStyle(.primary.opacity(0.85))
                    .lineLimit(1)
            }

            if let time = result.vod_time, !time.isEmpty {
                Text(time)
                    .font(.callout)
                    .foregroundStyle(.secondary)
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
        .scaleEffect(isHovered && !isSelected ? 1.012 : 1.0)
        .offset(y: isHovered && !isSelected ? -1.5 : 0)
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
        .onHover { isHovered = $0 }
        .animation(AppMotion.hover, value: isHovered)
        .animation(AppMotion.select, value: isSelected)
        .overlay(alignment: .topTrailing) {
            if isLoading {
                ProgressView()
                    .controlSize(.small)
                    .padding(AppSpacing.sm)
                    .glassBackground(radius: AppRadius.sm)
                    .padding(AppSpacing.sm)
            }
        }
    }
}
