import SwiftUI

struct SearchHistoryView: View {
    let history: [String]
    let onSelect: (String) -> Void
    let onDelete: (String) -> Void
    let onClear: () -> Void
    @State private var hoveredItem: String?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        if !history.isEmpty {
            VStack(alignment: .leading, spacing: AppSpacing.sm) {
                HStack {
                    Text("搜索历史")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Button("清空历史", action: onClear)
                        .buttonStyle(.plain)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .hoverLift()
                }

                FlowLayout(spacing: AppSpacing.xs, lineSpacing: AppSpacing.sm) {
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
            .padding(AppSpacing.md)
            .glassBackground(radius: AppRadius.md)
            .animation(reduceMotion ? nil : AppMotion.hover, value: hoveredItem)
        }
    }
}

private struct HistoryChip: View {
    let text: String
    let isHovered: Bool
    let onSelect: () -> Void
    let onDelete: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var displayText: String {
        if text.count > 12 {
            return String(text.prefix(12)) + "…"
        }
        return text
    }

    var body: some View {
        HStack(spacing: AppSpacing.xs) {
            Text(displayText)
                .font(.callout)
                .lineLimit(1)

            if isHovered {
                Button(action: onDelete) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 13))
                        .symbolRenderingMode(.hierarchical)
                }
                .buttonStyle(.plain)
                .help("删除")
                .transition(.scale.combined(with: .opacity))
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 7)
        .background(
            Capsule(style: .continuous)
                .fill(isHovered ? Color.secondary.opacity(0.14) : Color.secondary.opacity(0.06))
        )
        .contentShape(Capsule(style: .continuous))
        .onTapGesture(perform: onSelect)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .scaleEffect(reduceMotion ? 1.0 : (isHovered ? 1.04 : 1.0))
        .animation(reduceMotion ? nil : AppMotion.hover, value: isHovered)
    }
}

private struct FlowLayout: Layout {
    var spacing: CGFloat = AppSpacing.xs
    var lineSpacing: CGFloat = AppSpacing.sm

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
