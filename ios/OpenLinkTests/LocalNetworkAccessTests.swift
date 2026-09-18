//
//  LocalNetworkAccessTests.swift
//  OpenLink (parent app)
//
//  The message this logic produces is the only thing standing between a user
//  and a dead end: iOS reports a blocked local network as "The Internet
//  connection appears to be offline", and the app used to pass that straight
//  through with "make sure both devices are on the same Wi-Fi" — advice that
//  is worse than useless when they already are.
//
//  Getting the classification wrong sends someone to the wrong place, so the
//  boundaries are pinned here: which addresses are local, which errors mean
//  "no path", and which of the two causes is being reported.
//

import XCTest

final class LocalNetworkAccessTests: XCTestCase {

    // MARK: - Address classification

    func testPrivateAndLinkLocalAddressesAreLocal() {
        for host in ["192.168.1.5", "10.0.0.1", "172.16.4.9", "172.31.255.1", "169.254.3.2"] {
            XCTAssertTrue(LocalNetworkAccess.isLocalNetworkAddress(host), host)
        }
    }

    func testIPv6UniqueLocalAndLinkLocalAddressesAreLocal() {
        for host in ["fe80::1", "fd00::1", "[fd12:3456::9]"] {
            XCTAssertTrue(LocalNetworkAccess.isLocalNetworkAddress(host), host)
        }
    }

    func testBonjourHostnamesAreLocal() {
        XCTAssertTrue(LocalNetworkAccess.isLocalNetworkAddress("pixel-6.local"))
    }

    /// Tailscale and other overlay addresses ride a tunnel interface that
    /// local-network privacy doesn't gate. Calling them local would send
    /// someone to a Settings toggle that changes nothing.
    func testOverlayAndPublicAddressesAreNotLocal() {
        for host in ["100.101.102.103", "8.8.8.8", "2001:db8::1", "example.ts.net", ""] {
            XCTAssertFalse(LocalNetworkAccess.isLocalNetworkAddress(host), host)
        }
    }

    // MARK: - Error classification

    func testOnlyNoPathErrorsQualify() {
        XCTAssertTrue(LocalNetworkAccess.indicatesNoNetworkPath(URLError(.notConnectedToInternet)))
        XCTAssertTrue(LocalNetworkAccess.indicatesNoNetworkPath(URLError(.networkConnectionLost)))

        // These mean we found the network and the device didn't answer, which
        // is a different problem with different advice.
        XCTAssertFalse(LocalNetworkAccess.indicatesNoNetworkPath(URLError(.cannotConnectToHost)))
        XCTAssertFalse(LocalNetworkAccess.indicatesNoNetworkPath(URLError(.timedOut)))
        XCTAssertFalse(LocalNetworkAccess.indicatesNoNetworkPath(URLError(.secureConnectionFailed)))
    }

    // MARK: - Diagnosis

    private let lan = [DeviceEndpoint(host: "192.168.1.5", port: 8765, source: .pairingQR)]
    private let overlayOnly = [DeviceEndpoint(host: "100.101.102.103", port: 8765, source: .pairingQR)]

    /// The reported case: an iPhone on the same Wi-Fi, told it was offline.
    func testOfflineErrorOnLANWithWiFiIsAPermissionProblem() {
        XCTAssertEqual(
            LocalNetworkAccess.diagnose(
                error: URLError(.notConnectedToInternet), endpoints: lan, hasWiFiPath: true),
            .localNetworkPermission)
    }

    func testOfflineErrorOnLANWithoutWiFiIsAWiFiProblem() {
        XCTAssertEqual(
            LocalNetworkAccess.diagnose(
                error: URLError(.notConnectedToInternet), endpoints: lan, hasWiFiPath: false),
            .noWiFiPath)
    }

    /// An unknown Wi-Fi state must not become "check your Wi-Fi": the monitor
    /// simply may not have reported yet, and the permission toggle is the far
    /// more likely cause.
    func testUnknownWiFiStateFallsBackToPermission() {
        XCTAssertEqual(
            LocalNetworkAccess.diagnose(
                error: URLError(.notConnectedToInternet), endpoints: lan, hasWiFiPath: nil),
            .localNetworkPermission)
    }

    func testOverlayOnlyEndpointsAreNotDiagnosedAsLocal() {
        XCTAssertEqual(
            LocalNetworkAccess.diagnose(
                error: URLError(.notConnectedToInternet), endpoints: overlayOnly, hasWiFiPath: true),
            .notLocal)
    }

    func testAnOrdinaryTimeoutIsNotDiagnosedAsLocal() {
        XCTAssertEqual(
            LocalNetworkAccess.diagnose(error: URLError(.timedOut), endpoints: lan, hasWiFiPath: true),
            .notLocal)
    }

    /// A QR usually lists a LAN address *and* an overlay address; the LAN one
    /// is enough to make this the local-network case.
    func testMixedEndpointsCountAsLocal() {
        XCTAssertEqual(
            LocalNetworkAccess.diagnose(
                error: URLError(.notConnectedToInternet),
                endpoints: overlayOnly + lan,
                hasWiFiPath: true),
            .localNetworkPermission)
    }

    // MARK: - Explanations

    func testEveryActionableDiagnosisHasAnExplanation() {
        XCTAssertNil(LocalNetworkAccess.explanation(for: .notLocal))

        let permission = LocalNetworkAccess.explanation(for: .localNetworkPermission)
        XCTAssertNotNil(permission)
        // The whole point is naming the place to go; iOS never re-prompts.
        XCTAssertTrue(permission?.contains("Settings") == true)
        XCTAssertTrue(permission?.contains("Local Network") == true)

        let wifi = LocalNetworkAccess.explanation(for: .noWiFiPath)
        XCTAssertNotNil(wifi)
        XCTAssertTrue(wifi?.contains("Wi-Fi") == true)
    }
}
