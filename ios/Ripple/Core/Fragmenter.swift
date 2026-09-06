import Foundation

/// BLE frame fragmentation per PROTOCOL.md §5.2.
enum Fragmenter {
    static let header = 4

    static func fragment(_ packet: Data, frameSize: Int, streamId: UInt16) -> [Data] {
        let chunk = frameSize - header
        precondition(chunk >= 1, "frame too small")
        let total = max(1, (packet.count + chunk - 1) / chunk)
        precondition(total <= 255, "packet needs too many fragments")
        let p = Data(packet)
        return (0..<total).map { i in
            let start = i * chunk, end = min(p.count, start + chunk)
            var f = Data(capacity: header + end - start)
            f.append(UInt8(streamId >> 8)); f.append(UInt8(streamId & 0xff))
            f.append(UInt8(i)); f.append(UInt8(total))
            f.append(p.subdata(in: start..<end))
            return f
        }
    }
}

/// Reassembles frames from one link. Not thread-safe; owned by the link's queue.
final class Reassembler {
    private struct Stream { let total: Int; let startedAt: Date; var parts: [Data?]; var have = 0 }
    private var streams: [UInt16: Stream] = [:]
    private let ttl: TimeInterval
    private let now: () -> Date

    init(ttl: TimeInterval = MeshProtocol.reassemblyTTL, now: @escaping () -> Date = Date.init) {
        self.ttl = ttl; self.now = now
    }

    /// Returns the complete packet when the last fragment arrives.
    func push(_ frame: Data) -> Data? {
        let f = Data(frame)
        guard f.count >= Fragmenter.header else { return nil }
        let streamId = UInt16(f[0]) << 8 | UInt16(f[1])
        let index = Int(f[2]), total = Int(f[3])
        guard total > 0, index < total else { return nil }

        let t = now()
        streams = streams.filter { t.timeIntervalSince($0.value.startedAt) <= ttl }

        var s = streams[streamId]
        if s == nil || s!.total != total { s = Stream(total: total, startedAt: t, parts: Array(repeating: nil, count: total)) }
        if s!.parts[index] == nil { s!.parts[index] = f.subdata(in: Fragmenter.header..<f.count); s!.have += 1 }
        if s!.have < total { streams[streamId] = s; return nil }
        streams[streamId] = nil
        return s!.parts.reduce(into: Data()) { $0.append($1!) }
    }
}
