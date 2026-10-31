import SwiftUI

struct SearchHistoryView: View {
    let history: [String]
    let onSelect: (String) -> Void
    let onDelete: (String) -> Void
    let onClear: () -> Void
    @State private var hoveredItem: String?
    @State private var clearHovered = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        GroupBox {
            VStack(alignment: .leading, spacing: AppSpacing.md) {
                HStack {
                    Text("搜索历史").font(.caption).foregroundStyle(.secondary)
                    Spacer()
                    Button("清空历史", action: onClear)
                        .buttonStyle(.plain).font(.caption)
                        .foregroundStyle(Color.secondary.opacity(clearHovered ? 1 : 0.7))
                        .onHover { clearHovered = $0 }
                        .animation(reduceMotion ? nil : AppMotion.control, value: clearHovered)
                }
                FlowLayout(spacing: AppSpacing.sm, lineSpacing: AppSpacing.sm) {
                    ForEach(history, id: \.self) { item in
                        HistoryChip(
                            text: item, isHovered: hoveredItem == item,
                            onSelect: { onSelect(item) }, onDelete: { onDelete(item) }
                        )
                        .onHover { hoveredItem = $0 ? item : nil }
                        .contextMenu {
                            Button("移除记录", role: .destructive) { onDelete(item) }
                        }
                        .accessibilityAction(named: "移除记录") { onDelete(item) }
                    }
                }
            }
            .padding(AppSpacing.sm)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .animation(reduceMotion ? nil : AppMotion.control, value: hoveredItem)
        .animation(reduceMotion ? nil : AppMotion.control, value: history)
    }
}

struct HistoryChip: View {
    let text: String
    let isHovered: Bool
    let onSelect: () -> Void
    let onDelete: () -> Void
    @State private var deleteHovered = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Button {
            onSelect()
        } label: {
            Text(text).font(.callout).lineLimit(1).padding(.horizontal, AppSpacing.xs)
        }
        .controlSize(.large)
        .buttonStyle(.glass)
        .buttonBorderShape(.capsule)
        .overlay(alignment: .topTrailing) {
            if isHovered {
                Button(role: .destructive, action: onDelete) {
                    Image(systemName: "xmark.circle.fill")
                        .resizable()
                        .frame(width: 12, height: 12)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("移除记录")
                .foregroundStyle(Color.secondary.opacity(deleteHovered ? 1 : 0.7))
                .background(.background, in: .circle)
                .onHover { deleteHovered = $0 }
                .animation(reduceMotion ? nil : AppMotion.control, value: deleteHovered)
                .help("移除记录")
                .transition(.opacity)
            }
        }
        .onChange(of: isHovered) { _, hovered in
            if !hovered { deleteHovered = false }
        }
    }
}

struct FlowLayout: Layout {
    var spacing: CGFloat = AppSpacing.xs
    var lineSpacing: CGFloat = AppSpacing.sm

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let result = FlowResult(
            in: proposal.width ?? .infinity, subviews: subviews, spacing: spacing, lineSpacing: lineSpacing)
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
                proposal: ProposedViewSize(result.frames[index].size)
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
                let size = subview.sizeThatFits(
                    ProposedViewSize(width: maxWidth.isFinite ? maxWidth : nil, height: nil))
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
