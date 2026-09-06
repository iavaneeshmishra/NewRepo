package app.ripple.mesh.core

import java.nio.ByteBuffer

/** BLE frame fragmentation per PROTOCOL.md §5.2. */
object Fragmenter {
    const val HEADER = 4

    fun fragment(packet: ByteArray, frameSize: Int, streamId: Int): List<ByteArray> {
        val chunk = frameSize - HEADER
        require(chunk >= 1) { "frame too small" }
        val total = maxOf(1, (packet.size + chunk - 1) / chunk)
        require(total <= 255) { "packet needs too many fragments" }
        return List(total) { i ->
            val start = i * chunk
            val end = minOf(packet.size, start + chunk)
            ByteBuffer.allocate(HEADER + (end - start))
                .putShort(streamId.toShort()).put(i.toByte()).put(total.toByte())
                .put(packet, start, end - start)
                .array()
        }
    }
}

/** Reassembles frames from one link. Not thread-safe; owned by the link's single worker. */
class Reassembler(private val ttlMs: Long = Protocol.REASSEMBLY_TTL_MS, private val clock: () -> Long = System::currentTimeMillis) {
    private class Stream(val total: Int, val startedAt: Long) {
        val parts = arrayOfNulls<ByteArray>(total)
        var have = 0
    }

    private val streams = HashMap<Int, Stream>()

    /** @return the complete packet when the last fragment arrives, else null. */
    fun push(frame: ByteArray): ByteArray? {
        if (frame.size < Fragmenter.HEADER) return null
        val streamId = ((frame[0].toInt() and 0xff) shl 8) or (frame[1].toInt() and 0xff)
        val index = frame[2].toInt() and 0xff
        val total = frame[3].toInt() and 0xff
        if (total == 0 || index >= total) return null

        val now = clock()
        streams.entries.removeAll { now - it.value.startedAt > ttlMs }

        var s = streams[streamId]
        if (s == null || s.total != total) { s = Stream(total, now); streams[streamId] = s }
        if (s.parts[index] == null) {
            s.parts[index] = frame.copyOfRange(Fragmenter.HEADER, frame.size)
            s.have++
        }
        if (s.have < total) return null
        streams.remove(streamId)
        val size = s.parts.sumOf { it!!.size }
        val out = ByteBuffer.allocate(size)
        s.parts.forEach { out.put(it!!) }
        return out.array()
    }
}
