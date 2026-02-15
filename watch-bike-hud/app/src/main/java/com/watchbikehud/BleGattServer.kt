package com.watchbikehud

import android.bluetooth.BluetoothAdapter
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * BLE GATT Server that advertises a custom bike HUD service
 * and notifies connected clients (Glass) of sensor data.
 */
class BleGattServer(private val context: Context) {

    companion object {
        private const val TAG = "BleGattServer"

        val BIKE_SERVICE  = UUID.fromString("0000ff10-0000-1000-8000-00805f9b34fb")!!
        val CHAR_HR       = UUID.fromString("0000ff11-0000-1000-8000-00805f9b34fb")!!
        val CHAR_LOCATION = UUID.fromString("0000ff12-0000-1000-8000-00805f9b34fb")!!
        val CHAR_TRIP     = UUID.fromString("0000ff13-0000-1000-8000-00805f9b34fb")!!
        val CCCD          = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")!!
    }

    private var bluetoothManager: BluetoothManager? = null
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private val connectedDevices = mutableSetOf<BluetoothDevice>()

    private var hrChar: BluetoothGattCharacteristic? = null
    private var locationChar: BluetoothGattCharacteristic? = null
    private var tripChar: BluetoothGattCharacteristic? = null

    var onConnectionCountChanged: ((Int) -> Unit)? = null

    val connectionCount: Int get() = connectedDevices.size

    fun start(): Boolean {
        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val btManager = bluetoothManager ?: return false
        val adapter = btManager.adapter ?: return false

        if (!adapter.isEnabled) {
            Log.e(TAG, "Bluetooth not enabled")
            return false
        }

        // Open GATT server
        gattServer = btManager.openGattServer(context, gattCallback)
        if (gattServer == null) {
            Log.e(TAG, "Failed to open GATT server")
            return false
        }

        // Create service with 3 notify characteristics
        val service = BluetoothGattService(BIKE_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        hrChar = createNotifyCharacteristic(CHAR_HR)
        locationChar = createNotifyCharacteristic(CHAR_LOCATION)
        tripChar = createNotifyCharacteristic(CHAR_TRIP)

        service.addCharacteristic(hrChar)
        service.addCharacteristic(locationChar)
        service.addCharacteristic(tripChar)

        gattServer?.addService(service)

        // Start advertising
        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "BLE advertising not supported")
            return false
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)  // advertise indefinitely
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(BIKE_SERVICE))
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

    fun notifyHeartRate(bpm: Int) {
        val char = hrChar ?: return
        char.value = byteArrayOf((bpm and 0xFF).toByte())
        notifyAll(char)
    }

    fun notifyLocation(lat: Double, lon: Double, speedMps: Float, bearing: Float) {
        val char = locationChar ?: return
        val bb = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        bb.putDouble(lat)
        bb.putDouble(lon)
        bb.putFloat(speedMps)
        bb.putFloat(bearing)
        char.value = bb.array()
        notifyAll(char)
    }

    fun notifyTrip(distanceM: Float, elapsedSec: Int) {
        val char = tripChar ?: return
        val bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        bb.putFloat(distanceM)
        bb.putInt(elapsedSec)
        char.value = bb.array()
        notifyAll(char)
    }

    private fun notifyAll(characteristic: BluetoothGattCharacteristic) {
        val server = gattServer ?: return
        for (device in connectedDevices.toList()) {
            try {
                server.notifyCharacteristicChanged(device, characteristic, false)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to notify ${device.address}", e)
            }
        }
    }

    private fun createNotifyCharacteristic(uuid: UUID): BluetoothGattCharacteristic {
        val char = BluetoothGattCharacteristic(
            uuid,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            0  // no permissions needed for notify-only
        )
        val cccd = BluetoothGattDescriptor(
            CCCD,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        char.addDescriptor(cccd)
        return char
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
