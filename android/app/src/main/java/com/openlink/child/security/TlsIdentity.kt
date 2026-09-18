package com.openlink.child.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
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
            } else if (!canSignTlsHandshakes(keyStore)) {
                // Migration for devices that already generated a key under the old, broken
                // digest set. Those keys cannot complete a handshake and never will, so the
                // identity is useless and replacing it costs nothing -- no parent can have
                // paired against it, because pairing requires a handshake that could not
                // happen. Silently living with it would mean the app never works on exactly
                // the devices that installed it first.
                Log.w(TAG, "Existing TLS key cannot sign a handshake; regenerating it")
                try {
                    keyStore.deleteEntry(KEY_ALIAS)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not delete the unusable key: ${e.javaClass.simpleName}")
                }
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

        /**
         * Performs the exact operation a TLS handshake needs, and reports whether the key can
         * do it.
         *
         * Checking the key's authorised digests through KeyInfo would be reading metadata and
         * re-deriving what the TLS stack will ask for -- the same reasoning that produced the
         * bug. This asks the key to sign a 32-byte digest through NONEwithECDSA, which is
         * precisely what Conscrypt does during a handshake, and believes the answer.
         */
        private fun canSignTlsHandshakes(keyStore: KeyStore): Boolean = try {
            val privateKey = keyStore.getKey(KEY_ALIAS, null) as? PrivateKey
            if (privateKey == null) {
                false
            } else {
                Signature.getInstance("NONEwithECDSA").run {
                    initSign(privateKey)
                    update(ByteArray(32)) // a SHA-256-sized digest, as the handshake supplies
                    sign()
                }
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "TLS key self-test failed: ${e.javaClass.simpleName}: ${e.message}")
            false
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
                // DIGEST_NONE is the one that actually matters, and leaving it out broke
                // every TLS handshake this device ever attempted.
                //
                // The obvious reasoning -- "TLS 1.2/1.3 ECDSA uses SHA-256 or SHA-384, so
                // authorise those" -- is wrong about who does the hashing. Conscrypt computes
                // the handshake digest itself and then asks the key to sign that digest raw,
                // through NONEwithECDSA (see CryptoUpcalls.ecSignDigestWithPrivateKey). A key
                // that authorises only the SHA digests refuses that operation with
                // KeyStoreException: Incompatible digest, the handshake dies mid-flight, and
                // the peer sees the connection close. The SHA entries below are kept for any
                // stack that delegates hashing to the key instead, but NONE is what the
                // platform's own TLS implementation needs.
                //
                // Authorising DIGEST_NONE means the key will sign a caller-supplied 32 bytes
                // without hashing them. That is inherent to using a keystore key for TLS on
                // Android, and the key is used for nothing else.
                .setDigests(
                    KeyProperties.DIGEST_NONE,
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
