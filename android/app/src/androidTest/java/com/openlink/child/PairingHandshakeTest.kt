package com.openlink.child

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openlink.child.pairing.PairingSession
import com.openlink.child.security.Crypto
import com.openlink.child.security.TlsIdentity
import com.openlink.child.server.ServerState
import com.openlink.child.service.MonitorForegroundService
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Completes the pairing handshake against the real listener, the way the iPhone does.
 *
 * Everything before this only ever proved the listener *bound a port*. Whether anything could
 * actually connect to it -- whether the TLS handshake completes at all, whether the certificate
 * it serves is the one the QR advertises, whether `POST /pair` returns a token -- was never
 * checked anywhere, by any test, on either platform. That gap is exactly where the pairing bug
 * the user hit would live, and it is the reason this file exists.
 *
 * The client below is deliberately built the same way the iOS one is: it trusts *nothing* except
 * a certificate whose SHA-256 matches the fingerprint the child publishes, with no system trust
 * evaluation and no hostname check, because the child is a self-signed certificate on a raw IP.
 * If the server's TLS is misconfigured -- the wrong handler position, a key the engine cannot
 * load -- this fails here instead of on someone's phone.
 */
@RunWith(AndroidJUnit4::class)
class PairingHandshakeTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var identity: TlsIdentity
    private var port: Int = 0

    @Before
    fun startListener() {
        launchActivityAndWait()
        MonitorForegroundService.start(context)

        val snapshot = awaitServerOutcome(timeoutMillis = 60_000)
        assertNull("The listener reported an error: ${snapshot.lastError}", snapshot.lastError)
        assertNotNull("The listener never bound a port", snapshot.port)

        port = snapshot.port!!
        identity = TlsIdentity.loadOrCreate()
    }

    /**
     * The whole handshake: TLS with a pinned certificate, then a proof the child accepts.
     *
     * Also asserts the served certificate is byte-for-byte the one whose fingerprint goes into
     * the QR. If those two ever diverge, every parent would pin a fingerprint that no connection
     * can ever present, and pairing would fail with a certificate error nobody could act on.
     */
    @Test
    fun aPinnedClientCompletesTheHandshakeAndGetsAToken() {
        val pskBase64Url = PairingSession.begin()
        val psk = Crypto.base64UrlDecode(pskBase64Url)
        assertNotNull("PairingSession.begin() produced an undecodable psk", psk)

        val parentId = Crypto.base64Url(Crypto.randomBytes(32))
        val pinning = PinningTrustManager(identity.fingerprint)

        val response = post(
            path = "/pair",
            body = pairBody(parentId = parentId, proof = proofFor(psk!!, parentId)),
            trustManager = pinning
        )

        assertArrayEquals(
            "The certificate served over TLS is not the one the QR advertises, so no parent " +
                "that pinned the QR's fingerprint could ever connect",
            identity.fingerprint,
            pinning.presentedFingerprint
        )
        assertEquals("POST /pair rejected a valid proof: ${response.body}", 200, response.code)
        assertTrue("No parentToken in the response: ${response.body}", response.body.contains("parentToken"))
        assertTrue("No deviceId in the response: ${response.body}", response.body.contains("deviceId"))
    }

    /**
     * The other half of the security property: the pinned channel must not hand a token to
     * someone who didn't read the QR off the child's screen.
     */
    @Test
    fun aWrongProofIsRejected() {
        PairingSession.begin()

        val parentId = Crypto.base64Url(Crypto.randomBytes(32))
        val wrongProof = Crypto.base64Url(Crypto.randomBytes(32))

        val response = post(
            path = "/pair",
            body = pairBody(parentId = parentId, proof = wrongProof),
            trustManager = PinningTrustManager(identity.fingerprint)
        )

        assertTrue(
            "A bad proof was answered with ${response.code}, which is not a refusal: ${response.body}",
            response.code == 401 || response.code == 403
        )
    }

    /** A client that pins the wrong fingerprint must fail to handshake, not merely be refused. */
    @Test
    fun aClientPinningTheWrongCertificateCannotConnectAtAll() {
        PairingSession.begin()
        val wrongFingerprint = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val pinning = PinningTrustManager(wrongFingerprint)

        try {
            post(
                path = "/pair",
                body = pairBody(Crypto.base64Url(Crypto.randomBytes(32)), "nope"),
                trustManager = pinning
            )
            throw AssertionError(
                "A client pinning an unrelated fingerprint completed the TLS handshake. " +
                    "Certificate pinning is the only thing authenticating the child device."
            )
        } catch (expected: IOException) {
            // What a refused handshake looks like from HttpsURLConnection.
        }

        // Without this the test passes for the wrong reason whenever the server's TLS is
        // broken outright: a handshake that dies before any certificate is presented also
        // throws IOException, and would look exactly like pinning doing its job.
        assertNotNull(
            "The connection failed before the device presented any certificate, so this " +
                "proves nothing about pinning -- the listener's TLS is broken.",
            pinning.presentedFingerprint
        )
    }

    // MARK: - HTTPS client

    private data class Response(val code: Int, val body: String)

    private fun post(path: String, body: String, trustManager: X509TrustManager): Response {
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }

        val connection = URL("https://127.0.0.1:$port$path").openConnection() as HttpsURLConnection
        connection.sslSocketFactory = sslContext.socketFactory
        // The child has no hostname and no SAN to match; the fingerprint is the whole identity.
        connection.hostnameVerifier = HostnameVerifier { _: String, _: SSLSession -> true }
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")

        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            Response(code, text)
        } finally {
            connection.disconnect()
        }
    }

    /** Mirrors ios/.../CertificatePinning.swift: one certificate, matched by SHA-256, or nothing. */
    private class PinningTrustManager(private val expected: ByteArray) : X509TrustManager {
        @Volatile
        var presentedFingerprint: ByteArray? = null

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("This client never presents a certificate")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("Empty certificate chain")
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
            presentedFingerprint = fingerprint
            if (!MessageDigest.isEqual(fingerprint, expected)) {
                throw CertificateException("Certificate fingerprint does not match the pin")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    // MARK: - Protocol helpers

    /**
     * `base64url(HMAC-SHA256(psk, "openlink-pair-v1" || parentId))`, with `parentId` as the UTF-8
     * bytes of its base64url text -- the reading docs/PROTOCOL.md's ambiguity is resolved to in
     * PairingSession.verifyAndConsume, and the one iOS implements.
     */
    private fun proofFor(psk: ByteArray, parentIdBase64Url: String): String = Crypto.base64Url(
        Crypto.hmacSha256(
            key = psk,
            message = "openlink-pair-v1".toByteArray(Charsets.UTF_8) +
                parentIdBase64Url.toByteArray(Charsets.UTF_8)
        )
    )

    private fun pairBody(parentId: String, proof: String): String =
        """{"parentId":"$parentId","parentName":"CI parent","proof":"$proof"}"""

    // MARK: - Lifecycle helpers

    private fun launchActivityAndWait() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = instrumentation.targetContext.packageManager
            .getLaunchIntentForPackage(instrumentation.targetContext.packageName)
            ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.targetContext.startActivity(intent)
        instrumentation.waitForIdleSync()
        Thread.sleep(2_000)
    }

    private fun awaitServerOutcome(timeoutMillis: Long): ServerState.Snapshot {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var snapshot = ServerState.snapshot.value
        while (System.currentTimeMillis() < deadline &&
            snapshot.port == null &&
            snapshot.lastError == null
        ) {
            Thread.sleep(250)
            snapshot = ServerState.snapshot.value
        }
        return snapshot
    }
}
