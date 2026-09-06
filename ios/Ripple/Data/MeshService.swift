import Foundation
import Combine
import CoreBluetooth
import SwiftData
import UserNotifications
import os

struct MeshStatus: Equatable {
    var bluetoothOn = false
    var advertising = false
    var directLinks = 0
    var knownPeers = 0
}

/// App-lifetime owner of the `MeshRouter` and both BLE roles. Persists inbound
/// traffic to SwiftData and publishes status for the UI.
@MainActor
final class MeshService: ObservableObject, RouterListener {
    private static let log = Logger(subsystem: "app.ripple.mesh", category: "service")

    @Published private(set) var status = MeshStatus()
    @Published var displayName: String

    let router: MeshRouter
    let container: ModelContainer
    private var central: BleCentral!
    private var peripheral: BlePeripheral!
    private var housekeeping: Timer?

    /// Conversation currently on screen; suppresses its notifications.
    var visibleConversation: String?

    init(container: ModelContainer) {
        self.container = container
        let identity = IdentityStore.load()
        let name = IdentityStore.displayName ?? "Ripple \(identity.nodeId.short)"
        displayName = name
        router = MeshRouter(identity: identity, displayName: name, listener: nil)
        router.listener = self
        Self.log.info("identity \(identity.nodeId.display)")

        restoreState()

        central = BleCentral(onPacket: { [weak self] l, b in self?.router.onReceive(l, b) },
                             onLinkReady: { [weak self] l in self?.linkReady(l) },
                             onLinkClosed: { [weak self] l in self?.linkClosed(l) })
        peripheral = BlePeripheral(onPacket: { [weak self] l, b in self?.router.onReceive(l, b) },
                                   onLinkReady: { [weak self] l in self?.linkReady(l) },
                                   onLinkClosed: { [weak self] l in self?.linkClosed(l) })
        central.onStateChange = { [weak self] s in Task { @MainActor in self?.status.bluetoothOn = (s == .poweredOn); self?.refreshStatus() } }
        peripheral.onStateChange = { [weak self] _ in Task { @MainActor in self?.refreshStatus() } }

        housekeeping = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.persistRelayStore(); self?.central.startScanning() }
        }
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    // MARK: State restore / persist

    private func restoreState() {
        let ctx = container.mainContext
        if let peers = try? ctx.fetch(FetchDescriptor<PeerRecord>()) {
            router.importPeers(peers.compactMap { r in
                guard let id = NodeId(hex: r.nodeId), let key = try? Crypto.signingKey(fromWire: r.publicKeyWire) else { return nil }
                return Peer(nodeId: id, publicKey: key, publicKeyWire: r.publicKeyWire, name: r.name, lastSeen: r.lastSeen, hops: r.hops)
            })
        }
        let now = Date()
        if let packets = try? ctx.fetch(FetchDescriptor<RelayPacketRecord>()) {
            var live: [Packet] = []
            for r in packets {
                if r.expiresAt <= now { ctx.delete(r) } else if let p = try? Packet.decode(r.bytes) { live.append(p) }
            }
            router.importRelayStore(live)
        }
        try? ctx.save()
        status.knownPeers = router.allPeers().count
    }

    private func persistRelayStore() {
        let ctx = container.mainContext
        let expires = Date().addingTimeInterval(MeshProtocol.relayTTL)
        for p in router.relayStoreSnapshot() {
            let id = p.messageIdHex
            let existing = try? ctx.fetch(FetchDescriptor<RelayPacketRecord>(predicate: #Predicate { $0.messageId == id })).first
            if existing == nil { ctx.insert(RelayPacketRecord(messageId: id, bytes: p.encode(), expiresAt: expires)) }
        }
        try? ctx.save()
    }

    // MARK: BLE glue

    nonisolated private func linkReady(_ link: BleLink) {
        router.onLinkReady(link)
        Task { @MainActor in self.refreshStatus() }
    }

    nonisolated private func linkClosed(_ link: BleLink) {
        router.onLinkClosed(link)
        Task { @MainActor in self.refreshStatus() }
    }

    private func refreshStatus() {
        status.directLinks = router.directNeighbourCount()
        status.knownPeers = router.allPeers().count
        status.advertising = peripheral.isAdvertising
    }

    // MARK: RouterListener (called on the router queue)

    nonisolated func router(_ router: MeshRouter, didReceive m: InboundMessage) {
        Task { @MainActor in
            let conversation = m.isBroadcast ? Persistence.broadcastConversation : m.from.hex
            let rec = MessageRecord(messageId: m.messageId.hex, conversation: conversation, fromNodeId: m.from.hex, fromName: m.fromName,
                                    text: m.text, timestamp: Date(timeIntervalSince1970: Double(m.timestamp) / 1000), outgoing: false,
                                    status: .received, verified: m.verified)
            self.container.mainContext.insert(rec)
            try? self.container.mainContext.save()
            if self.visibleConversation != conversation { self.notify(rec) }
        }
    }

    nonisolated func router(_ router: MeshRouter, didReceiveAck messageId: Data, from: NodeId) {
        Task { @MainActor in
            let id = messageId.hex
            if let rec = try? self.container.mainContext.fetch(FetchDescriptor<MessageRecord>(predicate: #Predicate { $0.messageId == id })).first {
                rec.status = .delivered
                try? self.container.mainContext.save()
            }
        }
    }

    nonisolated func router(_ router: MeshRouter, peersDidChange peers: [Peer]) {
        Task { @MainActor in
            let ctx = self.container.mainContext
            for p in peers {
                let id = p.nodeId.hex
                if let rec = try? ctx.fetch(FetchDescriptor<PeerRecord>(predicate: #Predicate { $0.nodeId == id })).first {
                    rec.name = p.name; rec.lastSeen = p.lastSeen; rec.hops = p.hops; rec.publicKeyWire = p.publicKeyWire
                } else {
                    ctx.insert(PeerRecord(nodeId: id, publicKeyWire: p.publicKeyWire, name: p.name, lastSeen: p.lastSeen, hops: p.hops))
                }
            }
            try? ctx.save()
            self.refreshStatus()
        }
    }

    nonisolated func router(_ router: MeshRouter, didIdentify link: Link, as peer: Peer) {
        Task { @MainActor in self.refreshStatus() }
    }

    // MARK: API for the UI

    func send(conversation: String, text: String) {
        let ctx = container.mainContext
        let isBroadcast = conversation == Persistence.broadcastConversation
        var status: MessageStatus = router.linkCount() > 0 ? .sent : .pending
        var id: Data
        do {
            if isBroadcast {
                id = try router.sendBroadcast(text)
            } else {
                guard let dest = NodeId(hex: conversation) else { return }
                id = try router.sendDirect(to: dest, text: text)
            }
        } catch {
            id = Crypto.randomBytes(16); status = .failed
        }
        ctx.insert(MessageRecord(messageId: id.hex, conversation: conversation, fromNodeId: router.selfId.hex, fromName: displayName,
                                 text: text, timestamp: Date(), outgoing: true, status: status, verified: true))
        try? ctx.save()
        persistRelayStore()
    }

    func setDisplayName(_ name: String) {
        displayName = name
        IdentityStore.displayName = name
        router.setDisplayName(name)
    }

    func markRead(_ conversation: String) {
        let ctx = container.mainContext
        let received = MessageStatus.received.rawValue
        if let unread = try? ctx.fetch(FetchDescriptor<MessageRecord>(predicate: #Predicate { $0.conversation == conversation && $0.statusRaw == received && !$0.outgoing })) {
            unread.forEach { $0.status = .delivered }
            try? ctx.save()
        }
    }

    private func notify(_ m: MessageRecord) {
        let content = UNMutableNotificationContent()
        content.title = m.fromName ?? (NodeId(hex: m.fromNodeId)?.display ?? "Ripple")
        content.body = m.text
        content.sound = .default
        content.threadIdentifier = m.conversation
        content.userInfo = ["conversation": m.conversation]
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: m.messageId, content: content, trigger: nil))
    }
}
