import SwiftUI

// MARK: - Spacing

enum AppSpacing {
    static let xs: CGFloat = 4
    static let sm: CGFloat = 8
    static let md: CGFloat = 16
    static let lg: CGFloat = 24
    static let xl: CGFloat = 32
}

// MARK: - Radius

enum AppRadius {
    static let sm: CGFloat = 12
    static let md: CGFloat = 20
}

// MARK: - Motion

enum AppMotion {
    static var page: Animation { .smooth(duration: 0.25) }
    static var control: Animation { .smooth(duration: 0.18) }
}

// Shared dimensions for page loading and detail controls.
enum AppMetrics {
    static let homeSearchHeight: CGFloat = 44
    static let compactSearchHeight: CGFloat = 28
    static let hotSearchHeight: CGFloat = 160
    static let contentWidth: CGFloat = 1120
    static let resourceStatusHeight: CGFloat = controlHeight + AppSpacing.md
    static let controlHeight: CGFloat = 40
    static let loadingRegion: CGFloat = 160
    static let posterWidth: CGFloat = 144
    static let posterHeight: CGFloat = 216
}

struct AppLoadingIndicator: View {
    var compact = false
    var light = false
    var body: some View {
        ProgressView()
            .progressViewStyle(.circular)
            .controlSize(compact ? .small : .regular)
            .tint(light ? Color.white : nil)
            .frame(width: compact ? 16 : 24, height: compact ? 16 : 24)
            .accessibilityLabel("加载中")
    }
}

struct AppLoadingState: View {
    let message: String
    var compact = false
    var body: some View {
        VStack(spacing: AppSpacing.sm) {
            AppLoadingIndicator(compact: compact)
            Text(message).font(.callout).foregroundStyle(.secondary)
                .multilineTextAlignment(.center).lineLimit(2)
        }
        .padding(compact ? AppSpacing.xs : AppSpacing.lg)
        .frame(maxWidth: .infinity)
        .frame(maxHeight: compact ? nil : .infinity)
        .accessibilityElement(children: .combine)
    }
}

struct AppFailureState: View {
    let message: String
    let onRetry: () -> Void
    var compact = false

    var body: some View {
        Group {
            if compact {
                HStack(spacing: AppSpacing.md) {
                    Text(message).font(.callout).foregroundStyle(.red)
                    Spacer(minLength: AppSpacing.sm)
                    Button("重试", action: onRetry).buttonStyle(.bordered)
                }
            } else {
                VStack(spacing: AppSpacing.md) {
                    Text(message).foregroundStyle(.secondary)
                    Button("重试", action: onRetry).buttonStyle(.bordered)
                }
            }
        }
        .frame(maxWidth: .infinity, minHeight: compact ? AppMetrics.resourceStatusHeight : AppMetrics.loadingRegion)
    }
}
