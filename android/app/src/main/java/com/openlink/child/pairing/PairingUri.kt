package com.openlink.child.pairing

import java.net.URLEncoder

/**
 * Builds the `openlink://pair?...` URI that the pairing QR encodes, exactly as specified in
 * docs/PROTOCOL.md.
 *
 * ```
 * openlink://pair?v=1&id=<deviceId>&name=<url-encoded>&fp=<b64url>&psk=<b64url>&ep=<host:port,...>
 * ```
 *
 * `fp`, `psk` and `id` are already base64url (URL-safe, unpadded) so they need no escaping;
 * `name` is arbitrary user text and `ep` contains `:` and `,` separators that must survive, so
 * only `name` is percent-encoded.
 */
object PairingUri {

    const val VERSION = 1

    fun build(
        deviceId: String,
        deviceName: String,
        fingerprintBase64Url: String,
        pskBase64Url: String,
        endpoints: List<String>
    ): String {
        val name = URLEncoder.encode(deviceName, "UTF-8")
        // URLEncoder is form-encoding, which renders a space as '+'. In a query value that is
        // conventionally read back as a space, but percent-encoding it is unambiguous for any
        // parser on the other side.
        val safeName = name.replace("+", "%20")
        val ep = endpoints.joinToString(",")
        return buildString {
            append("openlink://pair?v=").append(VERSION)
            append("&id=").append(deviceId)
            append("&name=").append(safeName)
            append("&fp=").append(fingerprintBase64Url)
            append("&psk=").append(pskBase64Url)
            append("&ep=").append(ep)
        }
    }
}
