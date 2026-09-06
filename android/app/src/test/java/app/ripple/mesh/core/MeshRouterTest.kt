package app.ripple.mesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

/**
 * Simulated mesh with in-memory links. Mirrors tools/protocol/test.js so the two
 * router implementations are held to the same behaviour.
 */
class MeshRouterTest {
    private class Net {
        val queue = ArrayDeque<Pair<FakeLink, ByteArray>>()
        fun settle() {
            var guard = 100_000
            while (queue.isNotEmpty() && guard-- > 0) {
                val (link, bytes) = queue.poll()
                if (!link.open || !link.twin.open) continue
                link.remote.router.onReceive(link.twin, bytes)
            }
            check(guard > 0) { "network did not settle" }
        }
    }

    private class FakeLink(val owner: Node, val remote: Node, val net: Net) : Link {
        override val id = "${owner.name}->${remote.name}"
        override var peerHex: String? = null
        lateinit var twin: FakeLink
        var open = true
        override fun send(packetBytes: ByteArray) { if (open) net.queue.add(this to packetBytes) }
        override fun close() { unlink(owner, remote) }
    }

    private class Node(val name: String, val net: Net) : RouterListener {
        val inbox = ArrayList<InboundMessage>()
        val acks = ArrayList<ByteArray>()
        val links = ArrayList<FakeLink>()
        val router = MeshRouter(Identity.generate(), name, this)
        override fun onMessage(message: InboundMessage) { inbox.add(message) }
        override fun onAck(messageId: ByteArray, from: NodeId) { acks.add(messageId) }
        override fun onPeersChanged(peers: List<Peer>) {}
    }

    private companion object {
        fun link(a: Node, b: Node) {
            val la = FakeLink(a, b, a.net); val lb = FakeLink(b, a, a.net)
            la.twin = lb; lb.twin = la
            a.links += la; b.links += lb
            a.router.onLinkReady(la); b.router.onLinkReady(lb)
        }
        fun unlink(a: Node, b: Node) {
            for ((n, other) in listOf(a to b, b to a)) {
                n.links.filter { it.remote === other }.forEach { it.open = false; n.links.remove(it); n.router.onLinkClosed(it) }
            }
        }
    }

    @Test fun `line of four - broadcast floods, direct is e2e and acked`() {
        val net = Net()
        val (a, b, c, d) = listOf("A", "B", "C", "D").map { Node(it, net) }
        link(a, b); link(b, c); link(c, d); net.settle()

        for (n in listOf(a, b, c, d)) assertEquals("${n.name} peers", 3, n.router.peers().size)

        a.router.sendBroadcast("hello all"); net.settle()
        for (n in listOf(b, c, d)) assertEquals("hello all", n.inbox.last().text)

        val id = a.router.sendDirect(d.router.selfId, "only for D"); net.settle()
        assertEquals("only for D", d.inbox.last().text)
        assertTrue(d.inbox.last().verified)
        assertFalse(b.inbox.any { it.text == "only for D" })
        assertFalse(c.inbox.any { it.text == "only for D" })
        assertTrue(a.acks.any { it.contentEquals(id) })
        assertEquals(3, d.router.peer(a.router.selfId)!!.hops)
    }

    @Test fun `store and forward delivers after reconnect`() {
        val net = Net()
        val a = Node("A", net); val b = Node("B", net); val c = Node("C", net)
        link(a, b); link(b, c); net.settle()
        unlink(b, c)
        a.router.sendDirect(c.router.selfId, "catch up later"); net.settle()
        assertTrue(c.inbox.isEmpty())
        link(b, c); net.settle()
        assertEquals("catch up later", c.inbox.last().text)
    }

    @Test fun `triangle delivers exactly once`() {
        val net = Net()
        val a = Node("A", net); val b = Node("B", net); val c = Node("C", net)
        link(a, b); link(b, c); link(a, c); net.settle()
        a.router.sendBroadcast("once"); net.settle()
        assertEquals(1, b.inbox.count { it.text == "once" })
        assertEquals(1, c.inbox.count { it.text == "once" })
    }

    @Test fun `ttl bounds the flood at seven hops`() {
        val net = Net()
        val nodes = (0..8).map { Node("$it", net) }
        for (i in 0 until 8) link(nodes[i], nodes[i + 1])
        net.settle()
        nodes[0].router.sendBroadcast("far"); net.settle()
        assertEquals("far", nodes[7].inbox.lastOrNull()?.text)
        assertNull(nodes[8].inbox.lastOrNull())
    }

    @Test fun `forged packet from a known peer is dropped`() {
        val net = Net()
        val a = Node("A", net); val b = Node("B", net)
        link(a, b); net.settle()
        // Mallory forges a broadcast claiming to be A.
        val mallory = Identity.generate()
        val forged = PacketFactory.broadcastText(mallory, "pwned").let {
            val p = it.copy(source = a.router.selfId)
            p.copy(signature = mallory.sign(p.encodeUnsigned()))
        }
        b.router.onReceive(b.links.first(), forged.encode()); net.settle()
        assertTrue(b.inbox.none { it.text == "pwned" })
    }

    @Test fun `relay store survives export and import`() {
        val net = Net()
        val a = Node("A", net); val b = Node("B", net); val c = Node("C", net)
        link(a, b); link(b, c); net.settle()
        unlink(b, c)
        a.router.sendDirect(c.router.selfId, "persisted"); net.settle()

        // B "restarts": new router with same identity, imports persisted state.
        val snapshot = b.router.relayStoreSnapshot().map { Packet.decode(it.encode()) }
        val peersSnapshot = b.router.peers()
        val b2 = Node("B2", net)
        val restored = MeshRouter(b.router.identity, "B", b2)
        restored.importPeers(peersSnapshot)
        restored.importRelayStore(snapshot)
        val lb = FakeLink(b2, c, net); val lc = FakeLink(c, b2, net); lb.twin = lc; lc.twin = lb
        b2.links += lb; c.links += lc
        restored.onLinkReady(lb); c.router.onLinkReady(lc)
        // Route inbound for b2 through the restored router.
        while (net.queue.isNotEmpty()) {
            val (link, bytes) = net.queue.poll()
            if (link.remote === b2) restored.onReceive(link.twin, bytes) else link.remote.router.onReceive(link.twin, bytes)
        }
        assertEquals("persisted", c.inbox.last().text)
    }
}
