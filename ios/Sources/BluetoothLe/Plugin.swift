// swiftlint:disable identifier_name
// swiftlint:disable type_body_length
import Foundation
import UIKit
import Capacitor
import CoreBluetooth

let CONNECTION_TIMEOUT: Double = 10
let DEFAULT_TIMEOUT: Double = 5

@objc(BluetoothLe)
public class BluetoothLe: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "BluetoothLe"
    public let jsName = "BluetoothLe"
    // The methods that change the state of the plugin, the scan or a device stay synchronous: the bridge queue calls
    // them in the order of the calls and each hands its work to the main queue in that order, which keeps the
    // operations on a device in call order (a burst of writeWithoutResponse, connect after getDevices, stopLEScan
    // after requestLEScan) even when the JavaScript queue is disabled. Async methods would not keep that order, so
    // only the reads and openAppSettings are async.
    public let pluginMethods: [CAPPluginMethod] = [
        .promise("initialize", BluetoothLe.initialize(_:)),
        .async("isEnabled", BluetoothLe.isEnabled),
        .promise("requestEnable", BluetoothLe.requestEnable),
        .promise("enable", BluetoothLe.enable),
        .promise("disable", BluetoothLe.disable),
        .promise("startEnabledNotifications", BluetoothLe.startEnabledNotifications),
        .promise("stopEnabledNotifications", BluetoothLe.stopEnabledNotifications),
        .promise("isLocationEnabled", BluetoothLe.isLocationEnabled),
        .promise("openLocationSettings", BluetoothLe.openLocationSettings),
        .promise("openBluetoothSettings", BluetoothLe.openBluetoothSettings),
        .async("openAppSettings", BluetoothLe.openAppSettings),
        .promise("setDisplayStrings", BluetoothLe.setDisplayStrings),
        .promise("requestDevice", BluetoothLe.requestDevice),
        .promise("requestLEScan", BluetoothLe.requestLEScan),
        .promise("stopLEScan", BluetoothLe.stopLEScan),
        .promise("getDevices", BluetoothLe.getDevices),
        .promise("discoverServices", BluetoothLe.discoverServices),
        .promise("getConnectedDevices", BluetoothLe.getConnectedDevices),
        .promise("connect", BluetoothLe.connect),
        .promise("createBond", BluetoothLe.createBond),
        .promise("isBonded", BluetoothLe.isBonded),
        .promise("getBondedDevices", BluetoothLe.getBondedDevices),
        .promise("disconnect", BluetoothLe.disconnect),
        .async("getServices", BluetoothLe.getServices),
        .async("getMtu", BluetoothLe.getMtu),
        .promise("requestConnectionPriority", BluetoothLe.requestConnectionPriority),
        .promise("readRssi", BluetoothLe.readRssi),
        .promise("read", BluetoothLe.read),
        .promise("write", BluetoothLe.write),
        .promise("writeWithoutResponse", BluetoothLe.writeWithoutResponse),
        .promise("readDescriptor", BluetoothLe.readDescriptor),
        .promise("writeDescriptor", BluetoothLe.writeDescriptor),
        .promise("startNotifications", BluetoothLe.startNotifications),
        .promise("stopNotifications", BluetoothLe.stopNotifications)
    ]
    typealias BleDevice = [String: Any]
    typealias BleService = [String: Any]
    typealias BleCharacteristic = [String: Any]
    typealias BleDescriptor = [String: Any]
    private var deviceManager: DeviceManager?
    private var deviceMap = [String: Device]()
    private var displayStrings = [String: String]()

    override public func load() {
        self.displayStrings = self.getDisplayStrings()
    }

    func initialize(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            self.deviceManager = DeviceManager(self.bridge?.viewController, self.displayStrings, {(success, message) in
                if success {
                    call.resolve()
                } else {
                    call.reject(message)
                }
            })
        }
    }

    @MainActor
    func isEnabled(_ call: CAPPluginCall) async throws -> JSObject {
        let deviceManager = try self.getDeviceManager()
        return ["value": deviceManager.isEnabled()]
    }

    func requestEnable(_ call: CAPPluginCall) throws {
        throw CAPPluginError.unavailable("requestEnable is not available on iOS.")
    }

    func enable(_ call: CAPPluginCall) throws {
        throw CAPPluginError.unavailable("enable is not available on iOS.")
    }

    func disable(_ call: CAPPluginCall) throws {
        throw CAPPluginError.unavailable("disable is not available on iOS.")
    }

    func startEnabledNotifications(_ call: CAPPluginCall) {
        onMainQueue(call) {
            let deviceManager = try self.getDeviceManager()
            deviceManager.registerStateReceiver({(enabled) in
                self.notifyListeners("onEnabledChanged", data: ["value": enabled])
            })
            call.resolve()
        }
    }

    func stopEnabledNotifications(_ call: CAPPluginCall) {
        onMainQueue(call) {
            let deviceManager = try self.getDeviceManager()
            deviceManager.unregisterStateReceiver()
            call.resolve()
        }
    }

    func isLocationEnabled(_ call: CAPPluginCall) throws {
        throw CAPPluginError.unavailable("isLocationEnabled is not available on iOS.")
    }

    func openLocationSettings(_ call: CAPPluginCall) throws {
        throw CAPPluginError.unavailable("openLocationSettings is not available on iOS.")
    }

    func openBluetoothSettings(_ call: CAPPluginCall) throws {
        throw CAPPluginError.unavailable("openBluetoothSettings is not available on iOS.")
    }

    @MainActor
    func openAppSettings(_ call: CAPPluginCall) async throws -> JSObject {
        guard let settingsUrl = URL(string: UIApplication.openSettingsURLString),
              UIApplication.shared.canOpenURL(settingsUrl) else {
            throw CAPPluginError("Cannot open app settings.")
        }
        let success = await UIApplication.shared.open(settingsUrl)
        return ["value": success]
    }

    func setDisplayStrings(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            for key in ["noDeviceFound", "availableDevices", "scanning", "cancel"] {
                if let value = call.getString(key) {
                    self.displayStrings[key] = value
                }
            }
            call.resolve()
        }
    }

    // requestDevice stays synchronous although it presents the device list: its result arrives through the device
    // manager's "startScanning" callback, which requestLEScan replaces and which is dropped with the manager when
    // initialize runs again, so it is not called exactly once; and it starts a scan that must stay ordered with
    // requestLEScan and stopLEScan.
    func requestDevice(_ call: CAPPluginCall) {
        onMainQueue(call) {
            let deviceManager = try self.getDeviceManager()
            deviceManager.setDisplayStrings(self.displayStrings)

            let serviceUUIDs = self.getServiceUUIDs(call)
            let name = call.getString("name")
            let namePrefix = call.getString("namePrefix")
            let manufacturerDataFilters = self.getManufacturerDataFilters(call)
            let serviceDataFilters = self.getServiceDataFilters(call)

            let displayModeString = (call.getString("displayMode") ?? "alert").lowercased()
            guard ["alert", "list"].contains(displayModeString) else {
                throw CAPPluginError("Invalid displayMode '\(call.getString("displayMode") ?? "")'. Use 'alert' or 'list'.")
            }
            let deviceListMode: DeviceListMode = displayModeString == "list" ? .list : .alert

            deviceManager.startScanning(
                serviceUUIDs,
                name,
                namePrefix,
                manufacturerDataFilters,
                serviceDataFilters,
                false,
                deviceListMode,
                30,
                {(success, message) in
                    if success {
                        guard let peripheral = deviceManager.getPeripheral(message) else {
                            call.reject("Device not found.")
                            return
                        }
                        let storedDevice = self.getOrCreateDevice(peripheral)
                        let bleDevice: BleDevice = self.getBleDevice(storedDevice)
                        call.resolve(bleDevice)
                    } else {
                        call.reject(message)
                    }
                },
                { (_, _, _) in }
            )
        }
    }

    func requestLEScan(_ call: CAPPluginCall) {
        onMainQueue(call) {
            let deviceManager = try self.getDeviceManager()

            let serviceUUIDs = self.getServiceUUIDs(call)
            let name = call.getString("name")
            let namePrefix = call.getString("namePrefix")
            let allowDuplicates = call.getBool("allowDuplicates", false)
            let manufacturerDataFilters = self.getManufacturerDataFilters(call)
            let serviceDataFilters = self.getServiceDataFilters(call)

            deviceManager.startScanning(
                serviceUUIDs,
                name,
                namePrefix,
                manufacturerDataFilters,
                serviceDataFilters,
                allowDuplicates,
                .none,
                nil,
                { (success, message) in
                    if success {
                        call.resolve()
                    } else {
                        call.reject(message)
                    }
                }, { (peripheral, advertisementData, rssi) in
                    let storedDevice = self.getOrCreateDevice(peripheral)
                    let data = self.getScanResult(storedDevice, advertisementData, rssi)
                    self.notifyListeners("onScanResult", data: data)
                }
            )
        }
    }

    func stopLEScan(_ call: CAPPluginCall) {
        onMainQueue(call) {
            let deviceManager = try self.getDeviceManager()
            deviceManager.stopScan()
            call.resolve()
        }
    }

    // getDevices and getConnectedDevices stay synchronous: they register the devices that connect and the other
    // device methods look up, so they must run before the calls that follow them.
    func getDevices(_ call: CAPPluginCall) {
        onMainQueue(call) {
            let deviceManager = try self.getDeviceManager()
            guard let deviceIds = call.getArray("deviceIds", String.self) else {
                throw CAPPluginError("deviceIds must be provided")
            }
            let deviceUUIDs: [UUID] = deviceIds.compactMap({ deviceId in
                return UUID(uuidString: deviceId)
            })
            let peripherals = deviceManager.getDevices(deviceUUIDs)
            let bleDevices: [BleDevice] = peripherals.map({peripheral in
                let device = self.getOrCreateDevice(peripheral)
                return self.getBleDevice(device)
            })
            call.resolve(["devices": bleDevices])
        }
    }

    func getConnectedDevices(_ call: CAPPluginCall) {
        onMainQueue(call) {
            let deviceManager = try self.getDeviceManager()
            guard let services = call.getArray("services", String.self) else {
                throw CAPPluginError("services must be provided")
            }
            let serviceUUIDs: [CBUUID] = services.compactMap({ service in
                return CBUUID(string: service)
            })
            let peripherals = deviceManager.getConnectedDevices(serviceUUIDs)
            let bleDevices: [BleDevice] = peripherals.map({peripheral in
                let device = self.getOrCreateDevice(peripheral)
                return self.getBleDevice(device)
            })
            call.resolve(["devices": bleDevices])
        }
    }

    func connect(_ call: CAPPluginCall) {
        onMainQueue(call) {
            let deviceManager = try self.getDeviceManager()
            let device = try self.getDevice(call, checkConnection: false)
            let timeout = self.getTimeout(call, defaultTimeout: CONNECTION_TIMEOUT)
            let skipDescriptorDiscovery = call.getBool("skipDescriptorDiscovery") ?? false
            let serviceFilter: [CBUUID]? = call.getArray("services", String.self)?
                .compactMap({ service in
                    return CBUUID(string: service)
                })
            device.setOnConnected(timeout, skipDescriptorDiscovery, {(success, message) in
                if success {
                    call.resolve()
                } else {
                    self.deviceManager?.cancelConnect(device)
                    call.reject(message)
                }
            })
            deviceManager.setOnDisconnected(device, {(_, _) in
                let key = "disconnected|\(device.getId())"
                self.notifyListeners(key, data: nil)
            })
            deviceManager.connect(device, timeout, serviceFilter, {(success, message) in
                if success {
                    log("Connected to peripheral. Waiting for service discovery.")
                } else {
                    device.cancelConnectTimeout()
                    call.reject(message)
                }
            })
        }
    }

    func createBond(_ call: CAPPluginCall) throws {
        throw CAPPluginError.unavailable("createBond is not available on iOS.")
    }

    func isBonded(_ call: CAPPluginCall) throws {
        throw CAPPluginError.unavailable("isBonded is not available on iOS.")
    }

    func getBondedDevices(_ call: CAPPluginCall) throws {
        throw CAPPluginError.unavailable("getBondedDevices is not available on iOS.")
    }

    func disconnect(_ call: CAPPluginCall) {
        onMainQueue(call) {
            let deviceManager = try self.getDeviceManager()
            let device = try self.getDevice(call, checkConnection: false)
            let timeout = self.getTimeout(call)
            deviceManager.disconnect(device, timeout, {(success, message) in
                if success {
                    call.resolve()
                } else {
                    call.reject(message)
                }
            })
        }
    }

    /// A read of the services discovered on a connected device: it runs on the main actor, where CoreBluetooth
    /// updates them, and needs no order with the other calls.
    @MainActor
    func getServices(_ call: CAPPluginCall) async throws {
        _ = try self.getDeviceManager()
        let device = try self.getDevice(call)
        let services = device.getServices()
        var bleServices = [BleService]()
        for service in services {
            var bleCharacteristics = [BleCharacteristic]()
            for characteristic in service.characteristics ?? [] {
                var bleDescriptors = [BleDescriptor]()
                for descriptor in characteristic.descriptors ?? [] {
                    bleDescriptors.append([
                        "uuid": cbuuidToString(descriptor.uuid)
                    ])
                }
                bleCharacteristics.append([
                    "uuid": cbuuidToString(characteristic.uuid),
                    "properties": self.getProperties(characteristic),
                    "descriptors": bleDescriptors
                ])
            }
            bleServices.append([
                "uuid": cbuuidToString(service.uuid),
                "characteristics": bleCharacteristics
            ])
        }
        call.resolve(["services": bleServices])
    }

    private func getProperties(_ characteristic: CBCharacteristic) -> [String: Bool] {
        return [
            "broadcast": characteristic.properties.contains(CBCharacteristicProperties.broadcast),
            "read": characteristic.properties.contains(CBCharacteristicProperties.read),
            "writeWithoutResponse": characteristic.properties.contains(CBCharacteristicProperties.writeWithoutResponse),
            "write": characteristic.properties.contains(CBCharacteristicProperties.write),
            "notify": characteristic.properties.contains(CBCharacteristicProperties.notify),
            "indicate": characteristic.properties.contains(CBCharacteristicProperties.indicate),
            "authenticatedSignedWrites": characteristic.properties.contains(CBCharacteristicProperties.authenticatedSignedWrites),
            "extendedProperties": characteristic.properties.contains(CBCharacteristicProperties.extendedProperties),
            "notifyEncryptionRequired": characteristic.properties.contains(CBCharacteristicProperties.notifyEncryptionRequired),
            "indicateEncryptionRequired": characteristic.properties.contains(CBCharacteristicProperties.indicateEncryptionRequired)
        ]
    }

    func discoverServices(_ call: CAPPluginCall) {
        onMainQueue(call) {
            _ = try self.getDeviceManager()
            let device = try self.getDevice(call)
            let timeout = self.getTimeout(call)
            device.discoverServices(timeout, {(success, value) in
                if success {
                    call.resolve()
                } else {
                    call.reject(value)
                }
            })
        }
    }

    @MainActor
    func getMtu(_ call: CAPPluginCall) async throws -> JSObject {
        _ = try self.getDeviceManager()
        let device = try self.getDevice(call)
        return ["value": device.getMtu()]
    }

    func requestConnectionPriority(_ call: CAPPluginCall) throws {
        throw CAPPluginError.unavailable("requestConnectionPriority is not available on iOS.")
    }

    func readRssi(_ call: CAPPluginCall) {
        onMainQueue(call) {
            _ = try self.getDeviceManager()
            let device = try self.getDevice(call)
            let timeout = self.getTimeout(call)
            device.readRssi(timeout, {(success, value) in
                if success {
                    call.resolve([
                        "value": value
                    ])
                } else {
                    call.reject(value)
                }
            })
        }
    }

    func read(_ call: CAPPluginCall) {
        onMainQueue(call) {
            _ = try self.getDeviceManager()
            let device = try self.getDevice(call)
            let characteristic = try self.getCharacteristic(call)
            let timeout = self.getTimeout(call)
            device.read(characteristic.0, characteristic.1, timeout, {(success, value) in
                if success {
                    call.resolve([
                        "value": value
                    ])
                } else {
                    call.reject(value)
                }
            })
        }
    }

    func write(_ call: CAPPluginCall) {
        onMainQueue(call) {
            _ = try self.getDeviceManager()
            let device = try self.getDevice(call)
            let characteristic = try self.getCharacteristic(call)
            guard let value = call.getString("value") else {
                throw CAPPluginError("value must be provided")
            }
            let writeType = CBCharacteristicWriteType.withResponse
            let timeout = self.getTimeout(call)
            device.write(
                characteristic.0,
                characteristic.1,
                value,
                writeType,
                timeout, {(success, value) in
                    if success {
                        call.resolve()
                    } else {
                        call.reject(value)
                    }
                })
        }
    }

    func writeWithoutResponse(_ call: CAPPluginCall) {
        onMainQueue(call) {
            _ = try self.getDeviceManager()
            let device = try self.getDevice(call)
            let characteristic = try self.getCharacteristic(call)
            guard let value = call.getString("value") else {
                throw CAPPluginError("value must be provided")
            }
            let writeType = CBCharacteristicWriteType.withoutResponse
            let timeout = self.getTimeout(call)
            device.write(
                characteristic.0,
                characteristic.1,
                value,
                writeType,
                timeout, {(success, value) in
                    if success {
                        call.resolve()
                    } else {
                        call.reject(value)
                    }
                })
        }
    }

    func readDescriptor(_ call: CAPPluginCall) {
        onMainQueue(call) {
            _ = try self.getDeviceManager()
            let device = try self.getDevice(call)
            let descriptor = try self.getDescriptor(call)
            let timeout = self.getTimeout(call)
            device.readDescriptor(
                descriptor.0,
                descriptor.1,
                descriptor.2,
                timeout, {(success, value) in
                    if success {
                        call.resolve([
                            "value": value
                        ])
                    } else {
                        call.reject(value)
                    }
                })
        }
    }

    func writeDescriptor(_ call: CAPPluginCall) {
        onMainQueue(call) {
            _ = try self.getDeviceManager()
            let device = try self.getDevice(call)
            let descriptor = try self.getDescriptor(call)
            guard let value = call.getString("value") else {
                throw CAPPluginError("value must be provided")
            }
            let timeout = self.getTimeout(call)
            device.writeDescriptor(
                descriptor.0,
                descriptor.1,
                descriptor.2,
                value,
                timeout, {(success, value) in
                    if success {
                        call.resolve()
                    } else {
                        call.reject(value)
                    }
                })
        }
    }

    func startNotifications(_ call: CAPPluginCall) {
        onMainQueue(call) {
            _ = try self.getDeviceManager()
            let device = try self.getDevice(call)
            let characteristic = try self.getCharacteristic(call)
            let timeout = self.getTimeout(call)
            device.setNotifications(
                characteristic.0,
                characteristic.1,
                true, {(_, value) in
                    let key = "notification|\(device.getId())|\(characteristic.0.uuidString.lowercased())|\(characteristic.1.uuidString.lowercased())"
                    self.notifyListeners(key, data: ["value": value])
                },
                timeout, {(success, value) in
                    if success {
                        call.resolve()
                    } else {
                        call.reject(value)
                    }
                })
        }
    }

    func stopNotifications(_ call: CAPPluginCall) {
        onMainQueue(call) {
            _ = try self.getDeviceManager()
            let device = try self.getDevice(call)
            let characteristic = try self.getCharacteristic(call)
            let timeout = self.getTimeout(call)
            device.setNotifications(
                characteristic.0,
                characteristic.1,
                false,
                nil,
                timeout, {(success, value) in
                    if success {
                        call.resolve()
                    } else {
                        call.reject(value)
                    }
                })
        }
    }

    private func getDisplayStrings() -> [String: String] {
        let configDisplayStrings = getConfig().getObject("displayStrings") as? [String: String] ?? [String: String]()
        var displayStrings = [String: String]()
        displayStrings["noDeviceFound"] = configDisplayStrings["noDeviceFound"] ?? "No device found"
        displayStrings["availableDevices"] = configDisplayStrings["availableDevices"] ?? "Available devices"
        displayStrings["scanning"] = configDisplayStrings["scanning"] ?? "Scanning..."
        displayStrings["cancel"] = configDisplayStrings["cancel"] ?? "Cancel"
        return displayStrings
    }

    /// Runs `body` on the main queue, where CoreBluetooth calls the device manager and the devices back. The bridge
    /// calls the synchronous methods in the order of the calls and each hands its work to the main queue in that
    /// order, so the calls keep their order. What `body` throws rejects the call.
    private func onMainQueue(_ call: CAPPluginCall, _ body: @escaping () throws -> Void) {
        DispatchQueue.main.async {
            do {
                try body()
            } catch {
                call.reject(error)
            }
        }
    }

    private func getDeviceManager() throws -> DeviceManager {
        guard let deviceManager = self.deviceManager else {
            throw CAPPluginError("Bluetooth LE not initialized.")
        }
        return deviceManager
    }

    private func getServiceUUIDs(_ call: CAPPluginCall) -> [CBUUID] {
        let services = call.getArray("services", String.self) ?? []
        let serviceUUIDs = services.map({(service) -> CBUUID in
            return CBUUID(string: service)
        })
        return serviceUUIDs
    }

    private func getManufacturerDataFilters(_ call: CAPPluginCall) -> [ManufacturerDataFilter]? {
        guard let manufacturerDataArray = call.getArray("manufacturerData") else {
            return nil
        }

        var manufacturerDataFilters: [ManufacturerDataFilter] = []

        for index in 0..<manufacturerDataArray.count {
            guard let dataObject = manufacturerDataArray[index] as? JSObject,
                  let companyIdentifier = dataObject["companyIdentifier"] as? UInt16 else {
                return nil
            }

            let dataPrefix: Data? = {
                guard let prefixString = dataObject["dataPrefix"] as? String else { return nil }
                return stringToData(prefixString)
            }()

            let mask: Data? = {
                guard let maskString = dataObject["mask"] as? String else { return nil }
                return stringToData(maskString)
            }()

            let manufacturerFilter = ManufacturerDataFilter(
                companyIdentifier: companyIdentifier,
                dataPrefix: dataPrefix,
                mask: mask
            )

            manufacturerDataFilters.append(manufacturerFilter)
        }

        return manufacturerDataFilters
    }

    private func getServiceDataFilters(_ call: CAPPluginCall) -> [ServiceDataFilter]? {
        guard let serviceDataArray = call.getArray("serviceData") else {
            return nil
        }

        var serviceDataFilters: [ServiceDataFilter] = []

        for index in 0..<serviceDataArray.count {
            guard let dataObject = serviceDataArray[index] as? JSObject,
                  let serviceUuidString = dataObject["serviceUuid"] as? String else {
                return nil
            }

            let serviceUuid = CBUUID(string: serviceUuidString)

            let dataPrefix: Data? = {
                guard let prefixString = dataObject["dataPrefix"] as? String else { return nil }
                return stringToData(prefixString)
            }()

            let mask: Data? = {
                guard let maskString = dataObject["mask"] as? String else { return nil }
                return stringToData(maskString)
            }()

            let serviceDataFilter = ServiceDataFilter(
                serviceUuid: serviceUuid,
                dataPrefix: dataPrefix,
                mask: mask
            )

            serviceDataFilters.append(serviceDataFilter)
        }

        return serviceDataFilters
    }

    private func getDevice(_ call: CAPPluginCall, checkConnection: Bool = true) throws -> Device {
        guard let deviceId = call.getString("deviceId") else {
            throw CAPPluginError("deviceId required.")
        }
        guard let device = self.deviceMap[deviceId] else {
            throw CAPPluginError("Device not found. Call 'requestDevice', 'requestLEScan' or 'getDevices' first.")
        }
        if checkConnection {
            guard device.isConnected() else {
                throw CAPPluginError("Not connected to device.")
            }
        }
        return device
    }

    private func getTimeout(_ call: CAPPluginCall, defaultTimeout: Double = DEFAULT_TIMEOUT) -> Double {
        guard let timeout = call.getDouble("timeout") else {
            return defaultTimeout
        }
        return timeout / 1000
    }

    private func getCharacteristic(_ call: CAPPluginCall) throws -> (CBUUID, CBUUID) {
        guard let service = call.getString("service") else {
            throw CAPPluginError("Service UUID required.")
        }
        let serviceUUID = CBUUID(string: service)

        guard let characteristic = call.getString("characteristic") else {
            throw CAPPluginError("Characteristic UUID required.")
        }
        let characteristicUUID = CBUUID(string: characteristic)
        return (serviceUUID, characteristicUUID)
    }

    private func getDescriptor(_ call: CAPPluginCall) throws -> (CBUUID, CBUUID, CBUUID) {
        let characteristic = try getCharacteristic(call)
        guard let descriptor = call.getString("descriptor") else {
            throw CAPPluginError("Descriptor UUID required.")
        }
        let descriptorUUID = CBUUID(string: descriptor)

        return (characteristic.0, characteristic.1, descriptorUUID)
    }

    private func getOrCreateDevice(_ peripheral: CBPeripheral) -> Device {
        let key = peripheral.identifier.uuidString
        if let existing = self.deviceMap[key] {
            existing.updatePeripheral(peripheral)
            return existing
        }
        let device = Device(peripheral)
        self.deviceMap[key] = device
        return device
    }

    private func getBleDevice(_ device: Device) -> BleDevice {
        var bleDevice = [
            "deviceId": device.getId()
        ]
        if let name = device.getName() {
            bleDevice["name"] = name
        }
        return bleDevice
    }

    private func getScanResult(_ device: Device, _ advertisementData: [String: Any], _ rssi: NSNumber) -> [String: Any] {
        var data = [
            "device": self.getBleDevice(device),
            "rssi": rssi,
            "txPower": advertisementData[CBAdvertisementDataTxPowerLevelKey] ?? 127,
            "uuids": (advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] ?? []).map({(uuid) -> String in
                return cbuuidToString(uuid)
            })
        ]

        if let localName = advertisementData[CBAdvertisementDataLocalNameKey] as? String {
            data["localName"] = localName
        }

        if let manufacturerData = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data {
            data["manufacturerData"] = self.getManufacturerData(data: manufacturerData)
        }

        if let serviceData = advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data] {
            data["serviceData"] = self.getServiceData(data: serviceData)
        }
        return data
    }

    private func getManufacturerData(data: Data) -> [String: String] {
        var company = 0
        var rest = ""
        for (index, byte) in data.enumerated() {
            if index == 0 {
                company += Int(byte)
            } else if index == 1 {
                company += Int(byte) * 256
            } else {
                rest += String(format: "%02hhx ", byte)
            }
        }
        return [String(company): rest]
    }

    private func getServiceData(data: [CBUUID: Data]) -> [String: String] {
        var result: [String: String] = [:]
        for (key, value) in data {
            result[cbuuidToString(key)] = dataToString(value)
        }
        return result
    }
}
