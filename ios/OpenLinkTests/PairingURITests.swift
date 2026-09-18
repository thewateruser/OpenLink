//
//  PairingURITests.swift
//  OpenLink (parent app)
//
//  The pairing QR is the only string in this project written by one
//  implementation and read by a completely different one on another platform,
//  and it is the very first thing a new user does. When it goes wrong the app
//  says "That isn't an OpenLink pairing code" and there is nowhere to go from
//  there.
//
//  It did go wrong: the child listed a bracketed IPv6 endpoint, `[` and `]`
//  are gen-delims RFC 3986 allows only inside a host, and `URLComponents`
//  returned nil for the whole string. Nothing on the Android side objected --
//  java.net.URI accepts the brackets -- so the failure existed only on a real
//  iPhone, where no test was looking.
//
//  The fixtures below are verbatim output of the Kotlin builder in
//  android/app/src/main/java/com/openlink/child/pairing/PairingUri.kt, so the
//  two sides are checked against the same bytes rather than against each
//  side's idea of the format. android/app/src/test/.../PairingUriTest.kt
//  guards the producing end; this guards the consuming end.
//

import XCTest

final class PairingURITests: XCTestCase {

    private let deviceId = "nZ8p_qRtUvWxYz0123456789abcdefghijklmnopqrs"
    private let fingerprint = "3q2-7wABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhs"
    private let psk = "f39_fwABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhs"

    private func uri(name: String = "Pixel%206", endpoints: String) -> String {
        "openlink://pair?v=1&id=\(deviceId)&name=\(name)&fp=\(fingerprint)&psk=\(psk)&ep=\(endpoints)"
    }

    // MARK: - The regression

    /// What current child builds emit: brackets percent-encoded.
    func testParsesEncodedIPv6Endpoint() throws {
        let parsed = try PairingURI.parse(
            uri(endpoints: "192.168.1.5:8765,%5B2001:db8::1%5D:8765"))

        XCTAssertEqual(parsed.deviceId, deviceId)
        XCTAssertEqual(parsed.endpoints.count, 2)
        XCTAssertEqual(parsed.endpoints[0].host, "192.168.1.5")
        XCTAssertEqual(parsed.endpoints[0].port, 8765)
        XCTAssertEqual(parsed.endpoints[1].host, "2001:db8::1")
        XCTAssertEqual(parsed.endpoints[1].port, 8765)
    }

    /// What child builds up to v0.3.0 emit: raw brackets. Those APKs are
    /// already installed on people's phones, so a fixed parent app that only
    /// accepted the new form would leave them exactly as stuck as before.
    func testParsesRawBracketIPv6Endpoint() throws {
        let parsed = try PairingURI.parse(
            uri(endpoints: "192.168.1.5:8765,[2001:db8::1]:8765"))

        XCTAssertEqual(parsed.endpoints.count, 2)
        XCTAssertEqual(parsed.endpoints[1].host, "2001:db8::1")
    }

    /// The away-from-home path: a Tailscale address must parse like any other.
    func testParsesOverlayNetworkEndpoint() throws {
        let parsed = try PairingURI.parse(uri(endpoints: "100.101.102.103:8765"))
        XCTAssertEqual(parsed.endpoints.first?.host, "100.101.102.103")
    }

    // MARK: - Fields

    func testDecodesPercentEncodedDeviceName() throws {
        let parsed = try PairingURI.parse(
            uri(name: "Zo%C3%AB%27s%20Pixel%206", endpoints: "192.168.1.5:8765"))
        XCTAssertEqual(parsed.deviceName, "Zoë's Pixel 6")
    }

    func testKeepsFingerprintTextAndDecodesToThirtyTwoBytes() throws {
        let parsed = try PairingURI.parse(uri(endpoints: "192.168.1.5:8765"))
        XCTAssertEqual(parsed.fingerprintBase64URL, fingerprint)
        XCTAssertEqual(parsed.fingerprintBytes.count, 32)
        XCTAssertEqual(parsed.psk.count, 32)
    }

    /// QR scanners hand back whatever the code contains; a scheme is
    /// case-insensitive per RFC 3986, so a differently-cased payload is still
    /// a valid pairing code.
    func testSchemeAndHostAreCaseInsensitive() throws {
        let parsed = try PairingURI.parse(
            "OPENLINK://PAIR?v=1&id=\(deviceId)&name=Pixel&fp=\(fingerprint)&psk=\(psk)&ep=192.168.1.5:8765")
        XCTAssertEqual(parsed.deviceId, deviceId)
    }

    // MARK: - Rejections

    /// Pointing the camera at an unrelated QR must be an ordinary miss, not a
    /// crash or a half-built device entry.
    func testRejectsUnrelatedPayload() {
        assertParseFails("https://example.com/not-a-pairing-code", as: .notAPairingURI)
        assertParseFails("", as: .notAPairingURI)
    }

    func testRejectsUnsupportedVersion() {
        assertParseFails(
            "openlink://pair?v=2&id=\(deviceId)&fp=\(fingerprint)&psk=\(psk)&ep=192.168.1.5:8765",
            as: .unsupportedVersion(2))
    }

    /// A truncated fingerprint would silently weaken the one check the whole
    /// security model rests on, so it has to be a hard failure.
    func testRejectsShortFingerprint() {
        assertParseFails(
            "openlink://pair?v=1&id=\(deviceId)&fp=3q2-7w&psk=\(psk)&ep=192.168.1.5:8765",
            as: .badFingerprint)
    }

    func testRejectsMissingEndpoints() {
        assertParseFails(
            "openlink://pair?v=1&id=\(deviceId)&fp=\(fingerprint)&psk=\(psk)",
            as: .missing(field: "ep"))
    }

    private func assertParseFails(
        _ raw: String,
        as expected: PairingURI.ParseError,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertThrowsError(try PairingURI.parse(raw), file: file, line: line) { error in
            XCTAssertEqual(error as? PairingURI.ParseError, expected, file: file, line: line)
        }
    }
}
