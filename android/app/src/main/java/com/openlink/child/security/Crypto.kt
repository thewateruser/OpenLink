package com.openlink.child.security

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The small set of primitives the pairing handshake and bearer-token auth need, in one place so
 * there is exactly one base64url spelling, one SHA-256 call site style, and one comparison
 * routine in the app.
 *
 * Everything here is deliberately boring. The interesting property is [constantTimeEquals]:
 * every secret comparison in this app (pairing proof, parent token hash) goes through it, so a
 * caller can never accidentally reach for `contentEquals` and leak a byte-at-a-time oracle to
 * anyone who can reach the listener.
 */
object Crypto {

    /**
     * base64url with no padding and no line wrapping -- the encoding docs/PROTOCOL.md specifies
     * for `fp`, `psk`, `parentId`, `proof` and `parentToken`.
     */
    private const val B64_FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

    private val secureRandom = SecureRandom()

    fun randomBytes(count: Int): ByteArray = ByteArray(count).also { secureRandom.nextBytes(it) }

    fun base64Url(bytes: ByteArray): String = Base64.encodeToString(bytes, B64_FLAGS)

    /** Returns null rather than throwing: every caller is parsing attacker-controlled input. */
    fun base64UrlDecode(value: String): ByteArray? = try {
        Base64.decode(value, B64_FLAGS)
    } catch (e: IllegalArgumentException) {
        null
    }

    fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    fun sha256(value: String): ByteArray = sha256(value.toByteArray(Charsets.UTF_8))

    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    /**
     * Length-independent-time comparison.
     *
     * The early `size` return is safe for every use in this app because both operands are always
     * fixed-width digests (32-byte SHA-256 / HMAC-SHA256 outputs); a length mismatch means the
     * input was malformed, which is not a secret. The byte loop never short-circuits, so no
     * information about *where* two equal-length values differ is observable in the timing.
     */
    fun constantTimeEquals(a: ByteArray?, b: ByteArray?): Boolean {
        if (a == null || b == null) return false
        if (a.size != b.size) return false
        var difference = 0
        for (i in a.indices) {
            difference = difference or (a[i].toInt() xor b[i].toInt())
        }
        return difference == 0
    }

    /** Best-effort wipe of a secret we are done with. Not a guarantee on a managed heap. */
    fun wipe(bytes: ByteArray?) {
        bytes?.fill(0)
    }
}
