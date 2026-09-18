package com.openlink.child

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the platform behaviour that broke every TLS handshake the child device ever attempted.
 *
 * The identity key was generated authorising SHA-256/384/512 and nothing else, on the reasonable
 * sounding grounds that "TLS 1.2/1.3 ECDSA signatures use SHA-256 or SHA-384". That reasoning is
 * wrong about *who hashes*. Conscrypt -- the TLS implementation on Android, underneath Netty's
 * SSLEngine -- computes the handshake digest itself and then asks the key to sign those bytes
 * raw, via `NONEwithECDSA` (CryptoUpcalls.ecSignDigestWithPrivateKey). A key that has not
 * authorised `DIGEST_NONE` refuses, with:
 *
 *     android.security.KeyStoreException: Incompatible digest (internal Keystore code: -13)
 *         Error::Km(r#INCOMPATIBLE_DIGEST)
 *
 * The handshake then dies mid-flight and the peer just sees the connection close -- which on an
 * iPhone reads as a generic, unactionable failure to reach the device.
 *
 * These tests use throwaway aliases, never the real identity, so they cannot disturb the running
 * listener. They assert the failure and the fix directly against the device's own keystore rather
 * than reasoning about which digest a protocol "should" use, because reasoning about exactly that
 * is what produced the bug.
 */
@RunWith(AndroidJUnit4::class)
class TlsKeyDigestTest {

    private val legacyAlias = "openlink_test_legacy_digests"
    private val currentAlias = "openlink_test_current_digests"

    @After
    fun deleteScratchKeys() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        for (alias in listOf(legacyAlias, currentAlias)) {
            runCatching { keyStore.deleteEntry(alias) }
        }
    }

    /** The bug, reproduced: SHA digests alone cannot serve TLS. */
    @Test
    fun aKeyWithoutDigestNoneCannotSignAHandshake() {
        generateKey(
            legacyAlias,
            KeyProperties.DIGEST_SHA256,
            KeyProperties.DIGEST_SHA384,
            KeyProperties.DIGEST_SHA512
        )
        assertFalse(
            "A key authorising only SHA digests signed a raw digest. If the platform has " +
                "changed this, the DIGEST_NONE requirement in TlsIdentity can be revisited -- " +
                "but do not remove it on the strength of this test alone, since older devices " +
                "in the wild still behave the old way.",
            canSignRawDigest(legacyAlias)
        )
    }

    /** The fix: the digest set TlsIdentity now uses can do what a handshake asks. */
    @Test
    fun aKeyWithDigestNoneCanSignAHandshake() {
        generateKey(
            currentAlias,
            KeyProperties.DIGEST_NONE,
            KeyProperties.DIGEST_SHA256,
            KeyProperties.DIGEST_SHA384,
            KeyProperties.DIGEST_SHA512
        )
        assertTrue(
            "The digest set TlsIdentity generates keys with cannot sign a raw digest, so no " +
                "TLS handshake against this device can ever complete.",
            canSignRawDigest(currentAlias)
        )
    }

    private fun generateKey(alias: String, vararg digests: String) {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(*digests)
            .setUserAuthenticationRequired(false)
            .build()

        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            .apply { initialize(spec) }
            .generateKeyPair()
    }

    /** Exactly what Conscrypt asks of the key during a handshake. */
    private fun canSignRawDigest(alias: String): Boolean = try {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val privateKey = keyStore.getKey(alias, null) as PrivateKey
        Signature.getInstance("NONEwithECDSA").run {
            initSign(privateKey)
            update(ByteArray(32)) // a SHA-256-sized digest
            sign()
        }
        true
    } catch (e: Exception) {
        false
    }
}
