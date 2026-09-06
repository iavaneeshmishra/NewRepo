package app.ripple.mesh.ble

import android.annotation.SuppressLint
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
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import app.ripple.mesh.core.Protocol
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Peripheral role: advertises the mesh service and hosts the GATT server. Each
 * central that subscribes to TX becomes a [PeripheralLink].
 */
@SuppressLint("MissingPermission")
class BlePeripheral(
    context: Context,
    private val adapter: BluetoothAdapter,
    private val onPacket: (BleLink, ByteArray) -> Unit,
    private val onLinkReady: (BleLink) -> Unit,
    private val onLinkClosed: (BleLink) -> Unit,
) {
    companion object { private const val TAG = "BlePeripheral" }

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val appContext = context.applicationContext
    private var server: BluetoothGattServer? = null
    private var tx: BluetoothGattCharacteristic? = null
    private val links = ConcurrentHashMap<String, PeripheralLink>()   // address -> link
    private val mtus = ConcurrentHashMap<String, Int>()
    @Volatile private var advertising = false

    fun connectedAddresses(): Set<String> = links.keys

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { advertising = true; Log.i(TAG, "advertising") }
        override fun onStartFailure(errorCode: Int) { advertising = false; Log.w(TAG, "advertise failed: $errorCode") }
    }

    fun start() {
        if (server != null) return
        val srv = manager.openGattServer(appContext, serverCallback) ?: run { Log.w(TAG, "openGattServer failed"); return }
        server = srv
        val service = BluetoothGattService(UUID.fromString(Protocol.SERVICE_UUID), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val rx = BluetoothGattCharacteristic(
            UUID.fromString(Protocol.RX_UUID),
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val txChar = BluetoothGattCharacteristic(
            UUID.fromString(Protocol.TX_UUID), BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ,
        ).apply {
            addDescriptor(BluetoothGattDescriptor(UUID.fromString(Protocol.CCCD_UUID), BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        }
        service.addCharacteristic(rx); service.addCharacteristic(txChar)
        tx = txChar
        srv.addService(service)
        startAdvertising()
    }

    private fun startAdvertising() {
        val advertiser = adapter.bluetoothLeAdvertiser ?: run { Log.w(TAG, "no advertiser (peripheral mode unsupported)"); return }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true).setTimeout(0).build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(ParcelUuid.fromString(Protocol.SERVICE_UUID)).build()
        try { advertiser.startAdvertising(settings, data, advertiseCallback) } catch (e: Exception) { Log.w(TAG, "startAdvertising: $e") }
    }

    fun stop() {
        try { adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
        advertising = false
        links.values.toList().forEach { it.close() }
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                links.remove(device.address)?.close(); mtus.remove(device.address)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) { mtus[device.address] = mtu }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            val enable = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (enable && descriptor.characteristic.uuid == UUID.fromString(Protocol.TX_UUID)) {
                val link = PeripheralLink(device)
                link.frameSize = minOf((mtus[device.address] ?: 23) - 3, 512)
                if (links.putIfAbsent(device.address, link) == null) {
                    link.start(); Log.i(TAG, "${link.id} ready, frame=${link.frameSize}"); onLinkReady(link)
                }
            } else if (!enable) {
                links.remove(device.address)?.close()
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            val v = if (links.containsKey(device.address)) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, v)
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, c: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (c.uuid == UUID.fromString(Protocol.RX_UUID)) links[device.address]?.receive(value)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            links[device.address]?.notifyDone(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    inner class PeripheralLink(val device: BluetoothDevice) : BleLink("in:${device.address}", onPacket, { onLinkClosedInternal(it as PeripheralLink) }) {
        @Volatile private var latch: CountDownLatch? = null
        @Volatile private var ok = false

        fun receive(frame: ByteArray) = onFrame(frame)
        fun notifyDone(success: Boolean) { ok = success; latch?.countDown() }

        override fun writeFrame(frame: ByteArray): Boolean {
            val srv = server ?: return false
            val c = tx ?: return false
            val l = CountDownLatch(1); latch = l; ok = false
            val started = if (Build.VERSION.SDK_INT >= 33) {
                srv.notifyCharacteristicChanged(device, c, false, frame) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION") run { c.value = frame; srv.notifyCharacteristicChanged(device, c, false) }
            }
            if (!started) return false
            return l.await(5, TimeUnit.SECONDS) && ok
        }

        override fun closeTransport() { try { server?.cancelConnection(device) } catch (_: Exception) {} }
    }

    private fun onLinkClosedInternal(link: PeripheralLink) {
        links.remove(link.device.address, link)
        onLinkClosed(link)
    }
}
