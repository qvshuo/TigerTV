import SwiftUI

struct HomeScreen: View {
    @Binding var keyword: String
    let configErrorMessage: String?
    let history: [String]
    let onSearch: () -> Void
    let onSelectHistory: (String) -> Void
    let onDeleteHistory: (String) -> Void
    let onClearHistory: () -> Void
    let onRetryConfig: () -> Void
    var hotSearches: [String] = []
    var hotCategories: [HotSearchCategory] = []
    var selectedHotCategory = ""
    var isLoadingHotSearches = false
    var onSelectHotCategory: (String) -> Void = { _ in }
    var onRetryHotSearches: () -> Void = {}
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ScrollView {
            VStack(spacing: AppSpacing.xl) {
                VStack(spacing: AppSpacing.sm) {
                    Text("小老虎爱看剧").font(.system(size: 46, weight: .bold, design: .rounded))
                    Text("全网好剧，一搜即达").font(.title2).foregroundStyle(.secondary)
                }
                VStack(spacing: AppSpacing.md) {
                    SearchBar(keyword: $keyword, onSearch: onSearch, compact: false)
                    if let configErrorMessage {
                        GroupBox {
                            AppFailureState(message: configErrorMessage, onRetry: onRetryConfig, compact: true)
                                .padding(AppSpacing.sm)
                        }
                    }
                    if !history.isEmpty {
                        SearchHistoryView(
                            history: history, onSelect: onSelectHistory,
                            onDelete: onDeleteHistory, onClear: onClearHistory)
                    }
                    if !hotCategories.isEmpty {
                        HotSearchSection(
                            titles: hotSearches, categories: hotCategories,
                            selectedCategory: selectedHotCategory, isLoading: isLoadingHotSearches,
                            onSelectCategory: onSelectHotCategory, onSearch: onSelectHistory,
                            onRetry: onRetryHotSearches)
                    }
                }
                .animation(reduceMotion ? nil : AppMotion.control, value: history.isEmpty)
                .animation(reduceMotion ? nil : AppMotion.control, value: configErrorMessage)
            }
            .frame(maxWidth: 560)
            .padding(AppSpacing.lg)
            .padding(.top, AppSpacing.xl + AppSpacing.lg * 3)
            .frame(maxWidth: .infinity)
        }
    }
}

struct HotSearchSection: View {
    let titles: [String]
    let categories: [HotSearchCategory]
    let selectedCategory: String
    let isLoading: Bool
    let onSelectCategory: (String) -> Void
    let onSearch: (String) -> Void
    let onRetry: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        GroupBox {
            VStack(alignment: .leading, spacing: AppSpacing.sm) {
                Text("豆瓣热门").font(.caption).foregroundStyle(.secondary)
                ViewThatFits(in: .horizontal) {
                    categoryButtons
                    ScrollView(.horizontal) { categoryButtons }.scrollIndicators(.hidden)
                }
                .frame(height: AppMetrics.compactSearchHeight)
                Divider().opacity(0.5)
                Group {
                    if isLoading {
                        AppLoadingState(message: "正在加载热门内容…", compact: true)
                            .frame(height: AppMetrics.hotSearchHeight)
                    } else if titles.isEmpty {
                        AppFailureState(message: "热门内容暂不可用，请重试", onRetry: onRetry, compact: true)
                    } else {
                        ScrollView {
                            FlowLayout(spacing: AppSpacing.sm, lineSpacing: AppSpacing.sm) {
                                ForEach(titles, id: \.self) { title in
                                    Button {
                                        onSearch(title)
                                    } label: {
                                        Text(title).font(.callout).lineLimit(1)
                                            .padding(.horizontal, AppSpacing.xs)
                                    }
                                    .controlSize(.large)
                                    .buttonStyle(.glass)
                                    .buttonBorderShape(.capsule)
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)

                        }
                    }
                }
                .frame(height: AppMetrics.hotSearchHeight, alignment: .top)
                .animation(reduceMotion ? nil : AppMotion.control, value: isLoading)

            }
            .padding(AppSpacing.sm)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var categoryButtons: some View {
        GlassEffectContainer(spacing: 0) {
            HStack(spacing: AppSpacing.sm) {
                ForEach(categories) { category in
                    Button {
                        onSelectCategory(category.id)
                    } label: {
                        Text(category.name)
                            .font(.callout.weight(category.id == selectedCategory ? .semibold : .regular))
                            .frame(width: 42)
                    }
                    .buttonStyle(.glass)
                    .buttonBorderShape(.capsule)
                    .controlSize(.large)
                    .tint(category.id == selectedCategory ? Color.accentColor : nil)
                    .accessibilityAddTraits(category.id == selectedCategory ? .isSelected : [])
                }
            }
            .fixedSize(horizontal: true, vertical: false)
        }
        .frame(maxWidth: .infinity)
        .animation(reduceMotion ? nil : AppMotion.control, value: selectedCategory)
    }

}
