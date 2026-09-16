package com.openlink.child.security

/**
 * A fixed-window counter used to bound how fast an unauthenticated peer can guess.
 *
 * Two instances exist (see server/AuthGuard.kt and server/Routes.kt): one over `POST /pair`, one
 * over failed bearer-token authentications. Both are keyed by remote address, with a global
 * bucket layered on top so that an attacker who can spoof or rotate source addresses -- trivial
 * on a LAN, and the entire point of an overlay network is that peers can hold many addresses --
 * still cannot turn the per-address limit into unlimited total attempts.
 *
 * The key map is capped: an unbounded map keyed by attacker-chosen addresses is itself a memory
 * exhaustion vector on a phone. When the cap is hit, the whole map is dropped. That "forgets"
 * some in-progress windows, which is why the global bucket matters -- it is the limit that
 * cannot be flushed.
 */
class RateLimiter(
    private val maxPerKey: Int,
    private val maxGlobal: Int,
    private val windowMillis: Long,
    private val maxTrackedKeys: Int = 256,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private class Window(var windowStart: Long, var count: Int)

    private val perKey = LinkedHashMap<String, Window>()
    private val global = Window(0L, 0)

    /**
     * Records an attempt for [key] and returns true if it is within budget. Callers should only
     * charge the limiter for attempts worth limiting -- for auth that means failures, so a busy
     * but legitimate parent is never throttled.
     */
    @Synchronized
    fun tryAcquire(key: String): Boolean {
        val now = clock()

        if (now - global.windowStart >= windowMillis) {
            global.windowStart = now
            global.count = 0
        }

        if (perKey.size > maxTrackedKeys) {
            perKey.clear()
        }

        val window = perKey.getOrPut(key) { Window(now, 0) }
        if (now - window.windowStart >= windowMillis) {
            window.windowStart = now
            window.count = 0
        }

        window.count++
        global.count++

        return window.count <= maxPerKey && global.count <= maxGlobal
    }

    /**
     * Whether [key] is already over budget, without charging an attempt.
     *
     * Auth uses this: a successful request must not consume anyone's budget, or a parent polling
     * normally would lock itself out. Only failures are charged, via [tryAcquire].
     */
    @Synchronized
    fun isBlocked(key: String): Boolean {
        val now = clock()
        if (now - global.windowStart < windowMillis && global.count >= maxGlobal) return true
        val window = perKey[key] ?: return false
        if (now - window.windowStart >= windowMillis) return false
        return window.count >= maxPerKey
    }

    /** Clears all state, e.g. once a pairing session ends and its window is irrelevant. */
    @Synchronized
    fun reset() {
        perKey.clear()
        global.windowStart = 0L
        global.count = 0
    }
}
