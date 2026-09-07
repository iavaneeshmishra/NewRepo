package app.ripple.mesh.core

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * An in-process "neighbourhood" of simulated peers, each running a real [MeshRouter],
 * wired to the local router with [Link]s that add latency. Lets the whole app be
 * exercised on one device or an emulator, with no radio.
 *
 * Topology (one-hop neighbour + a peer that is only reachable through it):
 *
 *     you ── Asha ── Ravi
 *
 * Asha replies to direct messages; Ravi posts to the public channel periodically.
 * Ravi also "walks out of range" for a while every couple of minutes so store-and-
 * forward can be observed.
 */
class Loopback(private val local: MeshRouter, private val log: EventLog = EventLog.global) {
    companion object {
        private const val TAG = "loopback"
        private val phrases = listOf(
            "Anyone near the north gate?", "Water truck arrived at the school.",
            "Road to the bridge is flooded, avoid it.", "Charging point open at the temple hall.",
            "Signal's been down since morning here too.", "Medical tent is at the bus stand.",
        )
    }

    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "loopback").apply { isDaemon = true } }
    private val running = AtomicBoolean(false)
    private val tasks = mutableListOf<ScheduledFuture<*>>()

    /** A simulated peer: its own identity + router. */
    inner class Sim(val name: String, private val autoReply: Boolean) : RouterListener {
        val router = MeshRouter(Identity.generate(), name, this)
        override fun onPeersChanged(peers: List<Peer>) {}
        override fun onAck(messageId: ByteArray, from: NodeId) {}
        override fun onMessage(message: InboundMessage) {
            if (!autoReply || message.isBroadcast) return
            schedule(800 + Random.nextLong(1200)) {
                runCatching { router.sendDirect(message.from, "Got it: “${message.text.take(40)}” — $name (simulated)") }
                    .onFailure { log.w(TAG, "$name reply failed: ${it.message}") }
            }
        }
    }

    /** A latency-adding pipe between two routers. */
    private inner class Pipe(override val id: String, private val from: MeshRouter, private val to: MeshRouter, private val latencyMs: Long) : Link {
        override var peerHex: String? = null
        lateinit var twin: Pipe
        @Volatile var open = true
        override fun send(packetBytes: ByteArray) {
            if (!open) return
            schedule(latencyMs) { if (open && twin.open) to.onReceive(twin, packetBytes) }
        }
        override fun close() { open = false; twin.open = false; from.onLinkClosed(this); to.onLinkClosed(twin) }
    }

    val asha = Sim("Asha (simulated)", autoReply = true)
    val ravi = Sim("Ravi (simulated)", autoReply = false)
    private var youAsha: Pipe? = null
    private var ashaRavi: Pipe? = null

    val isRunning: Boolean get() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        log.i(TAG, "starting simulated neighbourhood (you — Asha — Ravi)")
        youAsha = connect(local, asha.router, 60)
        ashaRavi = connect(asha.router, ravi.router, 60)

        // Ravi chats on the public channel.
        tasks += exec.scheduleWithFixedDelay({
            runCatching { ravi.router.sendBroadcast(phrases.random()) }
        }, 5, 45, TimeUnit.SECONDS)

        // Ravi wanders out of range for 40 s every 2 minutes.
        tasks += exec.scheduleWithFixedDelay({
            log.i(TAG, "Ravi walked out of range")
            ashaRavi?.close(); ashaRavi = null
            schedule(40_000) {
                if (running.get()) { log.i(TAG, "Ravi is back in range"); ashaRavi = connect(asha.router, ravi.router, 60) }
            }
        }, 120, 120, TimeUnit.SECONDS)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        tasks.forEach { it.cancel(false) }; tasks.clear()
        youAsha?.close(); ashaRavi?.close()
        youAsha = null; ashaRavi = null
        log.i(TAG, "stopped simulated neighbourhood")
    }

    private fun connect(a: MeshRouter, b: MeshRouter, latencyMs: Long): Pipe {
        val ab = Pipe("sim:${a.displayName}->${b.displayName}", a, b, latencyMs)
        val ba = Pipe("sim:${b.displayName}->${a.displayName}", b, a, latencyMs)
        ab.twin = ba; ba.twin = ab
        a.onLinkReady(ab); b.onLinkReady(ba)
        return ab
    }

    private fun schedule(delayMs: Long, block: () -> Unit) {
        if (!exec.isShutdown) exec.schedule({ runCatching(block).onFailure { log.e(TAG, "sim task failed: $it") } }, delayMs, TimeUnit.MILLISECONDS)
    }
}
