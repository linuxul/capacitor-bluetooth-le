// swift-tools-version: 5.9
import Foundation
import PackageDescription

// Apps override this dependency with the @capacitor/ios they installed. To build this package on its own
// against a local runtime, point CAPACITOR_IOS_PATH at it.
let capacitor: Package.Dependency
if let path = ProcessInfo.processInfo.environment["CAPACITOR_IOS_PATH"] {
    capacitor = .package(name: "capacitor-swift-pm", path: path)
} else {
    capacitor = .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "8.0.0")
}

let package = Package(
    name: "CapacitorCommunityBluetoothLe",
    platforms: [.iOS(.v17)],
    products: [
        .library(
            name: "CapacitorCommunityBluetoothLe",
            targets: ["BluetoothLe"])
    ],
    dependencies: [capacitor],
    targets: [
        .target(
            name: "BluetoothLe",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm")
            ],
            path: "ios/Sources/BluetoothLe"),
        .testTarget(
            name: "BluetoothLeTests",
            dependencies: ["BluetoothLe"],
            path: "ios/Tests/BluetoothLeTests")
    ]
)