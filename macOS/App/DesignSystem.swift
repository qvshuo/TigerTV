import SwiftUI

// MARK: - Spacing

enum AppSpacing {
    static let xs: CGFloat = 6
    static let sm: CGFloat = 10
    static let md: CGFloat = 16
    static let lg: CGFloat = 24
    static let xl: CGFloat = 36
}

// MARK: - Radius

enum AppRadius {
    static let sm: CGFloat = 12
    static let md: CGFloat = 20
    static let lg: CGFloat = 28
    static let xl: CGFloat = 34
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
            .background(
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .fill(.ultraThinMaterial)
            )
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
