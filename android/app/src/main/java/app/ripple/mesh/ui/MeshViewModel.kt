package app.ripple.mesh.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.ripple.mesh.core.BatteryProfile
import app.ripple.mesh.core.NodeId
import app.ripple.mesh.core.toHex
import app.ripple.mesh.data.ConversationSummary
import app.ripple.mesh.data.IdentityStore
import app.ripple.mesh.data.MessageEntity
import app.ripple.mesh.data.PeerEntity
import app.ripple.mesh.data.RippleDatabase
import app.ripple.mesh.data.SosBeaconEntity
import app.ripple.mesh.service.LinkInfo
import app.ripple.mesh.service.MeshService
import app.ripple.mesh.service.MeshStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class MeshViewModel(app: Application) : AndroidViewModel(app) {
    private val db = RippleDatabase.get(app)
    private val service = MutableStateFlow<MeshService?>(null)

    val status: StateFlow<MeshStatus> = service.flatMapLatest { it?.status ?: flowOf(MeshStatus()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MeshStatus())

    val powerProfile: StateFlow<Int> = service.flatMapLatest { it?.powerProfile ?: flowOf(BatteryProfile.BALANCED) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BatteryProfile.BALANCED)

    val selfId: StateFlow<NodeId?> = service.flatMapLatest { flowOf(it?.router?.selfId) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Hex of our own 65-byte P-256 public key wire form, once the service is bound.
     * Input for pairing codes/safety numbers (docs/PAIRING.md) — never log it.
     */
    val selfPublicKeyHex: StateFlow<String?> = service.flatMapLatest { flowOf(it?.router?.identity?.publicKeyWire?.toHex()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val displayName: StateFlow<String?> = IdentityStore.displayName(app)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val conversations: StateFlow<List<ConversationSummary>> = db.messages().observeConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val peers: StateFlow<List<PeerEntity>> = db.peers().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Received SOS beacon history (persisted in Room, pruned to ~90 days). */
    val sosBeacons: StateFlow<List<SosBeaconEntity>> = db.sos().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun messages(conversation: String): Flow<List<MessageEntity>> = db.messages().observeConversation(conversation)
    fun peer(nodeIdHex: String): Flow<PeerEntity?> = db.peers().observe(nodeIdHex)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) { service.value = (binder as MeshService.LocalBinder).service }
        override fun onServiceDisconnected(name: ComponentName) { service.value = null }
    }

    fun bind() {
        val ctx = getApplication<Application>()
        MeshService.start(ctx)
        ctx.bindService(Intent(ctx, MeshService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    fun setVisibleConversation(c: String?) {
        service.value?.visibleConversation = c
        if (c != null) viewModelScope.launch { db.messages().markRead(c) }
    }

    fun send(conversation: String, text: String) = viewModelScope.launch {
        val s = service.value ?: return@launch
        if (conversation == MeshService.BROADCAST_CONVERSATION) s.sendBroadcast(text) else s.sendDirect(NodeId.fromHex(conversation), text)
    }

    fun setDisplayName(name: String) = viewModelScope.launch { service.value?.setDisplayName(name) ?: IdentityStore.setDisplayName(getApplication(), name) }

    fun setPowerProfile(code: Int) = viewModelScope.launch { service.value?.setPowerProfile(code) }

    fun sendSos(text: String, shareLocation: Boolean) = viewModelScope.launch { service.value?.sendSos(text, shareLocation) }

    // Diagnostics
    fun linkInfos(): List<LinkInfo> = service.value?.linkInfos() ?: emptyList()
    fun diagnosticsHeader(): String = service.value?.diagnosticsHeader() ?: "Ripple diagnostics (service not bound)"
    fun setLoopback(enabled: Boolean) { service.value?.setLoopback(enabled) }

    override fun onCleared() {
        runCatching { getApplication<Application>().unbindService(connection) }
    }
}
