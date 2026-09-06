package app.ripple.mesh.ble

import app.ripple.mesh.core.EventLog
import app.ripple.mesh.core.Fragmenter
import app.ripple.mesh.core.Link
import app.ripple.mesh.core.Reassembler
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Common half of a BLE link: fragments outbound packets into frames and pushes
 * them through a single writer thread; reassembles inbound frames into packets.
 *
 * Subclasses provide [writeFrame] (blocking until the frame is on the air) and
 * [closeTransport].
 */
abstract class BleLink(override val id: String, private val onPacket: (BleLink, ByteArray) -> Unit, private val onClosed: (BleLink) -> Unit) : Link {
    override var peerHex: String? = null

    /** Negotiated frame size (mtu - 3, capped at 512). Updated by the subclass. */
    @Volatile var frameSize: Int = 20

    // Diagnostics
    val openedAt: Long = System.currentTimeMillis()
    @Volatile var bytesIn: Long = 0; private set
    @Volatile var bytesOut: Long = 0; private set
    @Volatile var packetsIn: Int = 0; private set
    @Volatile var packetsOut: Int = 0; private set
    @Volatile var rssi: Int? = null
    @Volatile var lastActivity: Long = openedAt; private set
    protected val log = EventLog.global

    private val reassembler = Reassembler()
    private val outbound = LinkedBlockingQueue<ByteArray>()
    private val closed = AtomicBoolean(false)
    private val writer = Thread({ writerLoop() }, "ble-writer-$id").apply { isDaemon = true }

    fun start() { writer.start() }

    override fun send(packetBytes: ByteArray) {
        if (!closed.get()) outbound.offer(packetBytes)
    }

    /** Called by the subclass for every frame received from the radio. */
    protected fun onFrame(frame: ByteArray) {
        bytesIn += frame.size; lastActivity = System.currentTimeMillis()
        val packet = synchronized(reassembler) { reassembler.push(frame) } ?: return
        packetsIn++
        onPacket(this, packet)
    }

    private fun writerLoop() {
        try {
            while (!closed.get()) {
                val packet = outbound.take()
                val streamId = Random.nextInt(0, 0x10000)
                for (frame in Fragmenter.fragment(packet, frameSize, streamId)) {
                    if (closed.get()) return
                    if (!writeFrame(frame)) { log.w("link", "$id write failed; closing"); close(); return }
                    bytesOut += frame.size; lastActivity = System.currentTimeMillis()
                }
                packetsOut++
            }
        } catch (_: InterruptedException) { }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        writer.interrupt()
        try { closeTransport() } catch (_: Exception) {}
        onClosed(this)
    }

    val isClosed: Boolean get() = closed.get()

    /** Write one frame and block until it has been sent. Return false on fatal error. */
    protected abstract fun writeFrame(frame: ByteArray): Boolean
    protected abstract fun closeTransport()
}
