import SwiftUI

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
