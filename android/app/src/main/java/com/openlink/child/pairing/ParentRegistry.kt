package com.openlink.child.pairing

import android.content.Context
import com.openlink.child.prefs.PairedParent
import com.openlink.child.prefs.SecurePrefs
import com.openlink.child.security.Crypto

/**
 * The set of parent apps allowed to drive this device, and the issuing/checking of their bearer
 * tokens.
 *
 * Tokens are 32 random bytes, base64url-encoded. This device stores only SHA-256 of the token
 * string, and every check runs in constant time, so neither a storage dump nor a timing
 * measurement yields a usable credential.
 */
class ParentRegistry(context: Context) {

    private val prefs = SecurePrefs(context)

    fun parents(): List<PairedParent> = prefs.parents()

    fun isPaired(): Boolean = prefs.isPaired()

    /**
     * Mints a token for a newly paired parent and returns it. This is the only moment the token
     * exists on this device; it is handed straight to the `POST /pair` response and dropped.
     */
    fun register(parentId: String, parentName: String): String {
        val token = Crypto.base64Url(Crypto.randomBytes(TOKEN_BYTES))
        prefs.addParent(
            PairedParent(
                parentId = parentId,
                parentName = parentName.ifBlank { "Parent" }.take(64),
                tokenHashBase64Url = Crypto.base64Url(Crypto.sha256(token)),
                pairedAtEpochMillis = System.currentTimeMillis()
            )
        )
        return token
    }

    fun revoke(parentId: String): Boolean {
        val removed = prefs.removeParent(parentId)
        if (removed) bumpRevocationEpoch()
        return removed
    }

    fun revokeAll() {
        prefs.clearParents()
        bumpRevocationEpoch()
    }

    /** Whether this parent is still paired. Used to evict a revoked parent's live WebSocket. */
    fun isStillPaired(parentId: String): Boolean =
        prefs.parents().any { it.parentId == parentId }

    /**
     * Resolves a presented bearer token to the parent that holds it, or null.
     *
     * Every stored parent is compared, with no early exit on a match, so the time taken depends
     * only on how many parents are paired -- which is not a secret -- and not on how close the
     * presented token was to a real one.
     */
    fun resolve(presentedToken: String): PairedParent? {
        if (presentedToken.length !in MIN_TOKEN_CHARS..MAX_TOKEN_CHARS) return null
        val presentedHash = Crypto.sha256(presentedToken)

        var matched: PairedParent? = null
        for (parent in prefs.parents()) {
            val storedHash = Crypto.base64UrlDecode(parent.tokenHashBase64Url) ?: continue
            if (Crypto.constantTimeEquals(presentedHash, storedHash)) {
                matched = parent
            }
        }
        return matched
    }

    companion object {
        /**
         * Bumped whenever any parent is revoked, from the API or from the child's own settings
         * screen.
         *
         * Bearer auth is checked once per HTTP request, but a WebSocket is authenticated only at
         * the handshake -- so without this, a revoked parent would keep receiving live events for
         * as long as it held the socket open. The `/events` handler watches this counter and
         * re-checks its own membership when it moves, which costs nothing while nothing changes.
         */
        @Volatile
        private var revocationEpoch: Int = 0

        fun currentRevocationEpoch(): Int = revocationEpoch

        @Synchronized
        private fun bumpRevocationEpoch() {
            revocationEpoch++
        }

        private const val TOKEN_BYTES = 32

        // 32 bytes of base64url without padding is exactly 43 characters. The bounds are a
        // cheap guard against a caller shipping a megabyte-long "token" through SHA-256 on
        // every request; they leak nothing beyond the documented token format.
        const val MIN_TOKEN_CHARS = 43
        const val MAX_TOKEN_CHARS = 64
    }
}
