package com.capacitorjs.community.plugins.bluetoothle

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.ParcelUuid
import android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
import android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
import android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS
import androidx.activity.result.ActivityResult
import androidx.core.location.LocationManagerCompat
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Logger
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginException
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import java.util.UUID

@SuppressLint("MissingPermission")
@CapacitorPlugin(
    name = "BluetoothLe",
    permissions = [
        Permission(
            strings = [
                Manifest.permission.ACCESS_COARSE_LOCATION
            ],
            alias = "ACCESS_COARSE_LOCATION"
        ),
        Permission(
            strings = [
                Manifest.permission.ACCESS_FINE_LOCATION
            ],
            alias = "ACCESS_FINE_LOCATION"
        ),
        Permission(
            strings = [
                Manifest.permission.BLUETOOTH
            ],
            alias = "BLUETOOTH"
        ),
        Permission(
            strings = [
                Manifest.permission.BLUETOOTH_ADMIN
            ],
            alias = "BLUETOOTH_ADMIN"
        ),
        Permission(
            strings = [
                // Manifest.permission.BLUETOOTH_SCAN
                "android.permission.BLUETOOTH_SCAN"
            ],
            alias = "BLUETOOTH_SCAN"
        ),
        Permission(
            strings = [
                // Manifest.permission.BLUETOOTH_ADMIN
                "android.permission.BLUETOOTH_CONNECT"
            ],
            alias = "BLUETOOTH_CONNECT"
        )
    ]
)
public class BluetoothLe : Plugin() {
    public companion object {
        private val TAG = BluetoothLe::class.java.simpleName

        // maximal scan duration for requestDevice
        private const val MAX_SCAN_DURATION: Long = 30000
        private const val CONNECTION_TIMEOUT: Float = 10000.0F
        private const val DEFAULT_TIMEOUT: Float = 5000.0F
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var stateReceiver: BroadcastReceiver? = null
    private var deviceMap = HashMap<String, Device>()
    private var deviceScanner: DeviceScanner? = null
    private var displayStrings: DisplayStrings? = null
    private var aliases: Array<String> = arrayOf()

    override fun load() {
        displayStrings = getDisplayStrings()
    }

    @PluginMethod
    public fun initialize(call: PluginCall) {
        val neverForLocation = call.getBoolean("androidNeverForLocation", false) as Boolean
        aliases = if (neverForLocation) {
            arrayOf(
                "BLUETOOTH_SCAN",
                "BLUETOOTH_CONNECT"
            )
        } else {
            arrayOf(
                "BLUETOOTH_SCAN",
                "BLUETOOTH_CONNECT",
                "ACCESS_FINE_LOCATION"
            )
        }
        requestPermissionForAliases(aliases, call, "checkPermission")
    }

    @PermissionCallback
    private fun checkPermission(call: PluginCall) {
        val granted: List<Boolean> = aliases.map { alias ->
            getPermissionState(alias) == PermissionState.GRANTED
        }
        // all have to be true
        if (granted.all { it }) {
            runInitialization(call)
        } else {
            call.reject("Permission denied.")
        }
    }

    private fun runInitialization(call: PluginCall) {
        if (!activity.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            throw PluginException("BLE is not supported.")
        }

        bluetoothAdapter =
            (activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

        if (bluetoothAdapter == null) {
            throw PluginException("BLE is not available.")
        }
        call.resolve()
    }

    @PluginMethod
    public fun isEnabled(call: PluginCall) {
        val enabled = requireBluetoothAdapter().isEnabled
        val result = JSObject()
        result.put("value", enabled)
        call.resolve(result)
    }

    @PluginMethod
    public fun requestEnable(call: PluginCall) {
        requireBluetoothAdapter()
        val intent = Intent(ACTION_REQUEST_ENABLE)
        startActivityForResult(call, intent, "handleRequestEnableResult")
    }

    @ActivityCallback
    private fun handleRequestEnableResult(call: PluginCall, result: ActivityResult) {
        if (result.resultCode == Activity.RESULT_OK) {
            call.resolve()
        } else {
            call.reject("requestEnable failed.")
        }
    }

    @PluginMethod
    public fun enable(call: PluginCall) {
        val result = requireBluetoothAdapter().enable()
        if (!result) {
            throw PluginException("Enable failed.")
        }
        call.resolve()
    }

    @PluginMethod
    public fun disable(call: PluginCall) {
        val result = requireBluetoothAdapter().disable()
        if (!result) {
            throw PluginException("Disable failed.")
        }
        call.resolve()
    }

    @PluginMethod
    public fun startEnabledNotifications(call: PluginCall) {
        requireBluetoothAdapter()

        try {
            createStateReceiver()
        } catch (e: Error) {
            Logger.error(
                TAG,
                "Error while registering enabled state receiver: ${e.localizedMessage}",
                e
            )
            throw PluginException("startEnabledNotifications failed.")
        }
        call.resolve()
    }

    private fun createStateReceiver() {
        if (stateReceiver == null) {
            stateReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val action = intent.action
                    if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                        val state = intent.getIntExtra(
                            BluetoothAdapter.EXTRA_STATE,
                            BluetoothAdapter.ERROR
                        )
                        val enabled = state == BluetoothAdapter.STATE_ON
                        val result = JSObject()
                        result.put("value", enabled)
                        try {
                            notifyListeners("onEnabledChanged", result)
                        } catch (e: ConcurrentModificationException) {
                            Logger.error(TAG, "Error in notifyListeners: ${e.localizedMessage}", e)
                        }
                    }
                }
            }
            val intentFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            context.registerReceiver(stateReceiver, intentFilter)
        }
    }

    @PluginMethod
    public fun stopEnabledNotifications(call: PluginCall) {
        if (stateReceiver != null) {
            context.unregisterReceiver(stateReceiver)
        }
        stateReceiver = null
        call.resolve()
    }

    @PluginMethod
    public fun isLocationEnabled(call: PluginCall) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val enabled = LocationManagerCompat.isLocationEnabled(lm)
        Logger.debug(TAG, "location $enabled")
        val result = JSObject()
        result.put("value", enabled)
        call.resolve(result)
    }

    @PluginMethod
    public fun openLocationSettings(call: PluginCall) {
        val intent = Intent(ACTION_LOCATION_SOURCE_SETTINGS)
        activity.startActivity(intent)
        call.resolve()
    }

    @PluginMethod
    public fun openBluetoothSettings(call: PluginCall) {
        val intent = Intent(ACTION_BLUETOOTH_SETTINGS)
        activity.startActivity(intent)
        call.resolve()
    }

    @PluginMethod
    public fun openAppSettings(call: PluginCall) {
        val intent = Intent(ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = Uri.parse("package:" + activity.packageName)
        activity.startActivity(intent)
        call.resolve()
    }

    @PluginMethod
    public fun setDisplayStrings(call: PluginCall) {
        displayStrings = DisplayStrings(
            call.getString(
                "scanning",
                displayStrings!!.scanning
            ) as String,
            call.getString(
                "cancel",
                displayStrings!!.cancel
            ) as String,
            call.getString(
                "availableDevices",
                displayStrings!!.availableDevices
            ) as String,
            call.getString(
                "noDeviceFound",
                displayStrings!!.noDeviceFound
            ) as String
        )
        call.resolve()
    }

    @PluginMethod
    public fun requestDevice(call: PluginCall) {
        val bluetoothAdapter = requireBluetoothAdapter()
        val scanFilters = getScanFilters(call)
        val scanSettings = getScanSettings(call)
        val namePrefix = call.getString("namePrefix", "") as String

        try {
            deviceScanner?.stopScanning()
        } catch (e: IllegalStateException) {
            Logger.error(TAG, "Error in requestDevice: ${e.localizedMessage}", e)
            call.reject(e.localizedMessage)
            return
        }

        deviceScanner = DeviceScanner(
            context,
            bluetoothAdapter,
            scanDuration = MAX_SCAN_DURATION,
            displayStrings = displayStrings!!,
            showDialog = true
        )
        deviceScanner?.startScanning(
            scanFilters,
            scanSettings,
            false,
            namePrefix,
            { scanResponse ->
                run {
                    if (scanResponse.success) {
                        if (scanResponse.device == null) {
                            call.reject("No device found.")
                        } else {
                            val bleDevice = getBleDevice(scanResponse.device)
                            call.resolve(bleDevice)
                        }
                    } else {
                        call.reject(scanResponse.message)
                    }
                }
            },
            null
        )
    }

    @PluginMethod
    public fun requestLEScan(call: PluginCall) {
        val bluetoothAdapter = requireBluetoothAdapter()
        val scanFilters = getScanFilters(call)
        val scanSettings = getScanSettings(call)
        val namePrefix = call.getString("namePrefix", "") as String
        val allowDuplicates = call.getBoolean("allowDuplicates", false) as Boolean

        try {
            deviceScanner?.stopScanning()
        } catch (e: IllegalStateException) {
            Logger.error(TAG, "Error in requestLEScan: ${e.localizedMessage}", e)
            call.reject(e.localizedMessage)
            return
        }

        deviceScanner = DeviceScanner(
            context,
            bluetoothAdapter,
            scanDuration = null,
            displayStrings = displayStrings!!,
            showDialog = false
        )
        deviceScanner?.startScanning(
            scanFilters,
            scanSettings,
            allowDuplicates,
            namePrefix,
            { scanResponse ->
                run {
                    if (scanResponse.success) {
                        call.resolve()
                    } else {
                        call.reject(scanResponse.message)
                    }
                }
            },
            { result ->
                run {
                    val scanResult = getScanResult(result)
                    try {
                        notifyListeners("onScanResult", scanResult)
                    } catch (e: ConcurrentModificationException) {
                        Logger.error(TAG, "Error in notifyListeners: ${e.localizedMessage}", e)
                    }
                }
            }
        )
    }

    @PluginMethod
    public fun stopLEScan(call: PluginCall) {
        requireBluetoothAdapter()
        try {
            deviceScanner?.stopScanning()
        } catch (e: IllegalStateException) {
            Logger.error(TAG, "Error in stopLEScan: ${e.localizedMessage}", e)
        }
        call.resolve()
    }

    @PluginMethod
    public fun getDevices(call: PluginCall) {
        requireBluetoothAdapter()
        val deviceIds = (call.getArray("deviceIds", JSArray()) as JSArray).toList<String>()
        val bleDevices = JSArray()
        deviceIds.forEach { deviceId ->
            val bleDevice = JSObject()
            bleDevice.put("deviceId", deviceId)
            bleDevices.put(bleDevice)
        }
        val result = JSObject()
        result.put("devices", bleDevices)
        call.resolve(result)
    }

    @PluginMethod
    public fun getConnectedDevices(call: PluginCall) {
        requireBluetoothAdapter()
        val bluetoothManager =
            (activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
        val devices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
        val bleDevices = JSArray()
        devices.forEach { device ->
            bleDevices.put(getBleDevice(device))
        }
        val result = JSObject()
        result.put("devices", bleDevices)
        call.resolve(result)
    }

    @PluginMethod
    public fun getBondedDevices(call: PluginCall) {
        requireBluetoothAdapter()

        val bluetoothManager = activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter ?: throw PluginException("Bluetooth is not supported on this device")

        val bondedDevices = bluetoothAdapter.bondedDevices
        val bleDevices = JSArray()

        bondedDevices.forEach { device ->
            bleDevices.put(getBleDevice(device))
        }

        val result = JSObject()
        result.put("devices", bleDevices)
        call.resolve(result)
    }

    @PluginMethod
    public fun connect(call: PluginCall) {
        val device = getOrCreateDevice(call)
        val timeout = call.getFloat("timeout", CONNECTION_TIMEOUT)!!.toLong()
        val skipDescriptorDiscovery = call.getBoolean("skipDescriptorDiscovery", false)!!
        device.connect(timeout, skipDescriptorDiscovery) { response ->
            run {
                if (response.success) {
                    call.resolve()
                } else {
                    call.reject(response.value)
                }
            }
        }
    }

    private fun onDisconnect(deviceId: String) {
        try {
            notifyListeners("disconnected|$deviceId", null)
        } catch (e: ConcurrentModificationException) {
            Logger.error(TAG, "Error in notifyListeners: ${e.localizedMessage}", e)
        }
    }

    @PluginMethod
    public fun createBond(call: PluginCall) {
        val device = getOrCreateDevice(call)
        val timeout = call.getFloat("timeout", DEFAULT_TIMEOUT)!!.toLong()
        device.createBond(timeout) { response ->
            run {
                if (response.success) {
                    call.resolve()
                } else {
                    call.reject(response.value)
                }
            }
        }
    }

    @PluginMethod
    public fun isBonded(call: PluginCall) {
        val device = getOrCreateDevice(call)
        val isBonded = device.isBonded()
        val result = JSObject()
        result.put("value", isBonded)
        call.resolve(result)
    }

    @PluginMethod
    public fun disconnect(call: PluginCall) {
        val device = getOrCreateDevice(call)
        val timeout = call.getFloat("timeout", DEFAULT_TIMEOUT)!!.toLong()
        device.disconnect(timeout) { response ->
            run {
                if (response.success) {
                    device.cleanup()
                    deviceMap.remove(device.getId())
                    call.resolve()
                } else {
                    call.reject(response.value)
                }
            }
        }
    }

    @PluginMethod
    public fun getServices(call: PluginCall) {
        val device = getDevice(call)
        val services = device.getServices()
        val skipDescriptorDiscovery = device.getSkipDescriptorDiscovery()
        val bleServices = JSArray()
        services.forEach { service ->
            val bleCharacteristics = JSArray()
            service.characteristics.forEach { characteristic ->
                val bleCharacteristic = JSObject()
                bleCharacteristic.put("uuid", characteristic.uuid)
                bleCharacteristic.put("properties", getProperties(characteristic))
                val bleDescriptors = JSArray()
                if (!skipDescriptorDiscovery) {
                    characteristic.descriptors.forEach { descriptor ->
                        val bleDescriptor = JSObject()
                        bleDescriptor.put("uuid", descriptor.uuid)
                        bleDescriptors.put(bleDescriptor)
                    }
                }
                bleCharacteristic.put("descriptors", bleDescriptors)
                bleCharacteristics.put(bleCharacteristic)
            }
            val bleService = JSObject()
            bleService.put("uuid", service.uuid)
            bleService.put("characteristics", bleCharacteristics)
            bleServices.put(bleService)
        }
        val ret = JSObject()
        ret.put("services", bleServices)
        call.resolve(ret)
    }

    private fun getProperties(characteristic: BluetoothGattCharacteristic): JSObject {
        val properties = JSObject()
        properties.put(
            "broadcast",
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_BROADCAST > 0
        )
        properties.put(
            "read",
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ > 0
        )
        properties.put(
            "writeWithoutResponse",
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE > 0
        )
        properties.put(
            "write",
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE > 0
        )
        properties.put(
            "notify",
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY > 0
        )
        properties.put(
            "indicate",
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE > 0
        )
        properties.put(
            "authenticatedSignedWrites",
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE > 0
        )
        properties.put(
            "extendedProperties",
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS > 0
        )
        return properties
    }

    @PluginMethod
    public fun discoverServices(call: PluginCall) {
        val device = getDevice(call)
        val timeout = call.getFloat("timeout", DEFAULT_TIMEOUT)!!.toLong()
        device.discoverServices(timeout) { response ->
            run {
                if (response.success) {
                    call.resolve()
                } else {
                    call.reject(response.value)
                }
            }
        }
    }

    @PluginMethod
    public fun getMtu(call: PluginCall) {
        val device = getDevice(call)
        val mtu = device.getMtu()
        val ret = JSObject()
        ret.put("value", mtu)
        call.resolve(ret)
    }

    @PluginMethod
    public fun requestConnectionPriority(call: PluginCall) {
        val device = getDevice(call)
        val connectionPriority = call.getInt("connectionPriority", -1) as Int
        if (connectionPriority < BluetoothGatt.CONNECTION_PRIORITY_BALANCED ||
            connectionPriority > BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
        ) {
            throw PluginException("Invalid connectionPriority.")
        }

        val result = device.requestConnectionPriority(connectionPriority)
        if (!result) {
            throw PluginException("requestConnectionPriority failed.")
        }
        call.resolve()
    }

    @PluginMethod
    public fun readRssi(call: PluginCall) {
        val device = getDevice(call)
        val timeout = call.getFloat("timeout", DEFAULT_TIMEOUT)!!.toLong()
        device.readRssi(timeout) { response ->
            run {
                if (response.success) {
                    val ret = JSObject()
                    ret.put("value", response.value)
                    call.resolve(ret)
                } else {
                    call.reject(response.value)
                }
            }
        }
    }

    @PluginMethod
    public fun read(call: PluginCall) {
        val device = getDevice(call)
        val characteristic = getCharacteristic(call)
        val timeout = call.getFloat("timeout", DEFAULT_TIMEOUT)!!.toLong()
        device.read(characteristic.first, characteristic.second, timeout) { response ->
            run {
                if (response.success) {
                    val ret = JSObject()
                    ret.put("value", response.value)
                    call.resolve(ret)
                } else {
                    call.reject(response.value)
                }
            }
        }
    }

    @PluginMethod
    public fun write(call: PluginCall) {
        val device = getDevice(call)
        val characteristic = getCharacteristic(call)
        val value = call.getString("value", null) ?: throw PluginException("Value required.")
        val writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val timeout = call.getFloat("timeout", DEFAULT_TIMEOUT)!!.toLong()
        device.write(
            characteristic.first,
            characteristic.second,
            value,
            writeType,
            timeout
        ) { response ->
            run {
                if (response.success) {
                    call.resolve()
                } else {
                    call.reject(response.value)
                }
            }
        }
    }

    @PluginMethod
    public fun writeWithoutResponse(call: PluginCall) {
        val device = getDevice(call)
        val characteristic = getCharacteristic(call)
        val value = call.getString("value", null) ?: throw PluginException("Value required.")
        val writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val timeout = call.getFloat("timeout", DEFAULT_TIMEOUT)!!.toLong()
        device.write(
            characteristic.first,
            characteristic.second,
            value,
            writeType,
            timeout
        ) { response ->
            run {
                if (response.success) {
                    call.resolve()
                } else {
                    call.reject(response.value)
                }
            }
        }
    }

    @PluginMethod
    public fun readDescriptor(call: PluginCall) {
        val device = getDevice(call)
        val descriptor = getDescriptor(call)
        val timeout = call.getFloat("timeout", DEFAULT_TIMEOUT)!!.toLong()
        device.readDescriptor(
            descriptor.first,
            descriptor.second,
            descriptor.third,
            timeout
        ) { response ->
            run {
                if (response.success) {
                    val ret = JSObject()
                    ret.put("value", response.value)
                    call.resolve(ret)
                } else {
                    call.reject(response.value)
                }
            }
        }
    }

    @PluginMethod
    public fun writeDescriptor(call: PluginCall) {
        val device = getDevice(call)
        val descriptor = getDescriptor(call)
        val value = call.getString("value", null) ?: throw PluginException("Value required.")
        val timeout = call.getFloat("timeout", DEFAULT_TIMEOUT)!!.toLong()
        device.writeDescriptor(
            descriptor.first,
            descriptor.second,
            descriptor.third,
            value,
            timeout
        ) { response ->
            run {
                if (response.success) {
                    call.resolve()
                } else {
                    call.reject(response.value)
                }
            }
        }
    }

    @PluginMethod
    public fun startNotifications(call: PluginCall) {
        val device = getDevice(call)
        val characteristic = getCharacteristic(call)
        val timeout = call.getFloat("timeout", DEFAULT_TIMEOUT)!!.toLong()
        device.setNotifications(characteristic.first, characteristic.second, true, { response ->
            run {
                val key =
                    "notification|${device.getId()}|${(characteristic.first)}|${(characteristic.second)}"
                val ret = JSObject()
                ret.put("value", response.value)
                try {
                    notifyListeners(key, ret)
                } catch (e: ConcurrentModificationException) {
                    Logger.error(TAG, "Error in notifyListeners: ${e.localizedMessage}", e)
                }
            }
        }, timeout, { response ->
            run {
                if (response.success) {
                    call.resolve()
                } else {
                    call.reject(response.value)
                }
            }
        })
    }

    @PluginMethod
    public fun stopNotifications(call: PluginCall) {
        val device = getDevice(call)
        val characteristic = getCharacteristic(call)
        val timeout = call.getFloat("timeout", DEFAULT_TIMEOUT)!!.toLong()
        device.setNotifications(
            characteristic.first,
            characteristic.second,
            false,
            null,
            timeout
        ) { response ->
            run {
                if (response.success) {
                    call.resolve()
                } else {
                    call.reject(response.value)
                }
            }
        }
    }

    /**
     * The adapter that initialize found; throws when initialize has not succeeded.
     */
    private fun requireBluetoothAdapter(): BluetoothAdapter = bluetoothAdapter ?: throw PluginException("Bluetooth LE not initialized.")

    private fun getScanFilters(call: PluginCall): List<ScanFilter> {
        val filters: ArrayList<ScanFilter> = ArrayList()

        val services = (call.getArray("services", JSArray()) as JSArray).toList<String>()
        val manufacturerDataArray = call.getArray("manufacturerData", JSArray())
        val serviceDataArray = call.getArray("serviceData", JSArray())
        val name = call.getString("name", null)

        try {
            // Create filters based on services
            for (service in services) {
                val filter = ScanFilter.Builder()
                filter.setServiceUuid(ParcelUuid.fromString(service))
                if (name != null) {
                    filter.setDeviceName(name)
                }
                filters.add(filter.build())
            }

            // Service Data Handling (for filtering by service data like OpenDroneID)
            serviceDataArray?.let {
                for (i in 0 until it.length()) {
                    val serviceDataObject = it.getJSONObject(i)

                    val serviceUuid = serviceDataObject.getString("serviceUuid")
                    val servicePuuid = ParcelUuid.fromString(serviceUuid)

                    val dataPrefix = if (serviceDataObject.has("dataPrefix")) {
                        val dataPrefixString = serviceDataObject.getString("dataPrefix")
                        stringToBytes(dataPrefixString)
                    } else {
                        null
                    }

                    val mask = if (serviceDataObject.has("mask")) {
                        val maskString = serviceDataObject.getString("mask")
                        stringToBytes(maskString)
                    } else {
                        null
                    }

                    val filterBuilder = ScanFilter.Builder()

                    if (dataPrefix != null && mask != null) {
                        filterBuilder.setServiceData(servicePuuid, dataPrefix, mask)
                    } else if (dataPrefix != null) {
                        filterBuilder.setServiceData(servicePuuid, dataPrefix)
                    } else {
                        // Set service data filter without data (just match the service UUID)
                        filterBuilder.setServiceData(servicePuuid, byteArrayOf())
                    }

                    if (name != null) {
                        filterBuilder.setDeviceName(name)
                    }

                    filters.add(filterBuilder.build())
                }
            }

            // Manufacturer Data Handling (with optional parameters)
            manufacturerDataArray?.let {
                for (i in 0 until it.length()) {
                    val manufacturerDataObject = it.getJSONObject(i)

                    val companyIdentifier = manufacturerDataObject.getInt("companyIdentifier")

                    val dataPrefix = if (manufacturerDataObject.has("dataPrefix")) {
                        val dataPrefixString = manufacturerDataObject.getString("dataPrefix")
                        stringToBytes(dataPrefixString)
                    } else {
                        null
                    }

                    val mask = if (manufacturerDataObject.has("mask")) {
                        val maskString = manufacturerDataObject.getString("mask")
                        stringToBytes(maskString)
                    } else {
                        null
                    }

                    val filterBuilder = ScanFilter.Builder()

                    if (dataPrefix != null && mask != null) {
                        filterBuilder.setManufacturerData(companyIdentifier, dataPrefix, mask)
                    } else if (dataPrefix != null) {
                        filterBuilder.setManufacturerData(companyIdentifier, dataPrefix)
                    } else {
                        // Android requires at least dataPrefix for manufacturer filters.
                        throw PluginException("dataPrefix is required when specifying manufacturerData.")
                    }

                    if (name != null) {
                        filterBuilder.setDeviceName(name)
                    }

                    filters.add(filterBuilder.build())
                }
            }
            // Create filters when providing only name
            if (name != null && filters.isEmpty()) {
                val filterBuilder = ScanFilter.Builder()
                filterBuilder.setDeviceName(name)
                filters.add(filterBuilder.build())
            }

            return filters
        } catch (e: PluginException) {
            // The missing dataPrefix above, which the generic catch below must not replace
            throw e
        } catch (e: IllegalArgumentException) {
            throw PluginException("Invalid UUID or Manufacturer data provided.")
        } catch (e: Exception) {
            throw PluginException("Invalid or malformed filter data provided.")
        }
    }

    private fun getScanSettings(call: PluginCall): ScanSettings {
        val scanSettings = ScanSettings.Builder()
        val scanMode = call.getInt("scanMode", ScanSettings.SCAN_MODE_BALANCED) as Int
        try {
            scanSettings.setScanMode(scanMode)
        } catch (e: IllegalArgumentException) {
            throw PluginException("Invalid scan mode.")
        }

        // Bluetooth 5 advertising extensions are only reported when legacy mode is switched off.
        // setLegacy(false) reports both legacy and extended advertisements
        val allowExtendedAdvertising = call.getBoolean("allowExtendedAdvertising", false) as Boolean
        if (allowExtendedAdvertising) {
            scanSettings.setLegacy(false)
        }

        return scanSettings.build()
    }

    private fun getBleDevice(device: BluetoothDevice): JSObject {
        val bleDevice = JSObject()
        bleDevice.put("deviceId", device.address)
        if (device.name != null) {
            bleDevice.put("name", device.name)
        }

        val uuids = JSArray()
        device.uuids?.forEach { uuid -> uuids.put(uuid.toString()) }
        if (uuids.length() > 0) {
            bleDevice.put("uuids", uuids)
        }

        return bleDevice
    }

    private fun getScanResult(result: ScanResult): JSObject {
        val scanResult = JSObject()

        val bleDevice = getBleDevice(result.device)
        scanResult.put("device", bleDevice)

        if (result.device.name != null) {
            scanResult.put("localName", result.device.name)
        }

        scanResult.put("rssi", result.rssi)

        scanResult.put("txPower", result.txPower)

        val manufacturerData = JSObject()
        val manufacturerSpecificData = result.scanRecord?.manufacturerSpecificData
        if (manufacturerSpecificData != null) {
            for (i in 0 until manufacturerSpecificData.size()) {
                val key = manufacturerSpecificData.keyAt(i)
                val bytes = manufacturerSpecificData.get(key)
                manufacturerData.put(key.toString(), bytesToString(bytes))
            }
        }
        scanResult.put("manufacturerData", manufacturerData)

        val serviceDataObject = JSObject()
        val serviceData = result.scanRecord?.serviceData
        serviceData?.forEach {
            serviceDataObject.put(it.key.toString(), bytesToString(it.value))
        }
        scanResult.put("serviceData", serviceDataObject)

        val uuids = JSArray()
        result.scanRecord?.serviceUuids?.forEach { uuid -> uuids.put(uuid.toString()) }
        scanResult.put("uuids", uuids)

        scanResult.put("rawAdvertisement", result.scanRecord?.bytes?.let { bytesToString(it) })
        return scanResult
    }

    private fun getDisplayStrings(): DisplayStrings = DisplayStrings(
        config.getString("displayStrings.scanning", "Scanning...") ?: "Scanning...",
        config.getString("displayStrings.cancel", "Cancel") ?: "Cancel",
        config.getString("displayStrings.availableDevices", "Available devices") ?: "Available devices",
        config.getString("displayStrings.noDeviceFound", "No device found") ?: "No device found"
    )

    private fun getDeviceId(call: PluginCall): String = call.getString("deviceId", null) ?: throw PluginException("deviceId required.")

    private fun getOrCreateDevice(call: PluginCall): Device {
        val bluetoothAdapter = requireBluetoothAdapter()
        val deviceId = getDeviceId(call)
        val device = deviceMap[deviceId]
        if (device != null) {
            return device
        }
        return try {
            val newDevice = Device(
                activity.applicationContext,
                bluetoothAdapter,
                deviceId
            ) {
                onDisconnect(deviceId)
            }
            deviceMap[deviceId] = newDevice
            newDevice
        } catch (e: IllegalArgumentException) {
            throw PluginException("Invalid deviceId")
        }
    }

    private fun getDevice(call: PluginCall): Device {
        requireBluetoothAdapter()
        val deviceId = getDeviceId(call)
        val device = deviceMap[deviceId]
        if (device == null || !device.isConnected()) {
            throw PluginException("Not connected to device.")
        }
        return device
    }

    private fun getCharacteristic(call: PluginCall): Pair<UUID, UUID> {
        val serviceString = call.getString("service", null)
        val serviceUUID: UUID?
        try {
            serviceUUID = UUID.fromString(serviceString)
        } catch (e: IllegalArgumentException) {
            throw PluginException("Invalid service UUID.")
        }
        if (serviceUUID == null) {
            throw PluginException("Service UUID required.")
        }
        val characteristicString = call.getString("characteristic", null)
        val characteristicUUID: UUID?
        try {
            characteristicUUID = UUID.fromString(characteristicString)
        } catch (e: IllegalArgumentException) {
            throw PluginException("Invalid characteristic UUID.")
        }
        if (characteristicUUID == null) {
            throw PluginException("Characteristic UUID required.")
        }
        return Pair(serviceUUID, characteristicUUID)
    }

    private fun getDescriptor(call: PluginCall): Triple<UUID, UUID, UUID> {
        val characteristic = getCharacteristic(call)
        val descriptorString = call.getString("descriptor", null)
        val descriptorUUID: UUID?
        try {
            descriptorUUID = UUID.fromString(descriptorString)
        } catch (e: IllegalAccessException) {
            throw PluginException("Invalid descriptor UUID.")
        }
        if (descriptorUUID == null) {
            throw PluginException("Descriptor UUID required.")
        }
        return Triple(characteristic.first, characteristic.second, descriptorUUID)
    }
}
