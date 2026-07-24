// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "GatorWebSockets",
    platforms: [
        .iOS(.v13),
        .macOS(.v10_15)
    ],
    products: [
        .library(name: "GatorWebSockets", targets: ["GatorWebSockets"])
    ],
    targets: [
        .target(
            name: "GatorWebSockets",
            path: "clients/ios/Sources/GatorWebSockets"
        ),
        .testTarget(
            name: "GatorWebSocketsTests",
            dependencies: ["GatorWebSockets"],
            path: "clients/ios/Tests/GatorWebSocketsTests"
        )
    ]
)
