package app.ripple.mesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LoopbackTest {
    private class Collector : RouterListener {
        val messages = java.util.concurrent.CopyOnWriteArrayList<InboundMessage>()
        val peersSeen = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val acks = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        val gotMessage = CountDownLatch(1)
        val gotAck = CountDownLatch(1)
        override fun onMessage(message: InboundMessage) { messages.add(message); gotMessage.countDown() }
        override fun onAck(messageId: ByteArray, from: NodeId) { acks.add(messageId); gotAck.countDown() }
        override fun onPeersChanged(peers: List<Peer>) { peersSeen.add(peers.size) }
    }

    @Test fun `simulated neighbourhood announces two peers and Asha replies to a direct message`() {
        val me = Collector()
        val router = MeshRouter(Identity.generate(), "Me", me)
        val loop = Loopback(router, EventLog(50))
        loop.start()
        try {
            // Announces propagate: Asha at 1 hop, Ravi at 2.
            val deadline = System.currentTimeMillis() + 5_000
            while (router.peers().size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertEquals(2, router.peers().size)
            assertEquals(1, router.peer(loop.asha.router.selfId)!!.hops)
            assertEquals(2, router.peer(loop.ravi.router.selfId)!!.hops)

            val id = router.sendDirect(loop.asha.router.selfId, "hello Asha")
            assertTrue("ack", me.gotAck.await(5, TimeUnit.SECONDS))
            assertTrue(me.acks.any { it.contentEquals(id) })
            assertTrue("reply", me.gotMessage.await(5, TimeUnit.SECONDS))
            assertTrue(me.messages.first().text.contains("hello Asha"))
            assertTrue(me.messages.first().verified)
        } finally { loop.stop() }
        assertEquals(0, router.linkCount())
    }

    @Test fun `event log is bounded and exports plain text`() {
        val log = EventLog(capacity = 3)
        repeat(5) { log.i("t", "m$it") }
        assertEquals(listOf("m2", "m3", "m4"), log.snapshot().map { it.message })
        val text = log.export("hdr")
        assertTrue(text.startsWith("hdr"))
        assertTrue(text.contains("INFO  t          m4"))
    }
}
