import Foundation
import SwiftData

enum MessageStatus: String, Codable { case pending, sent, delivered, received, failed }

/// One chat message. `conversation` is "broadcast" for the public channel, or the
/// hex NodeId of the other party for a direct conversation.
@Model
final class MessageRecord {
    @Attribute(.unique) var messageId: String
    var conversation: String
    var fromNodeId: String
    var fromName: String?
    var text: String
    var timestamp: Date
    var outgoing: Bool
    var statusRaw: String
    var verified: Bool

    var status: MessageStatus {
        get { MessageStatus(rawValue: statusRaw) ?? .received }
        set { statusRaw = newValue.rawValue }
    }

    init(messageId: String, conversation: String, fromNodeId: String, fromName: String?, text: String, timestamp: Date, outgoing: Bool, status: MessageStatus, verified: Bool) {
        self.messageId = messageId; self.conversation = conversation; self.fromNodeId = fromNodeId; self.fromName = fromName
        self.text = text; self.timestamp = timestamp; self.outgoing = outgoing; self.statusRaw = status.rawValue; self.verified = verified
    }
}

@Model
final class PeerRecord {
    @Attribute(.unique) var nodeId: String
    var publicKeyWire: Data
    var name: String
    var lastSeen: Date
    var hops: Int

    init(nodeId: String, publicKeyWire: Data, name: String, lastSeen: Date, hops: Int) {
        self.nodeId = nodeId; self.publicKeyWire = publicKeyWire; self.name = name; self.lastSeen = lastSeen; self.hops = hops
    }
}

/// Relay-store persistence so store-and-forward survives process death.
@Model
final class RelayPacketRecord {
    @Attribute(.unique) var messageId: String
    var bytes: Data
    var expiresAt: Date

    init(messageId: String, bytes: Data, expiresAt: Date) {
        self.messageId = messageId; self.bytes = bytes; self.expiresAt = expiresAt
    }
}

/// Received SOS beacon history (PROTOCOL.md §2.2), retained for ~90 days.
@Model
final class SosRecord {
    @Attribute(.unique) var messageId: String
    var fromNodeId: String
    var fromName: String?
    var text: String
    var latE7: Int32?
    var lngE7: Int32?
    var accuracyMeters: Int?
    var verified: Bool
    var timestamp: Date

    init(messageId: String, fromNodeId: String, fromName: String?, text: String, latE7: Int32?, lngE7: Int32?, accuracyMeters: Int?, verified: Bool, timestamp: Date) {
        self.messageId = messageId; self.fromNodeId = fromNodeId; self.fromName = fromName
        self.text = text; self.latE7 = latE7; self.lngE7 = lngE7; self.accuracyMeters = accuracyMeters
        self.verified = verified; self.timestamp = timestamp
    }
}

enum Persistence {
    static let broadcastConversation = "broadcast"

    static func container() -> ModelContainer {
        let schema = Schema([MessageRecord.self, PeerRecord.self, RelayPacketRecord.self, SosRecord.self])
        do {
            return try ModelContainer(for: schema, configurations: [ModelConfiguration(schema: schema)])
        } catch {
            fatalError("Could not create SwiftData container: \(error)")
        }
    }
}
