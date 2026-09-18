package com.openlink.child.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the one string in this project that has to survive being read by a completely
 * independent implementation on another platform.
 *
 * A pairing QR that a strict parser rejects is indistinguishable, to the person holding the
 * phone, from a broken app: the iOS side simply says "that isn't an OpenLink pairing code".
 * That shipped, because an IPv6 endpoint is bracketed and `[`/`]` are gen-delims RFC 3986 only
 * allows inside a host. Nothing on the Android side objected -- `java.net.URI` happily accepts
 * them -- so a test that merely round-trips through a JVM parser would have passed too.
 *
 * These assertions therefore check the grammar directly rather than trusting any one parser.
 */
class PairingUriTest {

    /**
     * Everything RFC 3986 permits in a query: `pchar / "/" / "?"`, where
     * `pchar = unreserved / pct-encoded / sub-delims / ":" / "@"`. Notably absent: `[` and `]`.
     */
    private val queryLegal: Set<Char> = buildSet {
        addAll('A'..'Z'); addAll('a'..'z'); addAll('0'..'9')
        addAll("-._~".toList())          // unreserved
        addAll("!$&'()*+,;=".toList())   // sub-delims
        addAll(":@".toList())            // pchar
        addAll("/?".toList())            // query extras
        add('%')                         // pct-encoded introducer
    }

    private fun build(endpoints: List<String>, name: String = "Pixel 6") = PairingUri.build(
        deviceId = "nZ8p_qRtUvWxYz0123456789abcdefghijklmnopqrs",
        deviceName = name,
        fingerprintBase64Url = "3q2-7wABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhs",
        pskBase64Url = "f39_fwABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhs",
        endpoints = endpoints
    )

    private fun queryOf(uri: String): String {
        val marker = "openlink://pair?"
        assertTrue("URI must start with $marker, was: $uri", uri.startsWith(marker))
        return uri.removePrefix(marker)
    }

    @Test
    fun `query is legal when the device has only IPv4 addresses`() {
        val query = queryOf(build(listOf("192.168.1.5:8765")))
        val illegal = query.filterNot { it in queryLegal }
        assertEquals("Illegal query characters: $illegal", "", illegal)
    }

    /** The regression: a bracketed IPv6 literal must not leak raw brackets into the query. */
    @Test
    fun `query is legal when the device also has an IPv6 address`() {
        val query = queryOf(build(listOf("192.168.1.5:8765", "[2001:db8::1]:8765")))
        val illegal = query.filterNot { it in queryLegal }
        assertEquals("Illegal query characters: $illegal", "", illegal)
        assertTrue("IPv6 brackets must be percent-encoded, got: $query", query.contains("%5B"))
        assertTrue("IPv6 brackets must be percent-encoded, got: $query", query.contains("%5D"))
    }

    /** A Tailscale address is the away-from-home path, so it must survive too. */
    @Test
    fun `query is legal for an overlay-network address`() {
        val query = queryOf(build(listOf("100.101.102.103:8765")))
        assertEquals("", query.filterNot { it in queryLegal })
    }

    @Test
    fun `device names with spaces and non-ascii are percent-encoded`() {
        val query = queryOf(build(listOf("192.168.1.5:8765"), name = "Zoë's Pixel 6"))
        assertEquals("Illegal query characters", "", query.filterNot { it in queryLegal })
        // A literal space would terminate the URI for many scanners; '+' would be read back as
        // a plus by anything that isn't form-decoding.
        assertTrue("space must be %20, got: $query", query.contains("%20"))
        assertTrue("must not form-encode spaces as '+', got: $query", !query.contains("Pixel+6"))
    }

    @Test
    fun `all documented fields are present`() {
        val query = queryOf(build(listOf("192.168.1.5:8765")))
        for (field in listOf("v=", "id=", "name=", "fp=", "psk=", "ep=")) {
            assertTrue("missing $field in: $query", query.contains(field))
        }
    }
}
