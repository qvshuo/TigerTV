import SwiftUI

struct SearchHistoryView: View {
    let history: [String]
    let onSelect: (String) -> Void
    let onDelete: (String) -> Void
    let onClear: () -> Void
    @State private var hoveredItem: String?

    var body: some View {
        if !history.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text("搜索历史")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Button("清空历史", action: onClear)
                        .buttonStyle(.plain)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                FlowLayout(spacing: 6, lineSpacing: 9) {
                    ForEach(history, id: \.self) { item in
                        HistoryChip(
                            text: item,
                            isHovered: hoveredItem == item,
                            onSelect: { onSelect(item) },
                            onDelete: { onDelete(item) }
                        )
                        .onHover { hovering in
                            hoveredItem = hovering ? item : nil
                        }
                    }
                }
            }
            .padding(14)
            .background(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(.ultraThinMaterial)
                    .overlay(
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .stroke(Color.secondary.opacity(0.18), lineWidth: 1)
                    )
            )
        }
    }
}

private struct HistoryChip: View {
    let text: String
    let isHovered: Bool
    let onSelect: () -> Void
    let onDelete: () -> Void

    private var displayText: String {
        if text.count > 12 {
            return String(text.prefix(12)) + "…"
        }
        return text
    }

    var body: some View {
        Button(action: onSelect) {
            Text(displayText)
                .font(.callout)
                .lineLimit(1)
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .background(
                    Capsule(style: .continuous)
                        .fill(Color.secondary.opacity(0.08))
                )
                .overlay(alignment: .topTrailing) {
                    if isHovered {
                        Button(action: onDelete) {
                            Image(systemName: "xmark.circle.fill")
                                .font(.system(size: 13))
                                .symbolRenderingMode(.hierarchical)
                        }
                        .buttonStyle(.plain)
                        .offset(x: 6, y: -6)
                    }
                }
        }
        .buttonStyle(.plain)
    }
}

    private struct FlowLayout: Layout {
        var spacing: CGFloat = 6
        var lineSpacing: CGFloat = 9

        func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
            let result = FlowResult(in: proposal.width ?? .infinity, subviews: subviews, spacing: spacing, lineSpacing: lineSpacing)
            return result.size
        }

        func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
            let result = FlowResult(in: bounds.width, subviews: subviews, spacing: spacing, lineSpacing: lineSpacing)
            for (index, subview) in subviews.enumerated() {
                subview.place(
                    at: CGPoint(
                        x: bounds.minX + result.frames[index].minX,
                        y: bounds.minY + result.frames[index].minY
                    ),
                    proposal: .unspecified
                )
            }
        }

        private struct FlowResult {
            var size: CGSize = .zero
            var frames: [CGRect] = []

            init(in maxWidth: CGFloat, subviews: Subviews, spacing: CGFloat, lineSpacing: CGFloat) {
                var x: CGFloat = 0
                var y: CGFloat = 0
                var rowHeight: CGFloat = 0
                var maxX: CGFloat = 0
                for subview in subviews {
                    let size = subview.sizeThatFits(.unspecified)
                    if x + size.width > maxWidth && x > 0 {
                        x = 0
                        y += rowHeight + lineSpacing
                        rowHeight = 0
                    }
                    frames.append(CGRect(x: x, y: y, width: size.width, height: size.height))
                    rowHeight = max(rowHeight, size.height)
                    x += size.width + spacing
                    maxX = max(maxX, x - spacing)
                }
                self.size = CGSize(width: min(maxWidth, maxX), height: y + rowHeight)
            }
        }
    }
