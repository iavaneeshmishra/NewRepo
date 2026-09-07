package app.ripple.mesh.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.ripple.mesh.R
import app.ripple.mesh.ble.BleCentral
import app.ripple.mesh.ble.BleLink
import app.ripple.mesh.ble.BlePeripheral
import app.ripple.mesh.core.BatteryProfile
import app.ripple.mesh.core.Crypto
import app.ripple.mesh.core.InboundMessage
import app.ripple.mesh.core.Link
import app.ripple.mesh.core.MeshRouter
import app.ripple.mesh.core.NodeId
import app.ripple.mesh.core.Packet
import app.ripple.mesh.core.Peer
import app.ripple.mesh.core.RouterListener
import app.ripple.mesh.core.SosBeacon
import app.ripple.mesh.core.SosLocation
import app.ripple.mesh.core.toHex
import app.ripple.mesh.data.IdentityStore
import app.ripple.mesh.data.MessageEntity
import app.ripple.mesh.data.MessageStatus
import app.ripple.mesh.data.PeerEntity
import app.ripple.mesh.data.RelayPacketEntity
import app.ripple.mesh.data.RippleDatabase
import app.ripple.mesh.data.SosBeaconEntity
import app.ripple.mesh.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MeshStatus(
    val running: Boolean = false,
    val bluetoothOn: Boolean = false,
    val advertising: Boolean = false,
    val directLinks: Int = 0,
    val knownPeers: Int = 0,
)

/**
 * Foreground service that owns the [MeshRouter] and both BLE roles for the
 * lifetime of the app. The UI binds to it to send messages; all inbound traffic
 * is persisted to Room and surfaced via Flows.
 */
class MeshService : LifecycleService(), RouterListener {
    companion object {
        private const val TAG = "MeshService"
        const val CHANNEL_STATUS = "mesh_status"
        const val CHANNEL_MESSAGES = "mesh_messages"
        const val BROADCAST_CONVERSATION = "broadcast"
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            val i = Intent(context, MeshService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
    }

    inner class LocalBinder : Binder() { val service get() = this@MeshService }
    private val binder = LocalBinder()

    lateinit var router: MeshRouter; private set
    private lateinit var db: RippleDatabase
    private var central: BleCentral? = null
    private var peripheral: BlePeripheral? = null
    private val adapter: BluetoothAdapter? by lazy { (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter }

    private val _status = MutableStateFlow(MeshStatus())
    val status: StateFlow<MeshStatus> = _status

    private val _powerProfile = MutableStateFlow(BatteryProfile.BALANCED)
    val powerProfile: StateFlow<Int> = _powerProfile

    /** Most recently received SOS beacon (drives the SOS screen + notification). */
    @Volatile var recentSos: SosBeacon? = null

    /** Conversation currently on screen; used to suppress its notifications. */
    @Volatile var visibleConversation: String? = null

    override fun onBind(intent: Intent): IBinder { super.onBind(intent); return binder }

    override fun onCreate() {
        super.onCreate()
        db = RippleDatabase.get(this)
        createChannels()
        startForegroundCompat()

        val identity = IdentityStore.load(this)
        router = MeshRouter(identity, "Ripple ${identity.nodeId.short}", this)
        Log.i(TAG, "identity ${identity.nodeId.display}")

        lifecycleScope.launch {
            IdentityStore.displayName(this@MeshService).first()?.let { router.setDisplayName(it) }
            // Restore the persisted power profile, then rebuild in-memory state.
            IdentityStore.powerProfile(this@MeshService).first()?.let { code ->
                if (BatteryProfile.isValid(code)) {
                    runCatching { router.setBatteryProfile(code) }
                    _powerProfile.value = code
                }
            }
            val savedPeers = withContext(Dispatchers.IO) { db.peers().all() }
            router.importPeers(savedPeers.mapNotNull { e ->
                runCatching { Peer(NodeId.fromHex(e.nodeId), Crypto.publicKeyFromWire(e.publicKeyWire), e.publicKeyWire, e.name, e.lastSeen, e.hops) }.getOrNull()
            })
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) { db.relay().purge(now) }
            val packets = withContext(Dispatchers.IO) { db.relay().live(now) }.mapNotNull { runCatching { Packet.decode(it.bytes) }.getOrNull() }
            router.importRelayStore(packets)
            _status.update { it.copy(running = true, knownPeers = router.peers().size) }
            startBle()
        }

        registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))

        // Periodic housekeeping: persist relay store, prune old SOS history, refresh notification, poke scanner.
        lifecycleScope.launch {
            while (true) {
                delay(30_000)
                persistRelayStore()
                val cutoff = System.currentTimeMillis() - RippleDatabase.SOS_RETENTION_MS
                withContext(Dispatchers.IO) { db.sos().prune(cutoff) }
                central?.startScanning()
                updateNotification()
            }
        }
    }

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> startBle()
                BluetoothAdapter.STATE_OFF -> stopBle()
            }
        }
    }

    private fun startBle() {
        val ad = adapter ?: return
        if (!ad.isEnabled) { _status.update { it.copy(bluetoothOn = false) }; return }
        if (central != null) return
        val p = BlePeripheral(this, ad, ::onPacket, ::onLinkReady, ::onLinkClosed)
        val c = BleCentral(this, ad, ::onPacket, ::onLinkReady, ::onLinkClosed)
        c.shouldSkip = { device -> p.connectedAddresses().contains(device.address) }
        peripheral = p; central = c
        p.start(); c.startScanning()
        _status.update { it.copy(bluetoothOn = true, advertising = true) }
        updateNotification()
    }

    private fun stopBle() {
        central?.stop(); peripheral?.stop()
        central = null; peripheral = null
        _status.update { it.copy(bluetoothOn = false, advertising = false, directLinks = 0) }
        updateNotification()
    }

    // ---- BLE → router glue ----------------------------------------------------------

    private fun onPacket(link: BleLink, bytes: ByteArray) = router.onReceive(link, bytes)
    private fun onLinkReady(link: BleLink) { router.onLinkReady(link); refreshLinkStatus() }
    private fun onLinkClosed(link: BleLink) { router.onLinkClosed(link); refreshLinkStatus() }
    private fun refreshLinkStatus() { _status.update { it.copy(directLinks = router.directNeighbourCount()) }; updateNotification() }

    // ---- RouterListener -------------------------------------------------------------

    override fun onMessage(message: InboundMessage) {
        val conversation = if (message.isBroadcast) BROADCAST_CONVERSATION else message.from.hex
        val entity = MessageEntity(
            messageId = message.messageId.toHex(), conversation = conversation, fromNodeId = message.from.hex,
            fromName = message.fromName, text = message.text, timestamp = message.timestamp, outgoing = false,
            status = MessageStatus.RECEIVED, verified = message.verified,
        )
        lifecycleScope.launch(Dispatchers.IO) {
            db.messages().upsert(entity)
            if (visibleConversation != conversation) notifyMessage(entity)
        }
    }

    override fun onAck(messageId: ByteArray, from: NodeId) {
        lifecycleScope.launch(Dispatchers.IO) { db.messages().setStatus(messageId.toHex(), MessageStatus.DELIVERED) }
    }

    override fun onPeersChanged(peers: List<Peer>) {
        _status.update { it.copy(knownPeers = peers.size) }
        lifecycleScope.launch(Dispatchers.IO) {
            db.peers().upsertAll(peers.map { PeerEntity(it.nodeId.hex, it.publicKeyWire, it.name, it.lastSeen, it.hops) })
        }
    }

    override fun onLinkIdentified(link: Link, peer: Peer) = refreshLinkStatus()

    override fun onSos(beacon: SosBeacon) {
        recentSos = beacon
        val entity = SosBeaconEntity(
            messageId = beacon.messageId.toHex(),
            fromNodeId = beacon.from.hex,
            fromName = beacon.fromName,
            text = beacon.text,
            latE7 = beacon.location?.latE7,
            lngE7 = beacon.location?.lngE7,
            accuracyMeters = beacon.location?.accuracyMeters,
            verified = beacon.verified,
            timestamp = beacon.timestamp,
        )
        lifecycleScope.launch(Dispatchers.IO) { db.sos().upsert(entity) }
        val title = "SOS — ${beacon.fromName ?: beacon.from.display}"
        val body = if (beacon.text.isEmpty()) "An SOS beacon is active nearby." else beacon.text
        val n = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_ripple).setContentTitle(title).setContentText(body)
            .setAutoCancel(true).setContentIntent(PendingIntent.getActivity(this, "sos".hashCode(), Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        getSystemService(NotificationManager::class.java).notify("sos".hashCode(), n)
    }

    // ---- API for the UI -------------------------------------------------------------

    fun setPowerProfile(code: Int) {
        runCatching { router.setBatteryProfile(code) }
        _powerProfile.value = if (BatteryProfile.isValid(code)) code else BatteryProfile.BALANCED
        lifecycleScope.launch(Dispatchers.IO) { IdentityStore.setPowerProfile(this@MeshService, _powerProfile.value) }
        Log.i(TAG, "power profile -> $code")
    }

    /** Broadcast an SOS beacon. `shareLocation` attaches a coarse fix only if granted. */
    suspend fun sendSos(text: String, shareLocation: Boolean) {
        val location = if (shareLocation) currentLocationOrNull() else null
        router.sendSos(text, location)
    }

    /** Best-effort last-known location; null unless the operator granted location permission. */
    private fun currentLocationOrNull(): SosLocation? {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        val fix = providers.firstNotNullOfOrNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() } ?: return null
        return SosLocation(
            latE7 = (fix.latitude * 1e7).toInt(),
            lngE7 = (fix.longitude * 1e7).toInt(),
            accuracyMeters = (fix.accuracy.toDouble()).toInt().coerceIn(0, 65_000),
        )
    }

    suspend fun sendBroadcast(text: String) {
        val id = router.sendBroadcast(text)
        persistOutgoing(id, BROADCAST_CONVERSATION, text, MessageStatus.SENT)
    }

    suspend fun sendDirect(destination: NodeId, text: String) {
        val id = try { router.sendDirect(destination, text) } catch (e: IllegalStateException) {
            persistOutgoing(Crypto.randomBytes(16), destination.hex, text, MessageStatus.FAILED); return
        }
        persistOutgoing(id, destination.hex, text, if (router.linkCount() > 0) MessageStatus.SENT else MessageStatus.PENDING)
    }

    suspend fun setDisplayName(name: String) {
        IdentityStore.setDisplayName(this, name)
        router.setDisplayName(name)
    }

    private suspend fun persistOutgoing(id: ByteArray, conversation: String, text: String, status: MessageStatus) {
        withContext(Dispatchers.IO) {
            db.messages().upsert(MessageEntity(id.toHex(), conversation, router.selfId.hex, router.displayName, text, System.currentTimeMillis(), true, status, true))
        }
        persistRelayStore()
    }

    private suspend fun persistRelayStore() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        db.relay().upsertAll(router.relayStoreSnapshot().map { RelayPacketEntity(it.messageIdHex, it.encode(), now + app.ripple.mesh.core.Protocol.RELAY_TTL_MS) })
    }

    // ---- notifications --------------------------------------------------------------

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_STATUS, getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_MESSAGES, getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH))
    }

    private fun buildStatusNotification(): Notification {
        val s = _status.value
        val text = when {
            !s.bluetoothOn -> getString(R.string.status_bt_off)
            else -> resources.getQuantityString(R.plurals.status_links, s.directLinks, s.directLinks, s.knownPeers)
        }
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_ripple).setContentTitle(getString(R.string.app_name)).setContentText(text)
            .setOngoing(true).setContentIntent(pi).setSilent(true).build()
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, buildStatusNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTIF_ID, buildStatusNotification())
    }

    private fun updateNotification() { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildStatusNotification()) }

    private fun notifyMessage(m: MessageEntity) {
        val intent = Intent(this, MainActivity::class.java).putExtra("conversation", m.conversation)
        val pi = PendingIntent.getActivity(this, m.conversation.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = m.fromName ?: NodeId.fromHex(m.fromNodeId).display
        val n = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_ripple).setContentTitle(title).setContentText(m.text)
            .setAutoCancel(true).setContentIntent(pi).build()
        getSystemService(NotificationManager::class.java).notify(m.conversation.hashCode(), n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { super.onStartCommand(intent, flags, startId); return START_STICKY }

    override fun onDestroy() {
        unregisterReceiver(btStateReceiver)
        stopBle()
        super.onDestroy()
    }
}
