import SwiftUI

struct SearchBar: View {
    @Binding var keyword: String
    let onSearch: () -> Void
    let compact: Bool
    var isSearching = false

    @FocusState private var isInputFocused: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: AppSpacing.sm) {
            HStack(spacing: AppSpacing.sm) {
                TextField("输入剧名、电影名…", text: $keyword)
                    .textFieldStyle(.plain)
                    .focused($isInputFocused)
                    .font(compact ? .body : .title3)
                    .foregroundStyle(Color(nsColor: .textColor))
                    .onSubmit { if canSearch { onSearch() } }
                if !keyword.isEmpty {
                    Button("清空", systemImage: "xmark.circle.fill") {
                        keyword = ""
                        isInputFocused = true
                    }
                    .labelStyle(.iconOnly)
                    .buttonStyle(.plain)
                    .foregroundStyle(.secondary)
                    .help("清空搜索内容")
                }
            }
            .padding(.horizontal, AppSpacing.md)
            .frame(height: compact ? AppMetrics.compactSearchHeight : AppMetrics.homeSearchHeight)
            .glassEffect(.regular, in: .capsule)

            Button(action: onSearch) {
                ZStack {
                    if isSearching {
                        AppLoadingIndicator(compact: true, light: true)
                    } else {
                        Image(systemName: "arrow.right")
                    }
                }
                .frame(width: compact ? 20 : 24, height: compact ? 20 : 24)
            }
            .buttonStyle(.glassProminent)
            .buttonBorderShape(.capsule)
            .tint(.accentColor)
            .disabled(!canSearch)
            .accessibilityLabel(isSearching ? "正在搜索…" : "搜索")
        }
        .controlSize(compact ? .regular : .extraLarge)
        .animation(reduceMotion ? nil : AppMotion.control, value: isSearching)
        .animation(reduceMotion ? nil : AppMotion.control, value: keyword.isEmpty)
    }

    private var canSearch: Bool {
        !isSearching && !keyword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}
