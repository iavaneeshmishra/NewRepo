package app.ripple.mesh.core

import java.util.ArrayDeque

/**
 * Bounded, in-memory ring of diagnostic events. Written by the BLE and router
 * layers, read by the Diagnostics screen and exported for bug reports.
 * Thread-safe. Never includes message plaintext.
 */
class EventLog(private val capacity: Int = 500, private val clock: () -> Long = System::currentTimeMillis) {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Event(val at: Long, val level: Level, val tag: String, val message: String)

    private val ring = ArrayDeque<Event>(capacity)
    private val listeners = mutableListOf<(Event) -> Unit>()

    fun log(level: Level, tag: String, message: String) {
        val e = Event(clock(), level, tag, message)
        val ls: List<(Event) -> Unit>
        synchronized(ring) {
            if (ring.size >= capacity) ring.pollFirst()
            ring.addLast(e)
            ls = listeners.toList()
        }
        ls.forEach { it(e) }
    }

    fun d(tag: String, msg: String) = log(Level.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = log(Level.INFO, tag, msg)
    fun w(tag: String, msg: String) = log(Level.WARN, tag, msg)
    fun e(tag: String, msg: String) = log(Level.ERROR, tag, msg)

    fun snapshot(): List<Event> = synchronized(ring) { ring.toList() }
    fun clear() = synchronized(ring) { ring.clear() }

    fun addListener(l: (Event) -> Unit) = synchronized(ring) { listeners.add(l); Unit }
    fun removeListener(l: (Event) -> Unit) = synchronized(ring) { listeners.remove(l); Unit }

    /** Plain-text export suitable for pasting into a bug report. */
    fun export(header: String = ""): String = buildString {
        if (header.isNotEmpty()) appendLine(header).appendLine()
        for (e in snapshot()) appendLine("${formatTime(e.at)} ${e.level.name.padEnd(5)} ${e.tag.padEnd(10)} ${e.message}")
    }

    companion object {
        /** Process-wide instance so the BLE classes don't need injection plumbing. */
        val global = EventLog()

        fun formatTime(ms: Long): String {
            val s = ms / 1000
            return "%02d:%02d:%02d.%03d".format((s / 3600) % 24, (s / 60) % 60, s % 60, ms % 1000)
        }
    }
}
