import SwiftUI

struct HomeScreen: View {
    @Binding var keyword: String
    let history: [String]
    let onSearch: () -> Void
    let onSelectHistory: (String) -> Void
    let onDeleteHistory: (String) -> Void
    let onClearHistory: () -> Void

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [
                    Color(NSColor.controlBackgroundColor).opacity(0.6),
                    Color(NSColor.windowBackgroundColor)
                ],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()

            VStack(spacing: 26) {
                Spacer()

                VStack(spacing: 10) {
                    Text("小老虎爱看剧")
                        .font(.system(size: 52, weight: .bold, design: .rounded))

                    Text("全网好剧，一搜即达")
                        .font(.title2)
                        .foregroundStyle(.secondary)
                }

                VStack(spacing: 14) {
                    GlassSearchBar(keyword: $keyword, onSearch: onSearch, compact: false)
                        .frame(maxWidth: 520)

                    SearchHistoryView(
                        history: history,
                        onSelect: onSelectHistory,
                        onDelete: onDeleteHistory,
                        onClear: onClearHistory
                    )
                    .frame(maxWidth: 520)
                }

                Spacer()
            }
            .padding()
        }
    }
}
