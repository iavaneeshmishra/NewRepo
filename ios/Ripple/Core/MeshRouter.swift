import Foundation
import CryptoKit

/// A bidirectional byte pipe to one neighbour. Implemented by the BLE layer.
protocol Link: AnyObject {
    var id: String { get }
    /// Hex NodeId of the peer once learned from its ANNOUNCE.
    var peerHex: String? { get set }
    /// Must be non-blocking (queue + worker).
    func send(_ packetBytes: Data)
    func close()
}

struct Peer {
    let nodeId: NodeId
    let publicKey: P256.Signing.PublicKey
    let publicKeyWire: Data
    let name: String
    let lastSeen: Date
    /// Best known hop distance (1 = direct neighbour).
    let hops: Int
}

struct InboundMessage {
    let messageId: Data
    let from: NodeId
    let fromName: String?
    let text: String
    let isBroadcast: Bool
    let verified: Bool
    let timestamp: UInt64
}

protocol RouterListener: AnyObject {
    func router(_ router: MeshRouter, didReceive message: InboundMessage)
    func router(_ router: MeshRouter, didReceiveAck messageId: Data, from: NodeId)
    func router(_ router: MeshRouter, peersDidChange peers: [Peer])
    func router(_ router: MeshRouter, didIdentify link: Link, as peer: Peer)
}

extension RouterListener {
    func router(_ router: MeshRouter, didIdentify link: Link, as peer: Peer) {}
}

/// Transport-agnostic implementation of PROTOCOL.md §4. Behaviourally identical to
/// the Kotlin `MeshRouter` and `tools/protocol/mesh-sim.js`.
///
/// Thread-safe: every public entry point runs on the router's serial queue.
final class MeshRouter {
    let identity: Identity
    private(set) var displayName: String
    weak var listener: RouterListener?
    private let now: () -> Date
    private let maxSeen: Int
    private let maxRelay: Int

    private final class RelayEntry {
        let packet: Packet; let expiresAt: Date; var deliveredTo = Set<String>()
        init(_ p: Packet, expiresAt: Date) { packet = p; self.expiresAt = expiresAt }
    }

    private let queue = DispatchQueue(label: "app.ripple.mesh.router")
    private let log = EventLog.global
    private static let queueKey = DispatchSpecificKey<Void>()

    /// `queue.sync` that tolerates re-entrancy (e.g. a link closed from inside the
    /// router calling back into `onLinkClosed`). Mirrors Kotlin's ReentrantLock.
    private func locked<T>(_ body: () throws -> T) rethrows -> T {
        if DispatchQueue.getSpecific(key: Self.queueKey) != nil { return try body() }
        return try queue.sync(execute: body)
    }
    private var links: [String: Link] = [:]
    private var linkOrder: [String] = []
    private var peers: [String: Peer] = [:]
    private var seen: [String: Date] = [:]
    private var seenOrder: [String] = []
    private var relayStore: [String: RelayEntry] = [:]
    private var relayOrder: [String] = []

    init(identity: Identity, displayName: String, listener: RouterListener?, now: @escaping () -> Date = Date.init, maxSeen: Int = 5000, maxRelay: Int = 500) {
        self.identity = identity; self.displayName = displayName; self.listener = listener
        self.now = now; self.maxSeen = maxSeen; self.maxRelay = maxRelay
        queue.setSpecific(key: Self.queueKey, value: ())
    }

    var selfId: NodeId { identity.nodeId }
    func allPeers() -> [Peer] { locked { Array(peers.values) } }
    func peer(_ id: NodeId) -> Peer? { locked { peers[id.hex] } }
    func linkCount() -> Int { locked { links.count } }
    func linkSnapshots() -> [(id: String, peerHex: String?)] { locked { linkOrder.compactMap { id in links[id].map { ($0.id, $0.peerHex) } } } }
    func directNeighbourCount() -> Int { locked { links.values.filter { $0.peerHex != nil }.count } }

    func setDisplayName(_ name: String) {
        locked {
            displayName = name
            guard let ann = try? PacketFactory.announce(identity, name: name).encode() else { return }
            links.values.forEach { $0.send(ann) }
        }
    }

    func importPeers(_ saved: [Peer]) {
        locked { for p in saved where peers[p.nodeId.hex] == nil { peers[p.nodeId.hex] = p } }
    }

    func importRelayStore(_ packets: [Packet]) {
        locked {
            let t = now()
            for p in packets {
                markSeen(p, at: t)
                putRelay(p.messageIdHex, RelayEntry(p, expiresAt: t.addingTimeInterval(MeshProtocol.relayTTL)))
            }
        }
    }

    func relayStoreSnapshot() -> [Packet] { locked { relayOrder.compactMap { relayStore[$0]?.packet } } }

    // MARK: Outbound

    @discardableResult
    func sendBroadcast(_ text: String) throws -> Data {
        originate(try PacketFactory.broadcastText(identity, text))
    }

    enum SendError: Error { case unknownPeer(NodeId) }

    @discardableResult
    func sendDirect(to destination: NodeId, text: String) throws -> Data {
        guard let peer = peer(destination) else { throw SendError.unknownPeer(destination) }
        return originate(try PacketFactory.directText(identity, to: destination, recipientWire: peer.publicKeyWire, text: text))
    }

    private func originate(_ packet: Packet) -> Data {
        locked { originateLocked(packet) }
    }

    private func originateLocked(_ packet: Packet) -> Data {
        if packet.type != .ack { log.i("router", "sending \(packet.type) \(packet.messageIdHex.prefix(8)) on \(links.count) link(s)") }
        markSeen(packet, at: now())
        store(packet, from: nil)
        broadcast(packet, except: nil)
        return packet.messageId
    }

    // MARK: Link lifecycle

    func onLinkReady(_ link: Link) {
        locked {
            links[link.id] = link; linkOrder.append(link.id)
            if let ann = try? PacketFactory.announce(identity, name: displayName).encode() { link.send(ann) }
        }
    }

    func onLinkClosed(_ link: Link) {
        locked { removeLink(link.id) }
    }

    private func removeLink(_ id: String) {
        links[id] = nil; linkOrder.removeAll { $0 == id }
    }

    private func onLinkIdentified(_ link: Link, peerHex: String) {
        let duplicate = links.values.contains { $0 !== link && $0.peerHex == peerHex }
        if duplicate && selfId.hex > peerHex { log.d("router", "closing duplicate link \(link.id) to \(peerHex.suffix(4))"); link.close(); removeLink(link.id); return }
        log.i("router", "link \(link.id) is \(peers[peerHex]?.name ?? String(peerHex.suffix(4)))")

        if let p = peers[peerHex] { listener?.router(self, didIdentify: link, as: p) }

        let t = now()
        var replayed = 0
        defer { if replayed > 0 { log.i("router", "replayed \(replayed) stored packet(s) to \(peerHex.suffix(4))") } }
        for key in relayOrder {
            guard let entry = relayStore[key], entry.expiresAt > t, !entry.deliveredTo.contains(peerHex) else { continue }
            let dest = entry.packet.destination
            let forPeer = dest.hex == peerHex
            let unknownDest = !dest.isBroadcast && peers[dest.hex] == nil
            if forPeer || dest.isBroadcast || unknownDest {
                entry.deliveredTo.insert(peerHex)
                link.send(entry.packet.encode())
                replayed += 1
            }
        }
    }

    // MARK: Inbound

    func onReceive(_ link: Link, _ bytes: Data) {
        locked { receiveLocked(link, bytes) }
    }

    private func receiveLocked(_ link: Link, _ bytes: Data) {
        guard let p = try? Packet.decode(bytes) else { return }
        let t = now()
        if Double(p.timestamp) / 1000 > t.timeIntervalSince1970 + MeshProtocol.seenTTL { return }
        if seen[p.messageIdHex] != nil { return }
        markSeen(p, at: t)

        if p.type == .announce { handleAnnounce(link, p, at: t); return }

        let forMe = p.destination == selfId
        let isBroadcast = p.destination.isBroadcast
        if forMe || isBroadcast {
            let peer = peers[p.source.hex]
            let verified = peer.map { Crypto.verify($0.publicKey, unsignedPacket: p.encodeUnsigned(), rawSignature: p.signature) } ?? false
            if peer != nil && !verified { log.w("router", "dropped \(p.type) \(p.messageIdHex.prefix(8)): bad signature for \(p.source.short)"); return }
            if forMe && peer == nil { log.d("router", "\(p.type) \(p.messageIdHex.prefix(8)) for me from unknown \(p.source.short); relaying"); relay(p, from: link); return }

            switch p.type {
            case .message:
                let text: String
                if p.isEncrypted {
                    guard let plain = try? Crypto.decrypt(recipient: identity.agreement, messageId: p.messageId, source: p.source, destination: p.destination, payload: p.payload),
                          let s = String(data: plain, encoding: .utf8) else { log.w("router", "could not decrypt \(p.messageIdHex.prefix(8)) from \(p.source.short)"); return }
                    text = s
                } else {
                    guard let s = String(data: p.payload, encoding: .utf8) else { return }
                    text = s
                }
                log.i("router", "\(isBroadcast ? "broadcast" : "direct") \(p.messageIdHex.prefix(8)) from \(peer?.name ?? p.source.short) via \(link.id) (ttl \(p.ttl))")
                listener?.router(self, didReceive: InboundMessage(messageId: p.messageId, from: p.source, fromName: peer?.name, text: text, isBroadcast: isBroadcast, verified: verified, timestamp: p.timestamp))
                if forMe, let ack = try? PacketFactory.ack(identity, to: p.source, acknowledged: p.messageId) { _ = originateLocked(ack) }
            case .ack:
                if forMe && p.payload.count == MeshProtocol.messageIdSize { listener?.router(self, didReceiveAck: p.payload, from: p.source) }
            case .announce:
                break
            }
        }
        if !forMe { relay(p, from: link) }
    }

    private func handleAnnounce(_ link: Link, _ p: Packet, at t: Date) {
        guard let ann = try? Announce.decode(p.payload),
              NodeId.fromPublicKey(ann.publicKeyWire) == p.source,
              let key = try? Crypto.signingKey(fromWire: ann.publicKeyWire),
              Crypto.verify(key, unsignedPacket: p.encodeUnsigned(), rawSignature: p.signature),
              p.source != selfId else { return }

        let hops = Int(MeshProtocol.maxTTL) - Int(p.ttl) + 1
        let prev = peers[p.source.hex]
        if prev == nil { log.i("router", "new peer \(ann.name) (\(p.source.short)) at \(hops) hop(s)") }
        peers[p.source.hex] = Peer(nodeId: p.source, publicKey: key, publicKeyWire: ann.publicKeyWire, name: ann.name, lastSeen: t, hops: prev.map { min($0.hops, hops) } ?? hops)
        listener?.router(self, peersDidChange: Array(peers.values))

        if link.peerHex == nil && hops == 1 {
            link.peerHex = p.source.hex
            onLinkIdentified(link, peerHex: p.source.hex)
        }
        relay(p, from: link)
    }

    // MARK: Internals

    private func relay(_ p: Packet, from: Link) {
        guard p.ttl > 1 else { return }
        let relayed = p.withTTL(p.ttl - 1)
        store(relayed, from: from)
        broadcast(relayed, except: from)
    }

    private func broadcast(_ p: Packet, except: Link?) {
        let bytes = p.encode()
        let entry = relayStore[p.messageIdHex]
        for id in linkOrder {
            guard let link = links[id], link !== except else { continue }
            if let ph = link.peerHex { entry?.deliveredTo.insert(ph) }
            link.send(bytes)
        }
    }

    private func store(_ p: Packet, from: Link?) {
        guard p.type != .announce else { return }
        let entry = RelayEntry(p, expiresAt: now().addingTimeInterval(MeshProtocol.relayTTL))
        if let ph = from?.peerHex { entry.deliveredTo.insert(ph) }
        putRelay(p.messageIdHex, entry)
    }

    private func putRelay(_ key: String, _ entry: RelayEntry) {
        if relayStore[key] == nil { relayOrder.append(key) }
        relayStore[key] = entry
        while relayOrder.count > maxRelay { relayStore[relayOrder.removeFirst()] = nil }
    }

    private func markSeen(_ p: Packet, at t: Date) {
        if seen[p.messageIdHex] == nil { seenOrder.append(p.messageIdHex) }
        seen[p.messageIdHex] = t
        while seenOrder.count > maxSeen { seen[seenOrder.removeFirst()] = nil }
    }
}
