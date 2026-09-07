import Foundation

/// Bounded, in-memory ring of diagnostic events. Written by the BLE and router
/// layers, read by the Diagnostics screen and exported for bug reports.
/// Thread-safe. Never includes message plaintext.
final class EventLog {
    enum Level: String { case debug = "DEBUG", info = "INFO", warn = "WARN", error = "ERROR" }

    struct Event: Identifiable {
        let id = UUID()
        let at: Date
        let level: Level
        let tag: String
        let message: String
    }

    static let global = EventLog()

    private let capacity: Int
    private let now: () -> Date
    private var ring: [Event] = []
    private let lock = NSLock()
    private var listeners: [UUID: (Event) -> Void] = [:]

    init(capacity: Int = 500, now: @escaping () -> Date = Date.init) {
        self.capacity = capacity; self.now = now
    }

    func log(_ level: Level, _ tag: String, _ message: String) {
        let e = Event(at: now(), level: level, tag: tag, message: message)
        lock.lock()
        if ring.count >= capacity { ring.removeFirst() }
        ring.append(e)
        let ls = Array(listeners.values)
        lock.unlock()
        ls.forEach { $0(e) }
    }

    func d(_ tag: String, _ m: String) { log(.debug, tag, m) }
    func i(_ tag: String, _ m: String) { log(.info, tag, m) }
    func w(_ tag: String, _ m: String) { log(.warn, tag, m) }
    func e(_ tag: String, _ m: String) { log(.error, tag, m) }

    func snapshot() -> [Event] { lock.lock(); defer { lock.unlock() }; return ring }
    func clear() { lock.lock(); ring.removeAll(); lock.unlock() }

    @discardableResult
    func addListener(_ l: @escaping (Event) -> Void) -> UUID {
        let id = UUID(); lock.lock(); listeners[id] = l; lock.unlock(); return id
    }
    func removeListener(_ id: UUID) { lock.lock(); listeners[id] = nil; lock.unlock() }

    /// Plain-text export suitable for pasting into a bug report.
    func export(header: String = "") -> String {
        var out = header.isEmpty ? "" : header + "\n\n"
        for e in snapshot() {
            out += "\(Self.formatTime(e.at)) \(e.level.rawValue.padding(toLength: 5, withPad: " ", startingAt: 0)) \(e.tag.padding(toLength: 10, withPad: " ", startingAt: 0)) \(e.message)\n"
        }
        return out
    }

    private static let timeFormatter: DateFormatter = {
        let f = DateFormatter(); f.dateFormat = "HH:mm:ss.SSS"; return f
    }()
    static func formatTime(_ d: Date) -> String { timeFormatter.string(from: d) }
}
