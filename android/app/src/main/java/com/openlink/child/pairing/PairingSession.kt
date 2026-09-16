package com.openlink.child.pairing

import com.openlink.child.security.Crypto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The live pairing window: the single-use `psk` that the QR on screen encodes.
 *
 * Process-wide state on purpose -- the pairing screen (an Activity) mints the secret and the
 * embedded server (a Service) verifies against it, and they are different components of the same
 * process.
 *
 * Lifecycle, per docs/PROTOCOL.md:
 *  - a fresh 32-byte `psk` each time the pairing screen opens ([begin]),
 *  - valid for five minutes,
 *  - destroyed after exactly one successful pairing, on expiry, or when the screen closes
 *    ([end]).
 *
 * Outside that window `POST /pair` has nothing to verify against and refuses everything, so the
 * device's only unauthenticated route is inert unless a human is physically looking at the
 * child's screen.
 */
object PairingSession {

    private const val PSK_BYTES = 32
    private const val VALIDITY_MILLIS = 5 * 60 * 1000L

    /** The domain separator from docs/PROTOCOL.md's proof construction. */
    private val PROOF_PREFIX = "openlink-pair-v1".toByteArray(Charsets.UTF_8)

    private class Session(val psk: ByteArray, val expiresAtMillis: Long)

    @Volatile
    private var session: Session? = null

    private val _active = MutableStateFlow(false)

    /** Lets the pairing screen show/hide its QR without polling. */
    val active: StateFlow<Boolean> = _active

    /**
     * Opens a pairing window, replacing (and thereby invalidating) any previous one. Returns the
     * base64url `psk` to embed in the QR.
     */
    @Synchronized
    fun begin(): String {
        val psk = Crypto.randomBytes(PSK_BYTES)
        session?.let { Crypto.wipe(it.psk) }
        session = Session(psk, System.currentTimeMillis() + VALIDITY_MILLIS)
        _active.value = true
        return Crypto.base64Url(psk)
    }

    /** Closes the window -- called when the pairing screen leaves the composition. */
    @Synchronized
    fun end() {
        session?.let { Crypto.wipe(it.psk) }
        session = null
        _active.value = false
    }

    /** Wall-clock expiry of the current window, or null when there is none. */
    @Synchronized
    fun expiresAtMillis(): Long? = currentValidSession()?.expiresAtMillis

    @Synchronized
    fun isActive(): Boolean = currentValidSession() != null

    /**
     * Verifies a `POST /pair` proof and, on success, consumes the window.
     *
     * `proof = base64url(HMAC-SHA256(key = psk, msg = "openlink-pair-v1" || parentId))`.
     *
     * AMBIGUITY, resolved here and documented in android/README.md: docs/PROTOCOL.md writes the
     * message as a concatenation with `parentId`, but `parentId` crosses the wire as a JSON
     * string, so "the parentId" could mean either its 32 decoded bytes or its base64url text.
     * This implementation uses the **UTF-8 bytes of the base64url string exactly as it appears
     * in the request body**, because that is the one form both peers can agree on without
     * re-deriving a canonical encoding. The iOS side must do the same.
     */
    @Synchronized
    fun verifyAndConsume(parentIdBase64Url: String, proofBase64Url: String): Boolean {
        val current = currentValidSession() ?: return false

        val presented = Crypto.base64UrlDecode(proofBase64Url) ?: return false
        val expected = Crypto.hmacSha256(
            key = current.psk,
            message = PROOF_PREFIX + parentIdBase64Url.toByteArray(Charsets.UTF_8)
        )

        if (!Crypto.constantTimeEquals(presented, expected)) return false

        // Single use: a replay of this exact request must not pair a second parent.
        end()
        return true
    }

    private fun currentValidSession(): Session? {
        val current = session ?: return null
        if (System.currentTimeMillis() >= current.expiresAtMillis) {
            Crypto.wipe(current.psk)
            session = null
            _active.value = false
            return null
        }
        return current
    }
}
