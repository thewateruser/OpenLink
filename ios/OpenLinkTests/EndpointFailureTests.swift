//
//  EndpointFailureTests.swift
//  OpenLink (parent app)
//
//  Pins the behaviour that a single `lastError` variable got wrong.
//
//  A phone on a dual-stack home network advertises its LAN IPv4 address and
//  one or more global IPv6 addresses. The IPv4 address is ranked first and is
//  the one that matters; the global IPv6 address is tried last and routinely
//  fails with "The Internet connection appears to be offline". Because both
//  the pairing loop and the connection race overwrote their error on every
//  attempt, the reason the user was shown was always the IPv6 one — and the
//  actual reason pairing failed was discarded before anyone could see it.
//

import XCTest

/// Stands in for `DeviceConnectionError.unreachable(underlying:)`, which the
/// pairing loop wraps around every URLError before it is ever inspected.
private struct WrappingError: Error, UnderlyingErrorCarrying {
    let underlyingError: Error?
}

final class EndpointFailureTests: XCTestCase {

    private let lan = DeviceEndpoint(host: "192.168.1.5", port: 8765, source: .pairingQR)
    private let globalV6 = DeviceEndpoint(host: "2001:db8::1", port: 8765, source: .pairingQR)
    private let overlay = DeviceEndpoint(host: "100.101.102.103", port: 8765, source: .pairingQR)

    // MARK: - The regression

    /// The exact shape of the shipped bug: the LAN address failed for a real
    /// reason, the IPv6 address failed last with a misleading one, and the
    /// misleading one was the only one reported.
    func testPrimaryPrefersTheLocalAddressOverWhicheverFailedLast() {
        let failures = [
            EndpointFailure(endpoint: lan, error: URLError(.cannotConnectToHost)),
            EndpointFailure(endpoint: globalV6, error: URLError(.notConnectedToInternet))
        ]

        let primary = EndpointFailureReport.primary(failures)
        XCTAssertEqual(primary?.endpoint.host, "192.168.1.5")
        XCTAssertEqual(LocalNetworkAccess.urlError(in: primary!.error)?.code, .cannotConnectToHost)
    }

    /// Order of arrival must not matter — the race completes tasks in whatever
    /// order they fail, not in ranked order.
    func testPrimaryIsIndependentOfArrivalOrder() {
        let reversed = [
            EndpointFailure(endpoint: globalV6, error: URLError(.notConnectedToInternet)),
            EndpointFailure(endpoint: lan, error: URLError(.timedOut))
        ]
        XCTAssertEqual(EndpointFailureReport.primary(reversed)?.endpoint.host, "192.168.1.5")
    }

    func testPrimaryFallsBackToTheBestRankedAddressWhenNoneAreLocal() {
        let failures = [
            EndpointFailure(endpoint: globalV6, error: URLError(.timedOut)),
            EndpointFailure(endpoint: overlay, error: URLError(.cannotConnectToHost))
        ]
        // Overlay (latency class 3) outranks an unclassified global v6 (class 4).
        XCTAssertEqual(EndpointFailureReport.primary(failures)?.endpoint.host, "100.101.102.103")
    }

    func testPrimaryOfNothingIsNil() {
        XCTAssertNil(EndpointFailureReport.primary([]))
    }

    // MARK: - What the user is shown

    /// Every address appears, because what the person reading the screen can
    /// tell you is the only diagnostic channel a serverless app has.
    func testDetailListsEveryAddressWithItsOwnReason() {
        let detail = EndpointFailureReport.detail([
            EndpointFailure(endpoint: globalV6, error: URLError(.notConnectedToInternet)),
            EndpointFailure(endpoint: lan, error: URLError(.cannotConnectToHost))
        ])

        XCTAssertTrue(detail.contains("192.168.1.5:8765"), detail)
        XCTAssertTrue(detail.contains("[2001:db8::1]:8765"), detail)
        XCTAssertTrue(detail.contains("nothing accepted a connection on that port"), detail)
        XCTAssertTrue(detail.contains("no route to that address"), detail)
        XCTAssertEqual(detail.split(separator: "\n").count, 2)
    }

    /// Ranked order, so the address that was supposed to work is read first.
    func testDetailLeadsWithTheBestRankedAddress() {
        let detail = EndpointFailureReport.detail([
            EndpointFailure(endpoint: globalV6, error: URLError(.timedOut)),
            EndpointFailure(endpoint: lan, error: URLError(.timedOut))
        ])
        XCTAssertTrue(detail.hasPrefix("• 192.168.1.5:8765"), detail)
    }

    func testSummaryKeepsTheHeadlineWhenThereIsNothingToList() {
        XCTAssertEqual(EndpointFailureReport.summary([], headline: "Nope."), "Nope.")
    }

    func testSummaryCombinesHeadlineAndDetail() {
        let summary = EndpointFailureReport.summary(
            [EndpointFailure(endpoint: lan, error: URLError(.timedOut))],
            headline: "Couldn't reach it.")
        XCTAssertTrue(summary.hasPrefix("Couldn't reach it."), summary)
        XCTAssertTrue(summary.contains("192.168.1.5:8765 — timed out"), summary)
    }

    // MARK: - Reasons

    func testReasonsAreReadThroughWrappingErrors() {
        // The shipped diagnosis missed this: what the loop collects is never a
        // bare URLError, it is one wrapped in a DeviceConnectionError.
        let failure = EndpointFailure(
            endpoint: lan,
            error: WrappingError(underlyingError: URLError(.cannotConnectToHost)))
        XCTAssertEqual(failure.shortReason, "nothing accepted a connection on that port")
    }

    func testAnUnrecognisedErrorStillProducesSomething() {
        struct Opaque: Error {}
        let failure = EndpointFailure(endpoint: lan, error: Opaque())
        XCTAssertFalse(failure.shortReason.isEmpty)
    }
}
