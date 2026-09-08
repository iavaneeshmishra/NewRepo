import Foundation
import Combine
import CoreBluetooth
import SwiftData
import UserNotifications
import UIKit
import os

struct MeshStatus: Equatable {
    var bluetoothOn = false
    var advertising = false
    var directLinks = 0
    var knownPeers = 0
    var loopback = false
}

/// Snapshot of one link for the Diagnostics screen.
struct LinkInfo: Identifiable {
    let id: String
    let role: String            // "central" / "peripheral" / "sim"
    let peerName: String?
    let peerShort: String?
    let rssi: Int?
    let frameSize: Int
    let bytesIn: Int, bytesOut: Int, packetsIn: Int, packetsOut: Int
    let age: TimeInterval
}

/// App-lifetime owner of the `MeshRouter` and both BLE roles. Persists inbound
/// traffic to SwiftData and publishes status for the UI.
@MainActor
final class MeshService: ObservableObject, RouterListener {
    private static let log = Logger(subsystem: "app.ripple.mesh", category: "service")

    @Published private(set) var status = MeshStatus()
    @Published var displayName: String
    @Published private(set) var powerProfile = BatteryProfile.balanced
    /// Most recently received SOS beacon (drives the SOS screen + notification).
    @Published private(set) var recentSos: SosBeacon?

    let router: MeshRouter
    let container: ModelContainer
    private var central: BleCentral!
    private var peripheral: BlePeripheral!
    private var housekeeping: Timer?
    private var loopback: Loopback?
    private let previousCrash: String?
    let eventLog = EventLog.global

    /// Conversation currently on screen; suppresses and clears its notifications.
    var visibleConversation: String? {
        didSet {
            guard let conversation = visibleConversation else { return }
            let center = UNUserNotificationCenter.current()
            center.getDeliveredNotifications { notifications in
                let identifiers = notifications
                    .filter { $0.request.content.threadIdentifier == conversation }
                    .map(\.request.identifier)
                center.removeDeliveredNotifications(withIdentifiers: identifiers)
            }
        }
    }

    init(container: ModelContainer) {
        self.previousCrash = CrashLog.takePending()
        self.container = container
        let identity = IdentityStore.load()
        let name = IdentityStore.displayName ?? "Ripple \(identity.nodeId.short)"
        displayName = name
        router = MeshRouter(identity: identity, displayName: name, listener: nil)
        router.listener = self
        Self.log.info("identity \(identity.nodeId.display)")
        eventLog.i("service", "started; node \(identity.nodeId.display); iOS \(UIDevice.current.systemVersion); \(UIDevice.current.model)")

        // Restore the persisted power profile before rebuilding in-memory state.
        if let code = IdentityStore.powerProfile, let profile = BatteryProfile(rawValue: code) {
            powerProfile = profile
            router.setBatteryProfile(code)
        }

        restoreState()

        let r = router
        central = BleCentral(onPacket: { l, b in r.onReceive(l, b) },
                             onLinkReady: { [weak self] l in r.onLinkReady(l); self?.scheduleStatusRefresh() },
                             onLinkClosed: { [weak self] l in r.onLinkClosed(l); self?.scheduleStatusRefresh() })
        peripheral = BlePeripheral(onPacket: { l, b in r.onReceive(l, b) },
                                   onLinkReady: { [weak self] l in r.onLinkReady(l); self?.scheduleStatusRefresh() },
                                   onLinkClosed: { [weak self] l in r.onLinkClosed(l); self?.scheduleStatusRefresh() })
        central.onStateChange = { [weak self] s in
            Task { @MainActor [weak self] in self?.status.bluetoothOn = (s == .poweredOn); self?.refreshStatus() }
        }
        peripheral.onStateChange = { [weak self] _ in self?.scheduleStatusRefresh() }

        housekeeping = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.persistRelayStore()
                self.pruneSosHistory()
                self.central.startScanning()
                self.central.refreshRssi()
            }
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

    /// Received SOS beacons are retained for ~90 days before being pruned.
    private static let sosRetention: TimeInterval = 90 * 24 * 3600

    private func pruneSosHistory() {
        let ctx = container.mainContext
        let cutoff = Date().addingTimeInterval(-Self.sosRetention)
        if let old = try? ctx.fetch(FetchDescriptor<SosRecord>(predicate: #Predicate { $0.timestamp < cutoff })) {
            old.forEach { ctx.delete($0) }
            try? ctx.save()
        }
    }

    // MARK: BLE glue

    nonisolated private func scheduleStatusRefresh() {
        Task { @MainActor [weak self] in self?.refreshStatus() }
    }

    private func refreshStatus() {
        status.directLinks = router.directNeighbourCount()
        status.knownPeers = router.allPeers().count
        status.advertising = peripheral.isAdvertising
    }

    // MARK: RouterListener (called on the router queue)

    nonisolated func router(_ router: MeshRouter, didReceive m: InboundMessage) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            let conversation = m.isBroadcast ? Persistence.broadcastConversation : m.from.hex
            let rec = MessageRecord(messageId: m.messageId.hex, conversation: conversation, fromNodeId: m.from.hex, fromName: m.fromName,
                                    text: m.text, timestamp: Date(timeIntervalSince1970: Double(m.timestamp) / 1000), outgoing: false,
                                    status: .received, verified: m.verified)
            self.container.mainContext.insert(rec)
            try? self.container.mainContext.save()
            self.refreshBadge()
            if self.visibleConversation != conversation { self.notify(rec) }
        }
    }

    nonisolated func router(_ router: MeshRouter, didReceiveAck messageId: Data, from: NodeId) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            let id = messageId.hex
            if let rec = try? self.container.mainContext.fetch(FetchDescriptor<MessageRecord>(predicate: #Predicate { $0.messageId == id })).first {
                rec.status = .delivered
                try? self.container.mainContext.save()
            }
        }
    }

    nonisolated func router(_ router: MeshRouter, peersDidChange peers: [Peer]) {
        Task { @MainActor [weak self] in
            guard let self else { return }
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
        scheduleStatusRefresh()
    }

    nonisolated func router(_ router: MeshRouter, didReceiveSos beacon: SosBeacon) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            self.recentSos = beacon
            let rec = SosRecord(messageId: beacon.messageId.hex, fromNodeId: beacon.from.hex, fromName: beacon.fromName,
                                text: beacon.text, latE7: beacon.location?.latE7, lngE7: beacon.location?.lngE7,
                                accuracyMeters: beacon.location?.accuracyMeters, verified: beacon.verified,
                                timestamp: Date(timeIntervalSince1970: Double(beacon.timestamp) / 1000))
            self.container.mainContext.insert(rec)
            try? self.container.mainContext.save()
            let content = UNMutableNotificationContent()
            content.title = "SOS — \(beacon.fromName ?? beacon.from.display)"
            content.body = beacon.text.isEmpty ? "An SOS beacon is active nearby." : beacon.text
            content.sound = .default
            content.threadIdentifier = "sos"
            UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: beacon.messageId.hex, content: content, trigger: nil))
        }
    }

    // MARK: Diagnostics

    func linkInfos() -> [LinkInfo] {
        func info(_ l: BleLink, _ role: String) -> LinkInfo {
            let peer = l.peerHex.flatMap { NodeId(hex: $0) }.flatMap { router.peer($0) }
            return LinkInfo(id: l.id, role: role, peerName: peer?.name, peerShort: l.peerHex.map { String($0.suffix(4)) }, rssi: l.rssi, frameSize: l.frameSize,
                            bytesIn: l.bytesIn, bytesOut: l.bytesOut, packetsIn: l.packetsIn, packetsOut: l.packetsOut, age: Date().timeIntervalSince(l.openedAt))
        }
        var out = central.allLinks().map { info($0, "central") } + peripheral.allLinks().map { info($0, "peripheral") }
        if loopback?.isRunning == true {
            for s in router.linkSnapshots() where s.id.hasPrefix("sim:") {
                let peer = s.peerHex.flatMap { NodeId(hex: $0) }.flatMap { router.peer($0) }
                out.append(LinkInfo(id: s.id, role: "sim", peerName: peer?.name, peerShort: s.peerHex.map { String($0.suffix(4)) }, rssi: nil, frameSize: 0, bytesIn: 0, bytesOut: 0, packetsIn: 0, packetsOut: 0, age: 0))
            }
        }
        return out
    }

    func setLoopback(_ enabled: Bool) {
        if enabled, loopback == nil { let l = Loopback(local: router); loopback = l; l.start() }
        else if !enabled { loopback?.stop(); loopback = nil }
        status.loopback = enabled
        refreshStatus()
    }

    func diagnosticsHeader() -> String {
        var s = "Ripple diagnostics\n"
        s += "node: \(router.selfId.display)  name: \(displayName)\n"
        s += "device: \(UIDevice.current.model)  iOS \(UIDevice.current.systemVersion)\n"
        s += "bluetooth: \(status.bluetoothOn ? "on" : "off")  advertising: \(peripheral.isAdvertising)  loopback: \(loopback?.isRunning ?? false)\n"
        s += "links: \(router.linkCount())  identified: \(router.directNeighbourCount())  peers known: \(router.allPeers().count)  relay store: \(router.relayStoreSnapshot().count)\n"
        if let previousCrash { s += "previous crash: \(previousCrash)\n" }
        for l in linkInfos() { s += "  \(l.id) [\(l.role)] peer=\(l.peerName ?? l.peerShort ?? "?") rssi=\(l.rssi.map(String.init) ?? "?") frame=\(l.frameSize) in=\(l.packetsIn)p/\(l.bytesIn)B out=\(l.packetsOut)p/\(l.bytesOut)B\n" }
        for p in router.allPeers() { s += "  peer \(p.name) (\(p.nodeId.short)) hops=\(p.hops) seen=\(EventLog.formatTime(p.lastSeen))\n" }
        return s
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

    /// Broadcast an SOS beacon. Pass a `SosLocation` only when the operator opted in to sharing GPS.
    func sendSos(_ text: String, location: SosLocation?) {
        _ = try? router.sendSos(text: text, location: location)
    }

    func setPowerProfile(_ profile: BatteryProfile) {
        powerProfile = profile
        IdentityStore.powerProfile = profile.rawValue
        router.setBatteryProfile(profile.rawValue)
    }

    func markRead(_ conversation: String) {
        let ctx = container.mainContext
        let received = MessageStatus.received.rawValue
        if let unread = try? ctx.fetch(FetchDescriptor<MessageRecord>(predicate: #Predicate { $0.conversation == conversation && $0.statusRaw == received && !$0.outgoing })) {
            unread.forEach { $0.status = .delivered }
            try? ctx.save()
        }
        refreshBadge()
    }

    private func refreshBadge() {
        let received = MessageStatus.received.rawValue
        let descriptor = FetchDescriptor<MessageRecord>(predicate: #Predicate { $0.statusRaw == received && !$0.outgoing })
        let unread = (try? container.mainContext.fetch(descriptor).count) ?? 0
        if #available(iOS 16.0, *) {
            UNUserNotificationCenter.current().setBadgeCount(unread) { _ in }
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
