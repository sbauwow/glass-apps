package com.phoneinput

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

        const val TYPE_KEY: Byte        = 0x01
        const val TYPE_GESTURE: Byte    = 0x02
        const val TYPE_ROTARY: Byte     = 0x03
        const val TYPE_TOUCH_MOVE: Byte = 0x04
        const val TYPE_TOUCH_TAP: Byte  = 0x05
        const val TYPE_TOUCH_DOWN: Byte = 0x06
        const val TYPE_TOUCH_UP: Byte   = 0x07

        const val GESTURE_TAP: Byte         = 1
        const val GESTURE_LONG_PRESS: Byte   = 2
        const val GESTURE_SWIPE_LEFT: Byte   = 3
        const val GESTURE_SWIPE_RIGHT: Byte  = 4
        const val GESTURE_SWIPE_UP: Byte     = 5
        const val GESTURE_SWIPE_DOWN: Byte   = 6

        const val ACTION_DOWN: Byte = 0
        const val ACTION_UP: Byte   = 1
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
        val char = inputChar ?: return
        char.value = byteArrayOf(type, value, action, 0)
        val server = gattServer ?: return
        for (device in connectedDevices.toList()) {
            try {
                server.notifyCharacteristicChanged(device, char, false)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to notify ${device.address}", e)
            }
        }
    }

    fun sendKey(keyCode: Int) {
        notifyInput(TYPE_KEY, keyCode.toByte(), ACTION_DOWN)
        notifyInput(TYPE_KEY, keyCode.toByte(), ACTION_UP)
    }

    fun sendKeyDown(keyCode: Int) {
        notifyInput(TYPE_KEY, keyCode.toByte(), ACTION_DOWN)
    }

    fun sendKeyUp(keyCode: Int) {
        notifyInput(TYPE_KEY, keyCode.toByte(), ACTION_UP)
    }

    fun sendGesture(gesture: Byte) = notifyInput(TYPE_GESTURE, gesture)

    fun sendTouchMove(dx: Int, dy: Int) {
        notifyInput(TYPE_TOUCH_MOVE, dx.coerceIn(-128, 127).toByte(), dy.coerceIn(-128, 127).toByte())
    }

    fun sendTouchTap() {
        notifyInput(TYPE_TOUCH_TAP, 0, 0)
    }

    fun sendTouchDown() {
        notifyInput(TYPE_TOUCH_DOWN, 0, 0)
    }

    fun sendTouchUp() {
        notifyInput(TYPE_TOUCH_UP, 0, 0)
    }

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
