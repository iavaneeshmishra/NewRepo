import Foundation

/// An in-process "neighbourhood" of simulated peers, each running a real `MeshRouter`,
/// wired to the local router with latency-adding links. Lets the whole app be
/// exercised on one device or the simulator, with no radio.
///
///     you ── Asha ── Ravi
///
/// Asha replies to direct messages; Ravi posts to the public channel periodically and
/// "walks out of range" for 40 s every 2 minutes so store-and-forward can be observed.
final class Loopback {
    private static let tag = "loopback"
    private static let phrases = [
        "Anyone near the north gate?", "Water truck arrived at the school.",
        "Road to the bridge is flooded, avoid it.", "Charging point open at the temple hall.",
        "Signal's been down since morning here too.", "Medical tent is at the bus stand.",
    ]

    final class Sim: RouterListener {
        let name: String
        let autoReply: Bool
        private(set) var router: MeshRouter!
        weak var owner: Loopback?

        init(name: String, autoReply: Bool) {
            self.name = name; self.autoReply = autoReply
            router = MeshRouter(identity: .generate(), displayName: name, listener: nil)
            router.listener = self
        }
        func router(_ router: MeshRouter, peersDidChange peers: [Peer]) {}
        func router(_ router: MeshRouter, didReceiveAck messageId: Data, from: NodeId) {}
        func router(_ router: MeshRouter, didReceive m: InboundMessage) {
            guard autoReply, !m.isBroadcast, let owner else { return }
            owner.schedule(.milliseconds(Int.random(in: 800...2000))) { [weak self] in
                guard let self else { return }
                do { try self.router.sendDirect(to: m.from, text: "Got it: “\(m.text.prefix(40))” — \(self.name)") }
                catch { owner.log.w(Loopback.tag, "\(self.name) reply failed: \(error)") }
            }
        }
    }

    private final class Pipe: Link {
        let id: String
        var peerHex: String?
        unowned let from: MeshRouter, to: MeshRouter
        unowned let owner: Loopback
        let latency: DispatchTimeInterval
        var twin: Pipe!
        var open = true
        init(id: String, from: MeshRouter, to: MeshRouter, owner: Loopback, latency: DispatchTimeInterval) {
            self.id = id; self.from = from; self.to = to; self.owner = owner; self.latency = latency
        }
        func send(_ packetBytes: Data) {
            guard open else { return }
            owner.schedule(latency) { [self] in if open, twin.open { to.onReceive(twin, packetBytes) } }
        }
        func close() {
            guard open else { return }
            open = false; twin.open = false
            from.onLinkClosed(self); to.onLinkClosed(twin)
        }
    }

    let asha = Sim(name: "Asha (simulated)", autoReply: true)
    let ravi = Sim(name: "Ravi (simulated)", autoReply: false)
    private let local: MeshRouter
    fileprivate let log: EventLog
    private let queue = DispatchQueue(label: "app.ripple.mesh.loopback")
    private var youAsha: Pipe?
    private var ashaRavi: Pipe?
    private var timers: [DispatchSourceTimer] = []
    private(set) var isRunning = false

    init(local: MeshRouter, log: EventLog = .global) {
        self.local = local; self.log = log
        asha.owner = self; ravi.owner = self
    }

    func start() {
        queue.sync {
            guard !isRunning else { return }
            isRunning = true
            log.i(Self.tag, "starting simulated neighbourhood (you — Asha — Ravi)")
            youAsha = connect(local, asha.router)
            ashaRavi = connect(asha.router, ravi.router)

            let chat = DispatchSource.makeTimerSource(queue: queue)
            chat.schedule(deadline: .now() + 5, repeating: 45)
            chat.setEventHandler { [weak self] in _ = try? self?.ravi.router.sendBroadcast(Self.phrases.randomElement()!) }
            chat.resume(); timers.append(chat)

            let wander = DispatchSource.makeTimerSource(queue: queue)
            wander.schedule(deadline: .now() + 120, repeating: 120)
            wander.setEventHandler { [weak self] in
                guard let self else { return }
                log.i(Self.tag, "Ravi walked out of range")
                ashaRavi?.close(); ashaRavi = nil
                schedule(.seconds(40)) { [weak self] in
                    guard let self, self.isRunning else { return }
                    self.log.i(Self.tag, "Ravi is back in range")
                    self.ashaRavi = self.connect(self.asha.router, self.ravi.router)
                }
            }
            wander.resume(); timers.append(wander)
        }
    }

    func stop() {
        queue.sync {
            guard isRunning else { return }
            isRunning = false
            timers.forEach { $0.cancel() }; timers.removeAll()
            youAsha?.close(); ashaRavi?.close()
            youAsha = nil; ashaRavi = nil
            log.i(Self.tag, "stopped simulated neighbourhood")
        }
    }

    private func connect(_ a: MeshRouter, _ b: MeshRouter) -> Pipe {
        let ab = Pipe(id: "sim:\(a.displayName)->\(b.displayName)", from: a, to: b, owner: self, latency: .milliseconds(60))
        let ba = Pipe(id: "sim:\(b.displayName)->\(a.displayName)", from: b, to: a, owner: self, latency: .milliseconds(60))
        ab.twin = ba; ba.twin = ab
        a.onLinkReady(ab); b.onLinkReady(ba)
        return ab
    }

    fileprivate func schedule(_ delay: DispatchTimeInterval, _ block: @escaping () -> Void) {
        queue.asyncAfter(deadline: .now() + delay, execute: block)
    }
}
