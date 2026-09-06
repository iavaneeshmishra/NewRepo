import XCTest
@testable import Ripple

/// Simulated mesh with in-memory links. Mirrors tools/protocol/test.js and the
/// Kotlin MeshRouterTest so all three routers are held to the same behaviour.
final class MeshRouterTests: XCTestCase {
    final class Net {
        var queue: [(FakeLink, Data)] = []
        func settle() {
            var guardCount = 100_000
            while !queue.isEmpty && guardCount > 0 {
                guardCount -= 1
                let (link, bytes) = queue.removeFirst()
                guard link.open, link.twin.open else { continue }
                link.remote.router.onReceive(link.twin, bytes)
            }
            XCTAssertGreaterThan(guardCount, 0, "network did not settle")
        }
    }

    final class FakeLink: Link {
        let id: String
        var peerHex: String?
        unowned let owner: Node, remote: Node
        unowned let net: Net
        var twin: FakeLink!
        var open = true
        init(owner: Node, remote: Node, net: Net) { self.owner = owner; self.remote = remote; self.net = net; id = "\(owner.name)->\(remote.name)" }
        func send(_ packetBytes: Data) { if open { net.queue.append((self, packetBytes)) } }
        func close() { MeshRouterTests.unlink(owner, remote) }
    }

    final class Node: RouterListener {
        let name: String
        var inbox: [InboundMessage] = []
        var acks: [Data] = []
        var links: [FakeLink] = []
        var router: MeshRouter!
        init(_ name: String) {
            self.name = name
            router = MeshRouter(identity: .generate(), displayName: name, listener: nil)
            router.listener = self
        }
        func router(_ router: MeshRouter, didReceive message: InboundMessage) { inbox.append(message) }
        func router(_ router: MeshRouter, didReceiveAck messageId: Data, from: NodeId) { acks.append(messageId) }
        func router(_ router: MeshRouter, peersDidChange peers: [Peer]) {}
    }

    static func link(_ a: Node, _ b: Node, net: Net) {
        let la = FakeLink(owner: a, remote: b, net: net), lb = FakeLink(owner: b, remote: a, net: net)
        la.twin = lb; lb.twin = la
        a.links.append(la); b.links.append(lb)
        a.router.onLinkReady(la); b.router.onLinkReady(lb)
    }

    static func unlink(_ a: Node, _ b: Node) {
        for (n, other) in [(a, b), (b, a)] {
            for l in n.links where l.remote === other { l.open = false; n.router.onLinkClosed(l) }
            n.links.removeAll { $0.remote === other }
        }
    }

    func testLineOfFour() throws {
        let net = Net()
        let a = Node("A"), b = Node("B"), c = Node("C"), d = Node("D")
        Self.link(a, b, net: net); Self.link(b, c, net: net); Self.link(c, d, net: net); net.settle()

        for n in [a, b, c, d] { XCTAssertEqual(n.router.allPeers().count, 3, "\(n.name) peers") }

        try a.router.sendBroadcast("hello all"); net.settle()
        for n in [b, c, d] { XCTAssertEqual(n.inbox.last?.text, "hello all") }

        let id = try a.router.sendDirect(to: d.router.selfId, text: "only for D"); net.settle()
        XCTAssertEqual(d.inbox.last?.text, "only for D")
        XCTAssertTrue(d.inbox.last!.verified)
        XCTAssertFalse(b.inbox.contains { $0.text == "only for D" })
        XCTAssertFalse(c.inbox.contains { $0.text == "only for D" })
        XCTAssertTrue(a.acks.contains(id))
        XCTAssertEqual(d.router.peer(a.router.selfId)?.hops, 3)
    }

    func testStoreAndForward() throws {
        let net = Net()
        let a = Node("A"), b = Node("B"), c = Node("C")
        Self.link(a, b, net: net); Self.link(b, c, net: net); net.settle()
        Self.unlink(b, c)
        try a.router.sendDirect(to: c.router.selfId, text: "catch up later"); net.settle()
        XCTAssertTrue(c.inbox.isEmpty)
        Self.link(b, c, net: net); net.settle()
        XCTAssertEqual(c.inbox.last?.text, "catch up later")
    }

    func testTriangleDeliversOnce() throws {
        let net = Net()
        let a = Node("A"), b = Node("B"), c = Node("C")
        Self.link(a, b, net: net); Self.link(b, c, net: net); Self.link(a, c, net: net); net.settle()
        try a.router.sendBroadcast("once"); net.settle()
        XCTAssertEqual(b.inbox.filter { $0.text == "once" }.count, 1)
        XCTAssertEqual(c.inbox.filter { $0.text == "once" }.count, 1)
    }

    func testTTLBound() throws {
        let net = Net()
        let nodes = (0..<9).map { Node("\($0)") }
        for i in 0..<8 { Self.link(nodes[i], nodes[i + 1], net: net) }
        net.settle()
        try nodes[0].router.sendBroadcast("far"); net.settle()
        XCTAssertEqual(nodes[7].inbox.last?.text, "far")
        XCTAssertNil(nodes[8].inbox.last)
    }

    func testForgedPacketDropped() throws {
        let net = Net()
        let a = Node("A"), b = Node("B")
        Self.link(a, b, net: net); net.settle()
        let mallory = Identity.generate()
        var forged = try PacketFactory.broadcastText(mallory, "pwned")
        forged.source = a.router.selfId
        forged.signature = try mallory.sign(forged.encodeUnsigned())
        b.router.onReceive(b.links[0], forged.encode()); net.settle()
        XCTAssertFalse(b.inbox.contains { $0.text == "pwned" })
    }
}
