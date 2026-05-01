import SwiftUI

struct GlassSearchBar: View {
    @Binding var keyword: String
    let onSearch: () -> Void
    let compact: Bool
    @FocusState private var isFocused: Bool
    @State private var isSearchHovered = false

    var body: some View {
        HStack(spacing: AppSpacing.sm) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(.secondary)
                .font(compact ? .body : .title3)

            TextField("输入剧名、电影名...", text: $keyword)
                .textFieldStyle(.plain)
                .font(compact ? .body : .title3)
                .onSubmit(onSearch)
                .focused($isFocused)

            Button(action: { keyword = "" }) {
                Image(systemName: "xmark.circle.fill")
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
            .frame(width: 24)
            .opacity(keyword.isEmpty ? 0 : 1)
            .scaleEffect(keyword.isEmpty ? 0.8 : 1)
            .disabled(keyword.isEmpty)
            .animation(AppMotion.hover, value: keyword.isEmpty)

            Button(action: onSearch) {
                Image(systemName: "arrow.right")
                    .font(.system(size: compact ? 12 : 14, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: compact ? 28 : 36, height: compact ? 28 : 36)
                    .background(
                        Circle()
                            .fill(Color.accentColor.opacity(isSearchEnabled ? 1.0 : 0.4))
                    )
            }
            .buttonStyle(.plain)
            .opacity(isSearchEnabled ? 1 : 0.6)
            .scaleEffect(isSearchHovered ? 1.06 : 1.0)
            .disabled(!isSearchEnabled)
            .animation(AppMotion.hover, value: isSearchHovered)
            .onHover { isSearchHovered = $0 }
        }
        .padding(.horizontal, compact ? AppSpacing.md : AppSpacing.lg)
        .padding(.vertical, compact ? AppSpacing.sm : AppSpacing.md)
        .glassBackground(
            radius: compact ? AppRadius.sm : AppRadius.md,
            strokeOpacity: isFocused ? 0.35 : 0.16,
            shadowRadius: isFocused ? 12 : 6,
            isActive: isFocused
        )
        .animation(AppMotion.hover, value: isFocused)
    }

    private var isSearchEnabled: Bool {
        !keyword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}
