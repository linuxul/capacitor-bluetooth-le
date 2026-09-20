package com.capacitorjs.community.plugins.bluetoothle

import android.bluetooth.BluetoothDevice

public class DeviceList {
    private val devices: ArrayList<BluetoothDevice> = ArrayList()

    public fun addDevice(device: BluetoothDevice): Boolean {
        // contains compares devices by their address
        if (!devices.contains(device)) {
            devices.add(device)
            return true
        }
        return false
    }

    public fun getDevice(index: Int): BluetoothDevice = devices[index]

    public fun getCount(): Int = devices.size

    public fun clear() {
        devices.clear()
    }
}
