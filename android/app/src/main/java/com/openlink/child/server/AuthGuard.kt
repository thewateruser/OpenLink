package com.openlink.child.server

import com.openlink.child.pairing.ParentRegistry
import com.openlink.child.prefs.PairedParent
import com.openlink.child.security.RateLimiter
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.util.AttributeKey

/** Where the authenticated parent is stashed for the duration of a call. */
val ParentAttributeKey = AttributeKey<PairedParent>("openlink.parent")

/**
 * Bearer-token authentication for every route except `POST /pair`.
 *
 * This is the only thing standing between the open internet (or, more realistically, everyone
 * else on the coffee shop Wi-Fi) and an API that can lock a child's phone. Three properties
 * matter and all three are implemented here rather than left to a caller:
 *
 *  1. the comparison is constant-time and against a SHA-256 of the token, never the token;
 *  2. failures are rate-limited per source address with a global ceiling, so the 2^256 keyspace
 *     cannot be attacked at line rate even from many addresses;
 *  3. successes cost nothing, so a parent polling every 30 seconds never throttles itself.
 *
 * Nothing about the token -- not a prefix, not a length, not which check failed -- appears in a
 * log or a response body.
 */
class AuthGuard(private val registry: ParentRegistry) {

    private val failureLimiter = RateLimiter(
        maxPerKey = MAX_FAILURES_PER_ADDRESS,
        maxGlobal = MAX_FAILURES_GLOBAL,
        windowMillis = WINDOW_MILLIS
    )

    sealed class Result {
        data class Authenticated(val parent: PairedParent) : Result()
        object Unauthorized : Result()
        object RateLimited : Result()
    }

    fun authenticate(call: ApplicationCall): Result {
        val key = remoteKey(call)
        if (failureLimiter.isBlocked(key)) return Result.RateLimited

        val token = bearerToken(call)
        if (token == null) {
            failureLimiter.tryAcquire(key)
            return Result.Unauthorized
        }

        val parent = registry.resolve(token)
        if (parent == null) {
            failureLimiter.tryAcquire(key)
            return Result.Unauthorized
        }
        return Result.Authenticated(parent)
    }

    private fun bearerToken(call: ApplicationCall): String? {
        val header = call.request.headers[HttpHeaders.Authorization] ?: return null
        if (!header.regionMatches(0, BEARER_PREFIX, 0, BEARER_PREFIX.length, ignoreCase = true)) {
            return null
        }
        return header.substring(BEARER_PREFIX.length).trim().takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val BEARER_PREFIX = "Bearer "
        private const val MAX_FAILURES_PER_ADDRESS = 10
        private const val MAX_FAILURES_GLOBAL = 60
        private const val WINDOW_MILLIS = 60_000L

        /**
         * The rate-limit bucket key. The remote address is the only identity an unauthenticated
         * peer has; it is spoofable on a LAN, which is exactly why every limiter here also has a
         * global ceiling that a rotating source address cannot escape.
         */
        fun remoteKey(call: ApplicationCall): String =
            try {
                call.request.origin.remoteAddress
            } catch (e: Exception) {
                "unknown"
            }
    }
}
