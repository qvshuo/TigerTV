import SwiftUI

struct SearchResultCard: View {
    let result: SearchResult
    let isSelected: Bool
    let isLoading: Bool
    let onTap: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
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
        .padding()
        .background(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(.ultraThinMaterial)
                .overlay(
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .stroke(isSelected ? Color.accentColor.opacity(0.65) : Color.secondary.opacity(0.22), lineWidth: 1)
                )
        )
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
        .overlay(alignment: .topTrailing) {
            if isLoading {
                ProgressView()
                    .controlSize(.small)
                    .padding(12)
            }
        }
    }
}
