import XCTest
@testable import Ripple

final class LoopbackTests: XCTestCase {
    final class Collector: RouterListener {
        var messages: [InboundMessage] = []
        var acks: [Data] = []
        let gotMessage = XCTestExpectation(description: "reply")
        let gotAck = XCTestExpectation(description: "ack")
        let lock = NSLock()
        func router(_ router: MeshRouter, didReceive m: InboundMessage) { lock.lock(); messages.append(m); lock.unlock(); gotMessage.fulfill() }
        func router(_ router: MeshRouter, didReceiveAck messageId: Data, from: NodeId) { lock.lock(); acks.append(messageId); lock.unlock(); gotAck.fulfill() }
        func router(_ router: MeshRouter, peersDidChange peers: [Peer]) {}
    }

    func testSimulatedNeighbourhoodAnnouncesAndReplies() throws {
        let me = Collector()
        let router = MeshRouter(identity: .generate(), displayName: "Me", listener: me)
        let loop = Loopback(local: router, log: EventLog(capacity: 50))
        loop.start()
        defer { loop.stop() }

        let deadline = Date().addingTimeInterval(5)
        while router.allPeers().count < 2 && Date() < deadline { Thread.sleep(forTimeInterval: 0.02) }
        XCTAssertEqual(router.allPeers().count, 2)
        XCTAssertEqual(router.peer(loop.asha.router.selfId)?.hops, 1)
        XCTAssertEqual(router.peer(loop.ravi.router.selfId)?.hops, 2)

        let id = try router.sendDirect(to: loop.asha.router.selfId, text: "hello Asha")
        wait(for: [me.gotAck, me.gotMessage], timeout: 5)
        XCTAssertTrue(me.acks.contains(id))
        XCTAssertTrue(me.messages.first?.text.contains("hello Asha") ?? false)
        XCTAssertTrue(me.messages.first?.verified ?? false)
    }

    func testEventLogIsBoundedAndExports() {
        let log = EventLog(capacity: 3)
        for i in 0..<5 { log.i("t", "m\(i)") }
        XCTAssertEqual(log.snapshot().map(\.message), ["m2", "m3", "m4"])
        let text = log.export(header: "hdr")
        XCTAssertTrue(text.hasPrefix("hdr"))
        XCTAssertTrue(text.contains("INFO  t          m4"))
    }
}
