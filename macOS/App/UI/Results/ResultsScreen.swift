import SwiftUI

struct ResultsScreen: View {
    let works: [VideoWork]
    let isSearching: Bool
    let selectedResult: SearchResult?
    let isFetching: Bool
    let coverFallbacks: [String: URL]
    let onLoadCoverFallback: (SearchResult) async -> Void
    let onSearch: () -> Void
    let onSelectResult: (SearchResult) -> Void
    let searchErrorMessage: String?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @ViewBuilder
    var body: some View {
        if isSearching && works.isEmpty {
            Color.clear
        } else if let searchErrorMessage {
            AppFailureState(message: searchErrorMessage, onRetry: onSearch)
        } else if works.isEmpty {
            ContentUnavailableView("未找到相关作品", systemImage: "magnifyingglass")
                .font(.title3)
        } else {
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 200), spacing: AppSpacing.md, alignment: .top)], spacing: AppSpacing.md)
                {
                    ForEach(works) { work in
                        if let result = work.defaultSource {
                            SearchResultCard(
                                work: work,
                                result: result,
                                isSelected: selectedResult?.id == result.id,
                                isLoading: selectedResult?.id == result.id && isFetching,
                                coverFallbacks: coverFallbacks,
                                onLoadCoverFallback: onLoadCoverFallback,
                                onTap: { onSelectResult(result) }
                            )
                            .transition(.opacity)
                        }
                    }
                }
                .animation(reduceMotion ? nil : AppMotion.control, value: works.map(\.id))
                .frame(maxWidth: AppMetrics.contentWidth)
                .padding(AppSpacing.lg)
                .frame(maxWidth: .infinity)
            }
        }
    }
}
