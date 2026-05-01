import SwiftUI

struct GlassSearchBar: View {
    @Binding var keyword: String
    let onSearch: () -> Void
    let compact: Bool
    @FocusState private var isFocused: Bool

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(.secondary)
                .font(compact ? .body : .title3)

            TextField("输入剧名、电影名...", text: $keyword)
                .textFieldStyle(.plain)
                .font(compact ? .body : .title3)
                .onSubmit(onSearch)
                .focused($isFocused)

            if !keyword.isEmpty {
                Button(action: { keyword = "" }) {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
            }

            Button(action: onSearch) {
                Image(systemName: "arrow.right")
                    .font(.system(size: compact ? 12 : 14, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: compact ? 28 : 36, height: compact ? 28 : 36)
                    .background(Color.accentColor)
                    .clipShape(Circle())
            }
            .buttonStyle(.plain)
            .disabled(keyword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .padding(.horizontal, compact ? 12 : 18)
        .padding(.vertical, compact ? 8 : 14)
        .background(
            RoundedRectangle(cornerRadius: compact ? 12 : 18, style: .continuous)
                .fill(.ultraThinMaterial)
                .overlay(
                    RoundedRectangle(cornerRadius: compact ? 12 : 18, style: .continuous)
                        .stroke(Color.secondary.opacity(0.18), lineWidth: 1)
                )
        )
    }
}
