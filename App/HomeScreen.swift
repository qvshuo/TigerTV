import SwiftUI

struct HomeScreen: View {
    @Binding var keyword: String
    let history: [String]
    let onSearch: () -> Void
    let onSelectHistory: (String) -> Void
    let onDeleteHistory: (String) -> Void
    let onClearHistory: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            Color(NSColor.windowBackgroundColor)
                .ignoresSafeArea()

            GeometryReader { geo in
                Circle()
                    .fill(Color.accentColor.opacity(0.05))
                    .frame(width: geo.size.width * 0.65, height: geo.size.width * 0.65)
                    .blur(radius: 100)
                    .offset(x: geo.size.width * 0.1, y: -geo.size.height * 0.2)
            }
            .ignoresSafeArea()
            .allowsHitTesting(false)

            VStack(spacing: AppSpacing.xl) {
                Spacer()

                VStack(spacing: AppSpacing.sm) {
                    Text("小老虎爱看剧")
                        .font(.system(size: 46, weight: .bold, design: .rounded))
                        .contentTransition(.opacity)

                    Text("全网好剧，一搜即达")
                        .font(.title2)
                        .foregroundStyle(.secondary)
                }

                VStack(spacing: AppSpacing.md) {
                    GlassSearchBar(keyword: $keyword, onSearch: onSearch, compact: false)
                        .frame(maxWidth: 560)

                    if !history.isEmpty {
                        SearchHistoryView(
                            history: history,
                            onSelect: onSelectHistory,
                            onDelete: onDeleteHistory,
                            onClear: onClearHistory
                        )
                        .frame(maxWidth: 560)
                        .transition(.move(edge: .top).combined(with: .opacity))
                    }
                }

                Spacer()
            }
            .padding()
        }
        .animation(reduceMotion ? nil : AppMotion.page, value: history.isEmpty)
    }
}
