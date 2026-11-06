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
