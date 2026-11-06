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
            path: "App/Data",
            exclude: ["SharedCoreRepository.swift"]
        ),
        .testTarget(
            name: "TigerTVTests",
            dependencies: ["TigerTVKit"],
            path: "TigerTVTests"
        ),
    ]
)
