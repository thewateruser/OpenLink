package com.openlink.child.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

/**
 * The device's long-lived TLS identity: a P-256 key pair generated once, on first run, inside
 * the Android Keystore.
 *
 * The private key is generated *in* the Keystore and never has a software copy -- `getKey()`
 * hands back an opaque handle whose sign operation is performed by the keystore daemon (and on
 * most devices, by the TEE/StrongBox behind it). That is the property docs/PROTOCOL.md's pairing
 * security model rests on: the QR pins SHA-256 of this certificate, and an attacker can only
 * present this certificate if they can also produce signatures with a key they cannot extract.
 *
 * The certificate itself is the self-signed one AndroidKeyStore mints automatically for a
 * generated key pair (that is what `setCertificateSubject` / `setCertificateNotBefore` /
 * `setCertificateSerialNumber` configure). We never need a CA: the parent app pins the exact
 * fingerprint rather than validating a chain.
 *
 * NOTE for the parent app: this certificate carries no subjectAltName, because the device is
 * dialled by bare IP on the LAN and by a different bare IP over the overlay network, and neither
 * is known at generation time. The parent must therefore disable hostname verification and
 * validate the connection *solely* by comparing the presented leaf's SHA-256 against the pinned
 * `fp`. That is strictly stronger than name validation against a self-signed cert, but it has to
 * be implemented deliberately -- see android/README.md.
 */
class TlsIdentity private constructor(
    val keyStore: KeyStore,
    val certificate: X509Certificate
) {

    val certificateDer: ByteArray = certificate.encoded

    /** SHA-256 over the certificate DER -- the `fp` value in the pairing QR. */
    val fingerprint: ByteArray = Crypto.sha256(certificateDer)

    val fingerprintBase64Url: String = Crypto.base64Url(fingerprint)

    /** Human-checkable rendering, shown on the pairing/settings screens. */
    val fingerprintHex: String = fingerprint.joinToString(":") { byte ->
        "%02X".format(byte.toInt() and 0xFF)
    }

    companion object {
        private const val TAG = "TlsIdentity"

        const val KEY_ALIAS = "openlink_tls_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val VALIDITY_YEARS = 20L

        /**
         * An `SSLContext` whose server key is this device's AndroidKeyStore entry.
         *
         * This exists because Ktor's `sslConnector` cannot be used here at all. Given a keystore
         * it pulls the private key out and hands it to Netty, which builds a *fresh* keystore and
         * calls `setKeyEntry` on it. That fails twice over on Android: Netty passes a null
         * password, which Android's BouncyCastle keystore rejects with an NPE, and an
         * AndroidKeyStore private key is non-exportable by design, so it could never be re-packed
         * even if the password were right.
         *
         * A `KeyManagerFactory` is the supported way to use such a key: it keeps the opaque
         * handle and asks the keystore to sign, so the key never leaves. The resulting context is
         * attached to Netty's pipeline directly (see OpenLinkServer), bypassing `sslConnector`.
         *
         * The null password in `init` is correct and not an oversight -- an AndroidKeyStore entry
         * is protected by the keystore's own access control, not a passphrase.
         */
        fun serverSslContext(keyStore: KeyStore): SSLContext {
            val keyManagerFactory = KeyManagerFactory
                .getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(keyStore, null) }

            return SSLContext.getInstance("TLS").apply {
                init(keyManagerFactory.keyManagers, null, null)
            }
        }

        @Volatile
        private var cached: TlsIdentity? = null

        /**
         * Loads the existing identity, generating it on first call. Synchronized because both
         * the pairing UI (to show the fingerprint) and the foreground service (to start the
         * listener) can reach it, and generating twice would silently rotate the fingerprint out
         * from under already-paired parents.
         */
        @Synchronized
        fun loadOrCreate(): TlsIdentity {
            cached?.let { return it }

            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                generateKeyPair()
            }

            val certificate = keyStore.getCertificate(KEY_ALIAS) as? X509Certificate
                ?: error("Keystore entry $KEY_ALIAS has no X.509 certificate")

            return TlsIdentity(keyStore, certificate).also { cached = it }
        }

        /**
         * Destroys the identity. Every previously issued pin becomes invalid, so this is only
         * reachable from the "forget everything" path in Settings, alongside revoking parents.
         */
        @Synchronized
        fun reset() {
            try {
                KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
            } catch (e: Exception) {
                Log.w(TAG, "Could not delete TLS identity: ${e.javaClass.simpleName}")
            }
            cached = null
        }

        private fun generateKeyPair() {
            val now = System.currentTimeMillis()
            val notBefore = Date(now - TimeUnit.DAYS.toMillis(1)) // tolerate a skewed clock
            val notAfter = Date(now + TimeUnit.DAYS.toMillis(365 * VALIDITY_YEARS))

            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                // TLS 1.2/1.3 ECDSA signatures use SHA-256 or SHA-384 depending on what the
                // peer negotiates; allowing both avoids a handshake failure against a client
                // whose preference list we do not control.
                .setDigests(
                    KeyProperties.DIGEST_SHA256,
                    KeyProperties.DIGEST_SHA384,
                    KeyProperties.DIGEST_SHA512
                )
                .setCertificateSubject(X500Principal("CN=OpenLink Child Device"))
                .setCertificateSerialNumber(randomSerial())
                .setCertificateNotBefore(notBefore)
                .setCertificateNotAfter(notAfter)
                // The listener has to be able to complete a handshake while the screen is off
                // and the device is locked, so the key must not be gated on user auth.
                .setUserAuthenticationRequired(false)
                .build()

            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
                .apply { initialize(spec) }
                .generateKeyPair()

            Log.i(TAG, "Generated a new device TLS identity")
        }

        /** Positive, 64-bit, random -- an X.509 serial must be a positive integer. */
        private fun randomSerial(): BigInteger =
            BigInteger(64, SecureRandom()).max(BigInteger.ONE)
    }
}
