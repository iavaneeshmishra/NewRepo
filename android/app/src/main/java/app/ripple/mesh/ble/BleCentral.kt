package app.ripple.mesh.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import app.ripple.mesh.core.EventLog
import app.ripple.mesh.core.Protocol
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Central role: scans for the mesh service and opens an outgoing GATT link to each
 * device found. One [CentralLink] per remote device.
 */
@SuppressLint("MissingPermission")
class BleCentral(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val onPacket: (BleLink, ByteArray) -> Unit,
    private val onLinkReady: (BleLink) -> Unit,
    private val onLinkClosed: (BleLink) -> Unit,
) {
    companion object {
        private const val TAG = "BleCentral"
        private const val MAX_OUTGOING = 6
        private const val RECONNECT_BACKOFF_MS = 15_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private val log = EventLog.global
    private val lastRssi = ConcurrentHashMap<String, Int>()
    private val serviceUuid = ParcelUuid.fromString(Protocol.SERVICE_UUID)
    private val links = ConcurrentHashMap<String, CentralLink>()      // address -> link
    private val recentlyFailed = ConcurrentHashMap<String, Long>()     // address -> time
    @Volatile private var scanning = false

    /** Addresses of devices that already hold an *incoming* link to us; we skip connecting out to them. */
    @Volatile var shouldSkip: (BluetoothDevice) -> Boolean = { false }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) { noteRssi(result); consider(result.device) }
        override fun onBatchScanResults(results: List<ScanResult>) = results.forEach { noteRssi(it); consider(it.device) }
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode"); log.e(TAG, "scan failed: code $errorCode"); scanning = false
            main.postDelayed({ startScanning() }, 5_000)
        }
    }

    private fun noteRssi(r: ScanResult) {
        lastRssi[r.device.address] = r.rssi
        links[r.device.address]?.rssi = r.rssi
    }

    fun links(): List<BleLink> = links.values.toList()

    fun startScanning() {
        if (scanning) return
        val scanner = adapter.bluetoothLeScanner ?: return
        val filters = listOf(ScanFilter.Builder().setServiceUuid(serviceUuid).build())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()
        try { scanner.startScan(filters, settings, scanCallback); scanning = true; Log.i(TAG, "scanning"); log.i(TAG, "scanning for mesh service") }
        catch (e: Exception) { Log.w(TAG, "startScan: $e"); log.e(TAG, "startScan: $e") }
    }

    fun stopScanning() {
        if (!scanning) return
        try { adapter.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        scanning = false
    }

    fun stop() {
        stopScanning()
        links.values.toList().forEach { it.close() }
    }

    fun linkCount() = links.size

    private fun consider(device: BluetoothDevice) {
        val addr = device.address
        if (links.containsKey(addr)) return
        if (links.size >= MAX_OUTGOING) return
        if (shouldSkip(device)) return
        val failedAt = recentlyFailed[addr]
        if (failedAt != null && System.currentTimeMillis() - failedAt < RECONNECT_BACKOFF_MS) return

        val link = CentralLink(device)
        link.rssi = lastRssi[addr]
        if (links.putIfAbsent(addr, link) == null) {
            Log.i(TAG, "connecting to $addr"); log.i(TAG, "connecting → $addr (rssi ${lastRssi[addr] ?: "?"})")
            main.post { link.connect() }
        }
    }

    inner class CentralLink(val device: BluetoothDevice) : BleLink("out:${device.address}", onPacket, { onLinkClosedInternal(it as CentralLink) }) {
        private var gatt: BluetoothGatt? = null
        private var rx: BluetoothGattCharacteristic? = null
        @Volatile private var ready = false
        @Volatile private var writeLatch: CountDownLatch? = null
        @Volatile private var writeOk = false

        fun connect() {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            main.postDelayed({ if (!ready && !isClosed) { Log.w(TAG, "$id setup timeout"); log.w(TAG, "$id setup timeout"); fail() } }, 20_000)
        }

        private fun fail() { recentlyFailed[device.address] = System.currentTimeMillis(); close() }

        private val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    g.requestMtu(517)
                } else {
                    Log.i(TAG, "$id disconnected status=$status"); log.i(TAG, "$id disconnected (status $status)")
                    if (!ready) recentlyFailed[device.address] = System.currentTimeMillis()
                    close()
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                frameSize = if (status == BluetoothGatt.GATT_SUCCESS) minOf(mtu - 3, 512) else 20
                g.discoverServices()
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val service = g.getService(UUID.fromString(Protocol.SERVICE_UUID))
                val rxChar = service?.getCharacteristic(UUID.fromString(Protocol.RX_UUID))
                val txChar = service?.getCharacteristic(UUID.fromString(Protocol.TX_UUID))
                if (status != BluetoothGatt.GATT_SUCCESS || rxChar == null || txChar == null) { log.w(TAG, "$id service discovery failed (status $status)"); fail(); return }
                rx = rxChar
                g.setCharacteristicNotification(txChar, true)
                val cccd = txChar.getDescriptor(UUID.fromString(Protocol.CCCD_UUID)) ?: run { fail(); return }
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION") g.writeDescriptor(cccd)
                }
            }

            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) { fail(); return }
                ready = true
                start()
                Log.i(TAG, "$id ready, frame=$frameSize"); log.i(TAG, "$id ready, frame $frameSize B")
                g.readRemoteRssi()
                onLinkReady(this@CentralLink)
            }

            override fun onReadRemoteRssi(g: BluetoothGatt, r: Int, status: Int) { if (status == BluetoothGatt.GATT_SUCCESS) rssi = r }

            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                writeOk = status == BluetoothGatt.GATT_SUCCESS
                writeLatch?.countDown()
            }

            @Deprecated("pre-33 callback")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT < 33) @Suppress("DEPRECATION") c.value?.let { onFrame(it) }
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                onFrame(value)
            }
        }

        override fun writeFrame(frame: ByteArray): Boolean {
            val g = gatt ?: return false
            val c = rx ?: return false
            val latch = CountDownLatch(1)
            writeLatch = latch
            writeOk = false
            val started = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION") run { c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; c.value = frame; g.writeCharacteristic(c) }
            }
            if (!started) return false
            return latch.await(5, TimeUnit.SECONDS) && writeOk
        }

        override fun closeTransport() {
            try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
            gatt = null
        }

        fun refreshRssi() { try { gatt?.readRemoteRssi() } catch (_: Exception) {} }
    }

    fun refreshRssi() = links.values.forEach { it.refreshRssi() }

    private fun onLinkClosedInternal(link: CentralLink) {
        links.remove(link.device.address, link)
        onLinkClosed(link)
    }
}
