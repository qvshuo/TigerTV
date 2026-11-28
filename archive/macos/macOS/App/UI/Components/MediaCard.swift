import SwiftUI

struct MediaCardContent<Poster: View, Information: View>: View {
    let title: String
    let footer: String
    @ViewBuilder let poster: () -> Poster
    @ViewBuilder let information: () -> Information

    var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.sm) {
            poster()
            Text(title).font(.system(size: 14, weight: .semibold)).lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
            VStack(alignment: .leading, spacing: AppSpacing.xs) {
                information().font(.caption).lineLimit(1)
                    .frame(minHeight: 18, alignment: .leading)
                Text(footer).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    .frame(minHeight: 18, alignment: .leading)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(AppSpacing.sm)
    }
}

struct MediaCardHover: ViewModifier {
    @State private var hovered = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content
            .scaleEffect(hovered && !reduceMotion ? 1.01 : 1)
            .shadow(color: .black.opacity(hovered ? 0.10 : 0), radius: 8, y: 3)
            .onHover { hovered = $0 }
            .animation(reduceMotion ? nil : AppMotion.control, value: hovered)
    }
}
