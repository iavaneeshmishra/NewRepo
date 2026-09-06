import Foundation

/// Common half of a BLE link: fragments outbound packets into frames and drains
/// them one at a time (waiting for the radio's "ready" signal between frames);
/// reassembles inbound frames into packets.
class BleLink: Link {
    let id: String
    var peerHex: String?
    /// Negotiated frame size. Updated by the subclass once the MTU is known.
    var frameSize: Int = 20

    private let reassembler = Reassembler()
    private let workQueue = DispatchQueue(label: "app.ripple.mesh.link")
    private var pendingFrames: [Data] = []
    private var inFlight = false
    private(set) var isClosed = false
    let onPacket: (BleLink, Data) -> Void
    let onClosed: (BleLink) -> Void

    init(id: String, onPacket: @escaping (BleLink, Data) -> Void, onClosed: @escaping (BleLink) -> Void) {
        self.id = id; self.onPacket = onPacket; self.onClosed = onClosed
    }

    func send(_ packetBytes: Data) {
        workQueue.async { [self] in
            guard !isClosed else { return }
            pendingFrames.append(contentsOf: Fragmenter.fragment(packetBytes, frameSize: frameSize, streamId: UInt16.random(in: 0...UInt16.max)))
            pump()
        }
    }

    /// Subclass calls this when the radio can accept another frame.
    func radioReady() { workQueue.async { [self] in inFlight = false; pump() } }

    /// Subclass calls this for every frame received from the radio.
    func onFrame(_ frame: Data) {
        workQueue.async { [self] in
            if let packet = reassembler.push(frame) { onPacket(self, packet) }
        }
    }

    private func pump() {
        guard !inFlight, !isClosed, !pendingFrames.isEmpty else { return }
        let frame = pendingFrames.removeFirst()
        inFlight = true
        // writeFrame returns false when the radio is busy; the frame goes back to the head
        // of the queue and we wait for radioReady().
        if !writeFrame(frame) { pendingFrames.insert(frame, at: 0) }
    }

    func close() {
        workQueue.async { [self] in
            guard !isClosed else { return }
            isClosed = true
            pendingFrames.removeAll()
            closeTransport()
            onClosed(self)
        }
    }

    /// Attempt to send one frame. Return true if accepted by the radio (a later
    /// radioReady() will release the next frame), false if the radio is busy.
    func writeFrame(_ frame: Data) -> Bool { fatalError("abstract") }
    func closeTransport() {}
}
