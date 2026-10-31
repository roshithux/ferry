// swift-tools-version:5.10
import PackageDescription

let package = Package(
    name: "Ferry",
    platforms: [.macOS(.v14)],
    targets: [
        .executableTarget(name: "Ferry", path: "Sources/Ferry")
    ]
)
