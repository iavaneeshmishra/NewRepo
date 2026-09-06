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

interface RouterListener {
    fun onMessage(message: InboundMessage)
    fun onAck(messageId: ByteArray, from: NodeId)
    fun onPeersChanged(peers: List<Peer>)
    fun onLinkIdentified(link: Link, peer: Peer) {}
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
    private val links = LinkedHashMap<String, Link>()
    private val peers = LinkedHashMap<String, Peer>()
    private val seen = object : LinkedHashMap<String, Long>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>) = size > maxSeen
    }
    private val relayStore = object : LinkedHashMap<String, RelayEntry>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RelayEntry>) = size > maxRelay
    }

    val selfId: NodeId get() = identity.nodeId
    fun peers(): List<Peer> = lock.withLock { peers.values.toList() }
    fun peer(nodeId: NodeId): Peer? = lock.withLock { peers[nodeId.hex] }
    fun linkCount(): Int = lock.withLock { links.size }
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

    private fun originate(packet: Packet): ByteArray = lock.withLock {
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
        if (duplicate && selfId.hex > peerHex) { link.close(); links.remove(link.id); return }

        peers[peerHex]?.let { listener.onLinkIdentified(link, it) }

        // Store-and-forward replay of anything this peer might still need.
        val peerId = NodeId.fromHex(peerHex)
        val now = clock()
        for (entry in relayStore.values) {
            if (entry.expiresAt < now || peerHex in entry.deliveredTo) continue
            val dest = entry.packet.destination
            val forPeer = dest.hex == peerId.hex
            val isBroadcast = dest.isBroadcast
            val unknownDest = !isBroadcast && !peers.containsKey(dest.hex)
            if (forPeer || isBroadcast || unknownDest) {
                entry.deliveredTo.add(peerHex)
                link.send(entry.packet.encode())
            }
        }
    }

    // ---- inbound ------------------------------------------------------------------

    fun onReceive(link: Link, bytes: ByteArray): Unit = lock.withLock {
        val p = try { Packet.decode(bytes) } catch (_: Exception) { return }
        val now = clock()
        if (p.timestamp > now + Protocol.SEEN_TTL_MS) return
        if (seen.containsKey(p.messageIdHex)) return
        markSeen(p)

        if (p.type == PacketType.ANNOUNCE) { handleAnnounce(link, p, now); return }

        val forMe = p.destination.hex == selfId.hex
        val isBroadcast = p.destination.isBroadcast
        if (forMe || isBroadcast) {
            val peer = peers[p.source.hex]
            val verified = peer != null && Crypto.verify(peer.publicKey, p.encodeUnsigned(), p.signature)
            when {
                peer != null && !verified -> return                     // forged: known key, bad signature
                forMe && peer == null -> { relay(p, link); return }     // can't verify or decrypt yet
            }
            when (p.type) {
                PacketType.MESSAGE -> {
                    val text = if (p.isEncrypted) {
                        try { String(Crypto.decrypt(identity.privateKey, p.messageId, p.source, p.destination, p.payload), Charsets.UTF_8) }
                        catch (_: Exception) { return }
                    } else String(p.payload, Charsets.UTF_8)
                    listener.onMessage(InboundMessage(p.messageId, p.source, peer?.name, text, isBroadcast, verified, p.timestamp))
                    if (forMe) originate(PacketFactory.ack(identity, p.source, p.messageId))
                }
                PacketType.ACK -> if (forMe && p.payload.size == Protocol.MESSAGE_ID_SIZE) listener.onAck(p.payload, p.source)
                PacketType.ANNOUNCE -> {}
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
        peers[p.source.hex] = Peer(p.source, publicKey, ann.publicKeyWire, ann.name, now, if (prev != null) minOf(prev.hops, hops) else hops)
        listener.onPeersChanged(peers.values.toList())

        if (link.peerHex == null && hops == 1) {
            link.peerHex = p.source.hex
            onLinkIdentified(link, p.source.hex)
        }
        relay(p, link)
    }

    // ---- internals ----------------------------------------------------------------

    private fun relay(p: Packet, from: Link) {
        if (p.ttl <= 1) return
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
