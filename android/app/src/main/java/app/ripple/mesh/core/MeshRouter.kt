package app.ripple.mesh.core

import java.security.PublicKey
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A bidirectional byte pipe to one neighbour. Implemented by the BLE layer. */
interface Link {
    val id: String
    /** Hex NodeId of the peer, once learned from its ANNOUNCE. */
    var peerHex: String?
    fun send(packetBytes: ByteArray)
    fun close()
}

data class Peer(
    val nodeId: NodeId,
    val publicKey: PublicKey,
    val publicKeyWire: ByteArray,
    val name: String,
    val lastSeen: Long,
    /** Best known hop distance (1 = direct neighbour). */
    val hops: Int,
)

data class InboundMessage(
    val messageId: ByteArray,
    val from: NodeId,
    val fromName: String?,
    val text: String,
    val isBroadcast: Boolean,
    val verified: Boolean,
    val timestamp: Long,
)

/** Delivery report for an SOS beacon. `location` is null unless the sender opted in to GPS. */
data class SosBeacon(
    val messageId: ByteArray,
    val from: NodeId,
    val fromName: String?,
    val text: String,
    val location: SosLocation?,
    val verified: Boolean,
    val timestamp: Long,
)

interface RouterListener {
    fun onMessage(message: InboundMessage)
    fun onAck(messageId: ByteArray, from: NodeId)
    fun onPeersChanged(peers: List<Peer>)
    fun onLinkIdentified(link: Link, peer: Peer) {}
    fun onSos(beacon: SosBeacon) {}
}

/**
 * Transport-agnostic implementation of PROTOCOL.md §4: flooding with a seen-cache,
 * TTL, store-and-forward, duplicate-link suppression, signature verification and
 * end-to-end decryption. Identical in behaviour to tools/protocol/mesh-sim.js.
 *
 * Thread-safe: every public entry point takes the router lock. Outbound sends
 * happen while holding the lock, so [Link.send] must be non-blocking (queue + worker).
 */
class MeshRouter(
    val identity: Identity,
    displayName: String,
    private val listener: RouterListener,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxSeen: Int = 5_000,
    private val maxRelay: Int = 500,
) {
    @Volatile var displayName: String = displayName
        private set

    private class RelayEntry(val packet: Packet, val expiresAt: Long) { val deliveredTo = HashSet<String>() }

    private val lock = ReentrantLock()
    private val log = EventLog.global
    private val links = LinkedHashMap<String, Link>()
    private val peers = LinkedHashMap<String, Peer>()
    private val seen = object : LinkedHashMap<String, Long>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>) = size > maxSeen
    }
    private val relayStore = object : LinkedHashMap<String, RelayEntry>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RelayEntry>) = size > maxRelay
    }

    // Phase 2 policy state.
    private var batteryProfile: Int = BatteryProfile.BALANCED
    private var inboundRateCfg: Pair<Int, Long>? = null
    private val inboundRate = HashMap<String, RateLimiter>()

    val selfId: NodeId get() = identity.nodeId
    /** Store-and-forward retention (72 h, PROTOCOL.md §6). */
    val relayRetentionMs: Long get() = Protocol.RELAY_TTL_MS
    fun batteryProfileCode(): Int = lock.withLock { batteryProfile }
    fun setBatteryProfile(code: Int): Unit = lock.withLock {
        require(BatteryProfile.isValid(code)) { "bad battery profile $code" }
        batteryProfile = code
    }

    /** Cap how many broadcast/SOS packets a single source may push through this node per window. */
    fun setInboundRateLimit(max: Int, windowMs: Long): Unit = lock.withLock {
        require(max > 0 && windowMs > 0)
        inboundRateCfg = max to windowMs
        inboundRate.clear()
    }

    fun peers(): List<Peer> = lock.withLock { peers.values.toList() }
    fun peer(nodeId: NodeId): Peer? = lock.withLock { peers[nodeId.hex] }
    fun linkCount(): Int = lock.withLock { links.size }
    fun linkSnapshots(): List<Pair<String, String?>> = lock.withLock { links.values.map { it.id to it.peerHex } }
    fun directNeighbourCount(): Int = lock.withLock { links.values.count { it.peerHex != null } }

    fun setDisplayName(name: String): Unit = lock.withLock {
        displayName = name
        links.values.forEach { it.send(PacketFactory.announce(identity, name).encode()) }
    }

    /** Restore persisted peers (so direct messages can be sent before they re-announce). */
    fun importPeers(saved: Collection<Peer>): Unit = lock.withLock {
        saved.forEach { if (!peers.containsKey(it.nodeId.hex)) peers[it.nodeId.hex] = it }
    }

    /** Restore persisted relay-store packets after a restart. */
    fun importRelayStore(packets: Collection<Packet>): Unit = lock.withLock {
        val now = clock()
        packets.forEach { p ->
            seen[p.messageIdHex] = now
            relayStore[p.messageIdHex] = RelayEntry(p, now + Protocol.RELAY_TTL_MS)
        }
    }

    fun relayStoreSnapshot(): List<Packet> = lock.withLock { relayStore.values.map { it.packet } }

    // ---- outbound -----------------------------------------------------------------

    fun sendBroadcast(text: String): ByteArray = originate(PacketFactory.broadcastText(identity, text))

    /** @throws IllegalStateException if the peer's public key is unknown. */
    fun sendDirect(destination: NodeId, text: String): ByteArray {
        val peer = peer(destination) ?: throw IllegalStateException("Unknown peer ${destination.display}")
        return originate(PacketFactory.directText(identity, destination, peer.publicKeyWire, text))
    }

    /** Broadcast an SOS beacon. `location` is only sent if the operator opted in to sharing GPS. */
    fun sendSos(text: String, location: SosLocation? = null): ByteArray = originate(PacketFactory.sos(identity, text, location))

    private fun originate(packet: Packet): ByteArray = lock.withLock {
        if (packet.type != PacketType.ACK) log.i("router", "sending ${packet.type} ${packet.messageIdHex.take(8)} on ${links.size} link(s)")
        markSeen(packet)
        store(packet, fromLink = null)
        broadcast(packet, except = null)
        packet.messageId
    }

    // ---- link lifecycle -----------------------------------------------------------

    fun onLinkReady(link: Link): Unit = lock.withLock {
        links[link.id] = link
        link.send(PacketFactory.announce(identity, displayName).encode())
    }

    fun onLinkClosed(link: Link) = lock.withLock { links.remove(link.id); Unit }

    private fun onLinkIdentified(link: Link, peerHex: String) {
        // Duplicate-link suppression: the node with the larger id closes the newer link.
        val duplicate = links.values.any { it !== link && it.peerHex == peerHex }
        if (duplicate && selfId.hex > peerHex) { log.d("router", "closing duplicate link ${link.id} to ${peerHex.takeLast(4)}"); link.close(); links.remove(link.id); return }
        log.i("router", "link ${link.id} is ${peers[peerHex]?.name ?: peerHex.takeLast(4)}")

        peers[peerHex]?.let { listener.onLinkIdentified(link, it) }

        // Store-and-forward replay of anything this peer might still need.
        val peerId = NodeId.fromHex(peerHex)
        val now = clock()
        var replayed = 0
        for (entry in relayStore.values) {
            if (entry.expiresAt < now || peerHex in entry.deliveredTo) continue
            val dest = entry.packet.destination
            val forPeer = dest.hex == peerId.hex
            val isBroadcast = dest.isBroadcast
            val unknownDest = !isBroadcast && !peers.containsKey(dest.hex)
            if (forPeer || isBroadcast || unknownDest) {
                entry.deliveredTo.add(peerHex)
                link.send(entry.packet.encode())
                replayed++
            }
        }
        if (replayed > 0) log.i("router", "replayed $replayed stored packet(s) to ${peerHex.takeLast(4)}")
    }

    // ---- inbound ------------------------------------------------------------------

    fun onReceive(link: Link, bytes: ByteArray): Unit = lock.withLock {
        val p = try { Packet.decode(bytes) } catch (_: Exception) { return }
        val now = clock()
        if (p.timestamp > now + Protocol.SEEN_TTL_MS) return
        if (seen.containsKey(p.messageIdHex)) return
        markSeen(p)
        if (!rateAllows(p)) return // per-source broadcast flood cap (when configured)

        if (p.type == PacketType.ANNOUNCE) { handleAnnounce(link, p, now); return }

        val forMe = p.destination.hex == selfId.hex
        val isBroadcast = p.destination.isBroadcast

        if (p.type == PacketType.SOS) {
            if (!isBroadcast) { relay(p, link); return }
            val peer = peers[p.source.hex]
            val verified = peer != null && Crypto.verify(peer.publicKey, p.encodeUnsigned(), p.signature)
            if (peer != null && !verified) return // forged beacon from a known peer
            val sos = try { SosCodec.decode(p.payload) } catch (_: Exception) { relay(p, link); return }
            listener.onSos(SosBeacon(p.messageId, p.source, peer?.name, sos.text, sos.location, verified, p.timestamp))
            relay(p, link) // beacons always flood onward
            return
        }

        if (forMe || isBroadcast) {
            val peer = peers[p.source.hex]
            val verified = peer != null && Crypto.verify(peer.publicKey, p.encodeUnsigned(), p.signature)
            when {
                peer != null && !verified -> { log.w("router", "dropped ${p.type} ${p.messageIdHex.take(8)}: bad signature for ${p.source.short}"); return }
                forMe && peer == null -> { log.d("router", "${p.type} ${p.messageIdHex.take(8)} for me from unknown ${p.source.short}; relaying"); relay(p, link); return }
            }
            when (p.type) {
                PacketType.MESSAGE -> {
                    val text = if (p.isEncrypted) {
                        try { String(Crypto.decrypt(identity.privateKey, p.messageId, p.source, p.destination, p.payload), Charsets.UTF_8) }
                        catch (_: Exception) { log.w("router", "could not decrypt ${p.messageIdHex.take(8)} from ${p.source.short}"); return }
                    } else String(p.payload, Charsets.UTF_8)
                    log.i("router", "${if (isBroadcast) "broadcast" else "direct"} ${p.messageIdHex.take(8)} from ${peer?.name ?: p.source.short} via ${link.id} (ttl ${p.ttl})")
                    listener.onMessage(InboundMessage(p.messageId, p.source, peer?.name, text, isBroadcast, verified, p.timestamp))
                    if (forMe) originate(PacketFactory.ack(identity, p.source, p.messageId))
                }
                PacketType.ACK -> if (forMe && p.payload.size == Protocol.MESSAGE_ID_SIZE) listener.onAck(p.payload, p.source)
                PacketType.ANNOUNCE -> {}
                PacketType.SOS -> {} // handled above
            }
        }
        if (!forMe) relay(p, link)
    }

    private fun handleAnnounce(link: Link, p: Packet, now: Long) {
        val ann = try { Announce.decode(p.payload) } catch (_: Exception) { return }
        if (NodeId.fromPublicKey(ann.publicKeyWire).hex != p.source.hex) return
        val publicKey = try { Crypto.publicKeyFromWire(ann.publicKeyWire) } catch (_: Exception) { return }
        if (!Crypto.verify(publicKey, p.encodeUnsigned(), p.signature)) return
        if (p.source.hex == selfId.hex) return

        val hops = Protocol.MAX_TTL - p.ttl + 1
        val prev = peers[p.source.hex]
        if (prev == null) log.i("router", "new peer ${ann.name} (${p.source.short}) at $hops hop(s)")
        peers[p.source.hex] = Peer(p.source, publicKey, ann.publicKeyWire, ann.name, now, if (prev != null) minOf(prev.hops, hops) else hops)
        listener.onPeersChanged(peers.values.toList())

        if (link.peerHex == null && hops == 1) {
            link.peerHex = p.source.hex
            onLinkIdentified(link, p.source.hex)
        }
        relay(p, link)
    }

    // ---- internals ----------------------------------------------------------------

    /** ANNOUNCEs and SOS beacons always relay (safety/liveness); ordinary chat does not in POWER_SAVER. */
    private fun isCritical(p: Packet) = p.type == PacketType.SOS || p.type == PacketType.ANNOUNCE

    private fun rateAllows(p: Packet): Boolean {
        val cfg = inboundRateCfg ?: return true
        if (p.type != PacketType.MESSAGE && p.type != PacketType.SOS) return true
        if (!p.destination.isBroadcast) return true // direct E2E is never flood-gated
        val limiter = inboundRate.getOrPut(p.source.hex) { RateLimiter(cfg.first, cfg.second) }
        return limiter.allow()
    }

    private fun relay(p: Packet, from: Link) {
        if (p.ttl <= 1) return
        if (batteryProfile == BatteryProfile.POWER_SAVER && !isCritical(p)) return
        val relayed = p.withTtl(p.ttl - 1)
        store(relayed, from)
        broadcast(relayed, from)
    }

    private fun broadcast(p: Packet, except: Link?) {
        val bytes = p.encode()
        val entry = relayStore[p.messageIdHex]
        for (link in links.values) {
            if (link === except) continue
            link.peerHex?.let { entry?.deliveredTo?.add(it) }
            link.send(bytes)
        }
    }

    private fun store(p: Packet, fromLink: Link?) {
        if (p.type == PacketType.ANNOUNCE) return
        val entry = RelayEntry(p, clock() + Protocol.RELAY_TTL_MS)
        fromLink?.peerHex?.let { entry.deliveredTo.add(it) }
        relayStore[p.messageIdHex] = entry
    }

    private fun markSeen(p: Packet) { seen[p.messageIdHex] = clock() }
}
