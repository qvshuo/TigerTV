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
    static let lg: CGFloat = 24
    static let xl: CGFloat = 32
}

// MARK: - Motion

enum AppMotion {
    static var page: Animation { .spring(duration: 0.42, bounce: 0.12) }
    static var hover: Animation { .smooth(duration: 0.18) }
    static var select: Animation { .snappy(duration: 0.22) }
    static var press: Animation { .easeOut(duration: 0.12) }
    static var stagger: Animation { .spring(duration: 0.38, bounce: 0.08) }
}

// MARK: - Glass Surface

struct GlassBackground: ViewModifier {
    var radius: CGFloat = AppRadius.md
    var strokeOpacity: Double = 0.16
    var shadowRadius: CGFloat = 6
    var isActive: Bool = false
    var activeStrokeOpacity: Double = 0.55
    
    func body(content: Content) -> some View {
        content
            .background {
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .fill(.clear)
                    .glassEffect(.regular, in: .rect(cornerRadius: radius))
            }
            .overlay(
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .stroke(
                        isActive
                            ? Color.accentColor.opacity(activeStrokeOpacity)
                            : Color.secondary.opacity(strokeOpacity),
                        lineWidth: isActive ? 1.5 : 0.5
                    )
            )
            .shadow(
                color: isActive
                    ? Color.accentColor.opacity(0.06)
                    : Color.black.opacity(0.03),
                radius: isActive ? 14 : shadowRadius,
                x: 0,
                y: isActive ? 4 : 2
            )
    }
}

// MARK: - Hover Lift

struct HoverLift: ViewModifier {
    @State private var isHovered = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    
    func body(content: Content) -> some View {
        content
            .scaleEffect(reduceMotion ? 1.0 : (isHovered ? 1.04 : 1.0))
            .offset(y: reduceMotion ? 0 : (isHovered ? -1.5 : 0))
            .animation(AppMotion.hover, value: isHovered)
            .onHover { isHovered = $0 }
    }
}

// MARK: - View Extensions

extension View {
    func glassBackground(
        radius: CGFloat = AppRadius.md,
        strokeOpacity: Double = 0.16,
        shadowRadius: CGFloat = 6,
        isActive: Bool = false,
        activeStrokeOpacity: Double = 0.55
    ) -> some View {
        modifier(GlassBackground(
            radius: radius,
            strokeOpacity: strokeOpacity,
            shadowRadius: shadowRadius,
            isActive: isActive,
            activeStrokeOpacity: activeStrokeOpacity
        ))
    }
    
    func hoverLift() -> some View {
        modifier(HoverLift())
    }
}

// Shared dimensions for page loading and detail controls.
enum AppMetrics {
    static let controlHeight: CGFloat = 40
    static let loadingRegion: CGFloat = 160
    static let posterWidth: CGFloat = 144
    static let posterHeight: CGFloat = 216
}

struct AppLoadingIndicator: View {
    var compact = false
    var light = false
    var body: some View {
        Image(systemName: "progress.indicator")
            .font(.system(size: compact ? 16 : 24))
            .foregroundStyle(light ? Color.white : Color.secondary)
            .symbolEffect(.variableColor.iterative.dimInactiveLayers, options: .repeating)
            .environment(\.isEnabled, true)
            .frame(width: compact ? 16 : 24, height: compact ? 16 : 24)
            .accessibilityLabel("加载中")
    }
}

struct AppLoadingState: View {
    let message: String
    var body: some View {
        VStack(spacing: AppSpacing.sm) {
            AppLoadingIndicator()
            Text(message).font(.callout).foregroundStyle(.secondary)
                .multilineTextAlignment(.center).lineLimit(2)
        }
        .padding(AppSpacing.lg)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .combine)
    }
}
