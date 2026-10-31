// swift-tools-version: 6.4

import PackageDescription

let package = Package(
    name: "TigerTV",
    platforms: [.macOS("26.0")],
    products: [
        .library(name: "TigerTVKit", targets: ["TigerTVKit"]),
    ],
    targets: [
        .target(
            name: "TigerTVKit",
            path: "App",
            exclude: [
                "TigerTVApp.swift",
                "ContentView.swift",
                "HomeScreen.swift",
                "ResultsLayout.swift",
                "SearchBar.swift",
                "SearchHistoryView.swift",
                "SearchResultCard.swift",
                "DesignSystem.swift",
                "PlayerView.swift",
                "TigerTVViewModel.swift",
                "SharedCoreRepository.swift",
                "Assets.xcassets",
                "Info.plist",
                "TigerTV.entitlements",
                "Preview Content",
            ]
        ),
        .testTarget(
            name: "TigerTVTests",
            dependencies: ["TigerTVKit"],
            path: "TigerTVTests"
        ),
    ]
)
