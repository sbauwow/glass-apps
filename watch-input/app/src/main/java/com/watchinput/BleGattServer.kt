package com.watchinput

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

class BleGattServer(private val context: Context) {

    companion object {
        private const val TAG = "BleGattServer"

        val INPUT_SERVICE = UUID.fromString("0000ff20-0000-1000-8000-00805f9b34fb")!!
        val CHAR_INPUT    = UUID.fromString("0000ff21-0000-1000-8000-00805f9b34fb")!!
        val CCCD          = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")!!

        const val TYPE_KEY     = 0x01.toByte()
        const val TYPE_GESTURE = 0x02.toByte()
        const val TYPE_ROTARY  = 0x03.toByte()

        const val GESTURE_TAP        = 1.toByte()
        const val GESTURE_LONG_PRESS = 2.toByte()
        const val GESTURE_SWIPE_LEFT  = 3.toByte()
        const val GESTURE_SWIPE_RIGHT = 4.toByte()
        const val GESTURE_SWIPE_UP    = 5.toByte()
        const val GESTURE_SWIPE_DOWN  = 6.toByte()

        const val ROTARY_CW  = 1.toByte()
        const val ROTARY_CCW = 2.toByte()

        const val ACTION_DOWN = 0.toByte()
        const val ACTION_UP   = 1.toByte()
    }

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private val connectedDevices = mutableSetOf<BluetoothDevice>()
    private var inputChar: BluetoothGattCharacteristic? = null

    var onConnectionCountChanged: ((Int) -> Unit)? = null
    val connectionCount: Int get() = connectedDevices.size

    fun start(): Boolean {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return false
        val adapter = btManager.adapter ?: return false

        if (!adapter.isEnabled) {
            Log.e(TAG, "Bluetooth not enabled")
            return false
        }

        gattServer = btManager.openGattServer(context, gattCallback)
        if (gattServer == null) {
            Log.e(TAG, "Failed to open GATT server")
            return false
        }

        val service = BluetoothGattService(INPUT_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        inputChar = BluetoothGattCharacteristic(
            CHAR_INPUT,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            0
        )
        val cccd = BluetoothGattDescriptor(
            CCCD,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        inputChar!!.addDescriptor(cccd)
        service.addCharacteristic(inputChar)
        gattServer?.addService(service)

        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "BLE advertising not supported")
            return false
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(INPUT_SERVICE))
            .build()

        advertiser?.startAdvertising(settings, data, advertiseCallback)
        Log.i(TAG, "GATT server started, advertising...")
        return true
    }

    fun stop() {
        advertiser?.stopAdvertising(advertiseCallback)
        connectedDevices.clear()
        gattServer?.close()
        gattServer = null
        advertiser = null
        Log.i(TAG, "GATT server stopped")
    }

    fun notifyInput(type: Byte, value: Byte, action: Byte = 0) {
        val char = inputChar ?: run {
            Log.w(TAG, "notifyInput: inputChar is null")
            return
        }
        char.value = byteArrayOf(type, value, action, 0)
        val server = gattServer ?: run {
            Log.w(TAG, "notifyInput: gattServer is null")
            return
        }
        val devices = connectedDevices.toList()
        Log.d(TAG, "notifyInput type=$type val=$value to ${devices.size} devices")
        for (device in devices) {
            try {
                server.notifyCharacteristicChanged(device, char, false)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to notify ${device.address}", e)
            }
        }
    }

    fun notifyGesture(gesture: Byte) = notifyInput(TYPE_GESTURE, gesture)
    fun notifyRotary(direction: Byte) = notifyInput(TYPE_ROTARY, direction)

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Device connected: ${device.address}")
                connectedDevices.add(device)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Device disconnected: ${device.address}")
                connectedDevices.remove(device)
            }
            onConnectionCountChanged?.invoke(connectedDevices.size)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int,
            descriptor: BluetoothGattDescriptor, preparedWrite: Boolean,
            responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            if (CCCD == descriptor.uuid) {
                descriptor.value = value
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int,
            offset: Int, descriptor: BluetoothGattDescriptor
        ) {
            if (CCCD == descriptor.uuid) {
                gattServer?.sendResponse(
                    device, requestId, BluetoothGatt.GATT_SUCCESS, 0,
                    descriptor.value ?: BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                )
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "BLE advertising started")
        }
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "BLE advertising failed: $errorCode")
        }
    }
}
