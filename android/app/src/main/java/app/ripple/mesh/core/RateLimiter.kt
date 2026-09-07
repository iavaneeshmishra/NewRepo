package app.ripple.mesh.core

import java.util.ArrayDeque

/**
 * A sliding-window event counter. Allows at most `max` events in any `windowMs`
 * window; once the budget is spent the caller must drop the event. Not thread-safe;
 * the MeshRouter holds it behind its lock, one instance per message source.
 */
class RateLimiter(
    private val max: Int,
    private val windowMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(max > 0) { "max must be positive" }
        require(windowMs > 0) { "windowMs must be positive" }
    }

    private val stamps = ArrayDeque<Long>()

    /** @return true if an event is within budget and was recorded. */
    fun allow(): Boolean {
        val now = clock()
        val cutoff = now - windowMs
        while (stamps.isNotEmpty() && stamps.first() <= cutoff) stamps.removeFirst()
        if (stamps.size >= max) return false
        stamps.addLast(now)
        return true
    }
}
