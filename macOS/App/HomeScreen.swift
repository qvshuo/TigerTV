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
    var hotSearches: [HotSearchItem] = []
    var hotCategories: [HotSearchCategory] = []
    var selectedHotCategory = ""
    var isLoadingHotSearches = false
    var onSelectHotCategory: (String) -> Void = { _ in }
    var onRetryHotSearches: () -> Void = {}
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ScrollView {
            VStack(spacing: AppSpacing.md) {
                SearchBar(keyword: $keyword, onSearch: onSearch, compact: false)
                if let configErrorMessage {
                    GroupBox {
                        AppFailureState(
                            message: configErrorMessage, onRetry: onRetryConfig, compact: true
                        )
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
            .frame(maxWidth: AppMetrics.contentWidth)
            .padding(AppSpacing.lg)
            .padding(.top, AppSpacing.md)
            .frame(maxWidth: .infinity)
        }
    }
}

struct HotSearchSection: View {
    let titles: [HotSearchItem]
    let categories: [HotSearchCategory]
    let selectedCategory: String
    let isLoading: Bool
    let onSelectCategory: (String) -> Void
    let onSearch: (String) -> Void
    let onRetry: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var images: [String: NSImage] = [:]
    @State private var columns = 5
    @State private var contentHeight = AppMetrics.hotSearchHeight

    var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.md) {
            Text("豆瓣热门").font(.headline).foregroundStyle(.primary)
            ViewThatFits(in: .horizontal) {
                categoryButtons
                ScrollView(.horizontal) { categoryButtons }.scrollIndicators(.hidden)
            }
            .frame(minHeight: AppMetrics.controlHeight)
            Group {
                if isLoading {
                    AppLoadingState(message: "正在加载热门内容…", compact: true)
                        .frame(height: contentHeight)
                } else if titles.isEmpty {
                    AppFailureState(message: "热门内容暂不可用，请重试", onRetry: onRetry, compact: true)
                        .frame(height: contentHeight)
                } else {
                    LazyVGrid(
                        columns: [
                            GridItem(
                                .adaptive(minimum: 200), spacing: AppSpacing.md, alignment: .top)
                        ],
                        spacing: AppSpacing.md
                    ) {
                        ForEach(titles) { item in
                            Button {
                                onSearch(item.title)
                            } label: {
                                GroupBox {
                                    MediaCardContent(
                                        title: item.year.isEmpty
                                            ? item.title : "\(item.title) (\(item.year))",
                                        footer: item.status
                                    ) {
                                        Color.secondary.opacity(0.1).overlay {
                                            if let image = images[item.id] {
                                                Image(nsImage: image).resizable().scaledToFill()
                                            } else {
                                                Text(String(item.title.prefix(1))).font(.largeTitle)
                                                    .foregroundStyle(
                                                        .secondary)
                                            }
                                        }
                                        .aspectRatio(2.0 / 3.0, contentMode: .fit)
                                        .clipped().clipShape(
                                            RoundedRectangle(cornerRadius: AppRadius.sm))
                                    } information: {
                                        Text(
                                            item.rating.map {
                                                "豆瓣 " + String(format: "%.1f", $0)
                                                    + " · \(item.ratingCount)人"
                                            } ?? "暂无评分"
                                        )
                                        .foregroundStyle(.secondary)
                                    }
                                }
                            }.buttonStyle(.plain).modifier(MediaCardHover())
                        }
                    }
                    .onGeometryChange(for: CGFloat.self) { $0.size.height } action: {
                        contentHeight = max(AppMetrics.hotSearchHeight, $0)
                    }
                    .onGeometryChange(for: Int.self) {
                        max(1, Int(($0.size.width + 16) / 216))
                    } action: {
                        columns = $0
                    }
                    .task(
                        id:
                            "\(selectedCategory):\(columns):\(titles.map(\.id).joined(separator: ","))"
                    ) {
                        let batchSize = columns
                        let items = titles
                        images = [:]
                        for start in stride(from: 0, to: items.count, by: batchSize) {
                            guard !Task.isCancelled else { return }
                            let row = Array(items[start..<min(start + batchSize, items.count)])
                            await withTaskGroup(of: (String, NSImage?).self) { group in
                                for item in row {
                                    group.addTask {
                                        guard
                                            let url = HTTPClient.percentEncodedURL(
                                                from: item.poster)
                                        else {
                                            return (item.id, nil)
                                        }
                                        return (
                                            item.id, await CoverImageCache.shared.image(for: url)
                                        )
                                    }
                                }
                                for await (id, image) in group {
                                    guard !Task.isCancelled else { continue }
                                    images[id] = image
                                }
                            }
                        }
                    }
                }
            }
            .frame(minHeight: AppMetrics.hotSearchHeight, alignment: .top)

        }
        .padding(AppSpacing.sm)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var categoryButtons: some View {
        GlassEffectContainer(spacing: 0) {
            HStack(spacing: AppSpacing.sm) {
                ForEach(categories) { category in
                    Button {
                        onSelectCategory(category.id)
                    } label: {
                        Text(category.name)
                            .font(
                                .callout.weight(
                                    category.id == selectedCategory ? .semibold : .regular)
                            )
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
