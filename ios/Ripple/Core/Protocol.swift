import Foundation
import CryptoKit

/// Constants and wire-format codec for Ripple Mesh Protocol v1 (see /PROTOCOL.md).
enum MeshProtocol {
    static let version: UInt8 = 1
    static let maxTTL: UInt8 = 7
    static let maxPayload = 4096
    static let headerSize = 46
    static let signatureSize = 64
    static let maxPacket = headerSize + maxPayload + signatureSize
    static let maxNameBytes = 64
    static let nodeIdSize = 8
    static let messageIdSize = 16
    static let hkdfInfo = Data("ripple/v1/msg".utf8)

    static let seenTTL: TimeInterval = 24 * 3600
    // Store-and-forward guarantee: relayed packets are held for 72 h.
    static let relayTTL: TimeInterval = 72 * 3600
    static let reassemblyTTL: TimeInterval = 10

    // SOS beacon payload (PROTOCOL.md §2.2).
    static let maxSosText = 128
    static let sosFlagHasLocation: UInt8 = 0x01

    static let serviceUUID = "7E2C4B10-4B7D-4E3A-9C1F-8A2E5D6F1A01"
    static let rxUUID = "7E2C4B10-4B7D-4E3A-9C1F-8A2E5D6F1A02"
    static let txUUID = "7E2C4B10-4B7D-4E3A-9C1F-8A2E5D6F1A03"
}

enum PacketType: UInt8 {
    case announce = 1, message = 2, ack = 3, sos = 4
}

struct Flags {
    static let encrypted: UInt8 = 0x01
}

/// Battery-dependent relay policy (PROTOCOL.md §7).
enum BatteryProfile: Int, CaseIterable {
    case performance = 0
    case balanced = 1
    case powerSaver = 2

    /// POWER_SAVER conserves battery by not relaying ordinary chat; SOS/ANNOUNCE always relay.
    var relaysOrdinary: Bool { self != .powerSaver }
}

/// A decoded SOS beacon payload (PROTOCOL.md §2.2). `location` is nil unless GPS was opted in.
struct SosLocation: Equatable {
    let latE7: Int32
    let lngE7: Int32
    let accuracyMeters: Int
}

struct SosPayload {
    let flags: UInt8
    let text: String
    let location: SosLocation?
}

enum SosError: Error { case textTooLong, malformed }

enum SosCodec {
    static func encode(text: String, location: SosLocation?) throws -> Data {
        let textBytes = Data(text.utf8)
        guard textBytes.count <= MeshProtocol.maxSosText else { throw SosError.textTooLong }
        var d = Data()
        d.append(location == nil ? 0 : MeshProtocol.sosFlagHasLocation)
        d.append(UInt8(textBytes.count))
        d.append(textBytes)
        if let loc = location {
            d.append(int32(loc.latE7)); d.append(int32(loc.lngE7))
            d.append(uint16(loc.accuracyMeters))
        }
        return d
    }

    static func decode(_ payload: Data) throws -> SosPayload {
        let p = Data(payload)
        guard p.count >= 2 else { throw SosError.malformed }
        let flags = p[0]
        let textLen = Int(p[1])
        guard textLen <= MeshProtocol.maxSosText, p.count >= 2 + textLen else { throw SosError.malformed }
        guard let text = String(data: p.subdata(in: 2..<(2 + textLen)), encoding: .utf8) else { throw SosError.malformed }
        let hasLocation = flags & MeshProtocol.sosFlagHasLocation != 0
        if hasLocation {
            guard p.count == 2 + textLen + 10 else { throw SosError.malformed }
            let base = 2 + textLen
            let lat = readInt32(p, at: base)
            let lng = readInt32(p, at: base + 4)
            let acc = Int(readUInt16(p, at: base + 8))
            return SosPayload(flags: flags, text: text, location: SosLocation(latE7: lat, lngE7: lng, accuracyMeters: acc))
        }
        guard p.count == 2 + textLen else { throw SosError.malformed }
        return SosPayload(flags: flags, text: text, location: nil)
    }

    private static func int32(_ v: Int32) -> Data {
        var x = v.bigEndian
        return withUnsafeBytes(of: &x) { Data($0) }
    }
    private static func uint16(_ v: Int) -> Data {
        var x = UInt16(v & 0xffff).bigEndian
        return withUnsafeBytes(of: &x) { Data($0) }
    }
    private static func readInt32(_ d: Data, at o: Int) -> Int32 {
        var v: UInt32 = 0
        for i in 0..<4 { v = (v << 8) | UInt32(d[o + i]) }
        return Int32(bitPattern: v)
    }
    private static func readUInt16(_ d: Data, at o: Int) -> UInt16 {
        (UInt16(d[o]) << 8) | UInt16(d[o + 1])
    }
}

/// Sliding-window event counter (see PROTOCOL.md §7). One instance per message source.
final class RateLimiter {
    private let max: Int
    private let windowMs: TimeInterval
    private let now: () -> Date
    private var stamps: [Date] = []

    init(max: Int, windowMs: TimeInterval, now: @escaping () -> Date = Date.init) {
        precondition(max > 0 && windowMs > 0)
        self.max = max; self.windowMs = windowMs; self.now = now
    }

    /// Returns true if an event is within budget and was recorded.
    func allow() -> Bool {
        let t = now()
        let cutoff = t.addingTimeInterval(-windowMs)
        stamps.removeAll { $0 <= cutoff }
        guard stamps.count < max else { return false }
        stamps.append(t)
        return true
    }
}

enum ProtocolError: Error, Equatable {
    case tooShort, unsupportedVersion(UInt8), unknownType(UInt8), payloadTooLarge, lengthMismatch
    case badPublicKey, announceMalformed, eciesMalformed, nameTooLong
}

/// 8-byte node identifier.
struct NodeId: Hashable, Comparable, CustomStringConvertible {
    let bytes: Data

    init(_ bytes: Data) {
        precondition(bytes.count == MeshProtocol.nodeIdSize, "NodeId must be 8 bytes")
        self.bytes = bytes
    }

    init?(hex: String) {
        guard let d = Data(hex: hex), d.count == MeshProtocol.nodeIdSize else { return nil }
        self.bytes = d
    }

    static let broadcast = NodeId(Data(repeating: 0, count: MeshProtocol.nodeIdSize))

    static func fromPublicKey(_ wire: Data) -> NodeId {
        NodeId(Data(Data(SHA256.hash(data: wire)).prefix(MeshProtocol.nodeIdSize)))
    }

    var hex: String { bytes.hex }
    var isBroadcast: Bool { bytes.allSatisfy { $0 == 0 } }
    /// `xxxx-xxxx-xxxx-xxxx`
    var display: String {
        let h = hex
        return stride(from: 0, to: h.count, by: 4).map { i in
            let s = h.index(h.startIndex, offsetBy: i); let e = h.index(s, offsetBy: 4)
            return String(h[s..<e])
        }.joined(separator: "-")
    }
    var short: String { String(hex.suffix(4)) }
    var description: String { display }

    static func < (a: NodeId, b: NodeId) -> Bool { a.hex < b.hex }
}

struct Packet: Equatable {
    var type: PacketType
    var flags: UInt8
    var ttl: UInt8
    var messageId: Data
    var source: NodeId
    var destination: NodeId
    /// Unix time in milliseconds.
    var timestamp: UInt64
    var payload: Data
    var signature: Data

    var isEncrypted: Bool { flags & Flags.encrypted != 0 }
    var messageIdHex: String { messageId.hex }

    func withTTL(_ t: UInt8) -> Packet { var p = self; p.ttl = t; return p }

    /// Header + payload, without signature.
    func encodeUnsigned() -> Data {
        var d = Data(capacity: MeshProtocol.headerSize + payload.count)
        d.append(MeshProtocol.version)
        d.append(type.rawValue)
        d.append(flags)
        d.append(ttl)
        d.append(messageId)
        d.append(source.bytes)
        d.append(destination.bytes)
        d.append(contentsOf: withUnsafeBytes(of: timestamp.bigEndian) { Array($0) })
        d.append(contentsOf: withUnsafeBytes(of: UInt16(payload.count).bigEndian) { Array($0) })
        d.append(payload)
        return d
    }

    func encode() -> Data { encodeUnsigned() + signature }

    /// SHA-256 over the unsigned bytes with ttl zeroed (what ECDSA-SHA256 signs).
    func signingDigest() -> Data { Packet.signingDigest(encodeUnsigned()) }

    static func signingDigest(_ unsigned: Data) -> Data {
        var copy = unsigned; copy[copy.startIndex + 3] = 0
        return Data(SHA256.hash(data: copy))
    }

    static func decode(_ bytes: Data) throws -> Packet {
        let b = Data(bytes) // rebase indices at 0
        guard b.count >= MeshProtocol.headerSize + MeshProtocol.signatureSize else { throw ProtocolError.tooShort }
        guard b[0] == MeshProtocol.version else { throw ProtocolError.unsupportedVersion(b[0]) }
        guard let type = PacketType(rawValue: b[1]) else { throw ProtocolError.unknownType(b[1]) }
        let payloadLength = Int(b[44]) << 8 | Int(b[45])
        guard payloadLength <= MeshProtocol.maxPayload else { throw ProtocolError.payloadTooLarge }
        guard b.count == MeshProtocol.headerSize + payloadLength + MeshProtocol.signatureSize else { throw ProtocolError.lengthMismatch }
        var ts: UInt64 = 0
        for i in 36..<44 { ts = ts << 8 | UInt64(b[i]) }
        return Packet(
            type: type, flags: b[2], ttl: b[3],
            messageId: b.subdata(in: 4..<20),
            source: NodeId(b.subdata(in: 20..<28)),
            destination: NodeId(b.subdata(in: 28..<36)),
            timestamp: ts,
            payload: b.subdata(in: MeshProtocol.headerSize..<(MeshProtocol.headerSize + payloadLength)),
            signature: b.subdata(in: (MeshProtocol.headerSize + payloadLength)..<b.count)
        )
    }
}

struct Announce {
    let publicKeyWire: Data
    let name: String

    func encode() throws -> Data {
        let nameBytes = Data(name.utf8)
        guard nameBytes.count <= MeshProtocol.maxNameBytes else { throw ProtocolError.nameTooLong }
        return publicKeyWire + Data([UInt8(nameBytes.count)]) + nameBytes
    }

    static func decode(_ payload: Data) throws -> Announce {
        let p = Data(payload)
        guard p.count >= 66 else { throw ProtocolError.announceMalformed }
        let len = Int(p[65])
        guard p.count == 66 + len else { throw ProtocolError.announceMalformed }
        guard let name = String(data: p.subdata(in: 66..<p.count), encoding: .utf8) else { throw ProtocolError.announceMalformed }
        return Announce(publicKeyWire: p.subdata(in: 0..<65), name: name)
    }
}

// MARK: - Hex helpers

extension Data {
    var hex: String { map { String(format: "%02x", $0) }.joined() }

    init?(hex: String) {
        guard hex.count % 2 == 0 else { return nil }
        var d = Data(capacity: hex.count / 2)
        var idx = hex.startIndex
        while idx < hex.endIndex {
            let next = hex.index(idx, offsetBy: 2)
            guard let b = UInt8(hex[idx..<next], radix: 16) else { return nil }
            d.append(b); idx = next
        }
        self = d
    }
}
