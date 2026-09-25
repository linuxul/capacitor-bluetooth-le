import XCTest
import Capacitor
@testable import BluetoothLe

// These tests never call initialize: creating a CBCentralManager needs the host app's Bluetooth usage description.
class PluginTests: XCTestCase {
    private let timeout: TimeInterval = 20

    func testEveryMethodIsRegisteredAsAPromise() {
        let methods = BluetoothLe().pluginMethods
        XCTAssertEqual(methods.map(\.name), [
            "initialize", "isEnabled", "requestEnable", "enable", "disable", "startEnabledNotifications",
            "stopEnabledNotifications", "isLocationEnabled", "openLocationSettings", "openBluetoothSettings",
            "openAppSettings", "setDisplayStrings", "requestDevice", "requestLEScan", "stopLEScan", "getDevices",
            "discoverServices", "getConnectedDevices", "connect", "createBond", "isBonded", "getBondedDevices",
            "disconnect", "getServices", "getMtu", "requestConnectionPriority", "readRssi", "read", "write",
            "writeWithoutResponse", "readDescriptor", "writeDescriptor", "startNotifications", "stopNotifications"
        ])
        XCTAssertTrue(methods.allSatisfy { $0.returnType == .promise })
    }

    func testMethodsWithoutAnIOSImplementationThrowUnavailable() {
        let plugin = BluetoothLe()
        let methods: [(String, (CAPPluginCall) throws -> Void)] = [
            ("requestEnable", plugin.requestEnable),
            ("enable", plugin.enable),
            ("disable", plugin.disable),
            ("isLocationEnabled", plugin.isLocationEnabled),
            ("openLocationSettings", plugin.openLocationSettings),
            ("openBluetoothSettings", plugin.openBluetoothSettings),
            ("createBond", plugin.createBond),
            ("isBonded", plugin.isBonded),
            ("getBondedDevices", plugin.getBondedDevices),
            ("requestConnectionPriority", plugin.requestConnectionPriority)
        ]
        for (name, method) in methods {
            XCTAssertThrowsError(try method(unansweredCall(name))) { error in
                XCTAssertEqual((error as? CAPPluginError)?.message, "\(name) is not available on iOS.")
                XCTAssertEqual((error as? CAPPluginError)?.code, "UNAVAILABLE")
            }
        }
    }

    @MainActor
    func testReadsBeforeInitializeThrow() async {
        let plugin = BluetoothLe()
        do {
            _ = try await plugin.isEnabled(unansweredCall("isEnabled"))
            XCTFail("isEnabled must throw")
        } catch {
            assertNotInitialized(error)
        }
        do {
            _ = try await plugin.getMtu(unansweredCall("getMtu", ["deviceId": "device"]))
            XCTFail("getMtu must throw")
        } catch {
            assertNotInitialized(error)
        }
        do {
            try await plugin.getServices(unansweredCall("getServices", ["deviceId": "device"]))
            XCTFail("getServices must throw")
        } catch {
            assertNotInitialized(error)
        }
    }

    func testDeviceOperationsBeforeInitializeAreRejectedOnTheMainQueueInCallOrder() {
        let plugin = BluetoothLe()
        let count = 20
        let rejected = expectation(description: "every call is rejected")
        rejected.expectedFulfillmentCount = count
        var order: [Int] = []
        for index in 0..<count {
            let options: JSObject = ["deviceId": "device", "service": "180d", "characteristic": "2a37", "value": "01"]
            let call = CAPPluginCall(callbackId: "\(index)", methodName: "writeWithoutResponse", options: options, success: { _, _ in
                XCTFail("writeWithoutResponse must not resolve")
            }, error: { error in
                XCTAssertTrue(Thread.isMainThread)
                XCTAssertEqual(error.message, "Bluetooth LE not initialized.")
                XCTAssertNil(error.code)
                order.append(index)
                rejected.fulfill()
            })
            plugin.writeWithoutResponse(call)
        }
        wait(for: [rejected], timeout: timeout)
        XCTAssertEqual(order, Array(0..<count))
    }

    private func assertNotInitialized(_ error: Error, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual((error as? CAPPluginError)?.message, "Bluetooth LE not initialized.", file: file, line: line)
        XCTAssertNil((error as? CAPPluginError)?.code, file: file, line: line)
    }

    /// A call the method under test must answer by throwing.
    private func unansweredCall(_ name: String, _ options: JSObject = [:]) -> CAPPluginCall {
        CAPPluginCall(callbackId: "test", methodName: name, options: options, success: { _, _ in
            XCTFail("\(name) must not resolve")
        }, error: { _ in
            XCTFail("\(name) answers by throwing")
        })
    }
}
