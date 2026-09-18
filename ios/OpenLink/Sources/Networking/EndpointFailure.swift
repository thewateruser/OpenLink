//
//  EndpointFailure.swift
//  OpenLink (parent app)
//
//  One address, and why it didn't work.
//
//  Both the pairing handshake and ordinary connections try several addresses
//  from the QR code. Both used to keep a single `lastError` variable and
//  overwrite it on every attempt, so the error the user saw was whichever
//  address happened to be tried LAST — which, because the list is ranked
//  fastest-first, is the *least* likely address to be the interesting one.
//
//  That is not a cosmetic problem. A phone on a dual-stack home network
//  advertises its LAN IPv4 address and one or more global IPv6 addresses. The
//  IPv4 address is tried first and is the one that matters; a global IPv6
//  address is tried last and routinely fails with "The Internet connection
//  appears to be offline". So the real reason pairing failed was being
//  discarded and replaced with a misleading one, every single time.
//
//  Keeping every failure costs nothing and means the message can name each
//  address and what it said.
//

import Foundation

struct EndpointFailure {
    let endpoint: DeviceEndpoint
    let error: Error

    /// A short, plain-English reason, preferring the URLError code over
    /// Foundation's prose (which is written for web requests, not LANs).
    var shortReason: String {
        guard let urlError = LocalNetworkAccess.urlError(in: error) else {
            return error.localizedDescription
        }
        switch urlError.code {
        case .cannotConnectToHost:
            return "nothing accepted a connection on that port"
        case .timedOut:
            return "timed out"
        case .notConnectedToInternet:
            return "iOS reported no route to that address"
        case .networkConnectionLost:
            return "the connection dropped mid-request"
        case .dataNotAllowed:
            return "iOS refused to use the network for this request"
        case .secureConnectionFailed:
            return "the TLS handshake failed"
        case .serverCertificateUntrusted, .serverCertificateHasBadDate,
             .serverCertificateHasUnknownRoot, .serverCertificateNotYetValid:
            return "the certificate was rejected"
        case .cannotFindHost:
            return "that address couldn't be resolved"
        case .badServerResponse:
            return "the reply wasn't valid HTTP"
        case .cancelled:
            return "the attempt was cancelled"
        default:
            return urlError.localizedDescription
        }
    }
}

enum EndpointFailureReport {

    /// The failure worth diagnosing and leading with.
    ///
    /// Prefers the best-ranked address that is actually on the local network,
    /// because that's the one that was supposed to work at home. Falls back to
    /// the best-ranked address of any kind.
    static func primary(_ failures: [EndpointFailure]) -> EndpointFailure? {
        guard !failures.isEmpty else { return nil }
        let ranked = EndpointRanker.rank(failures.map(\.endpoint))
        for endpoint in ranked where LocalNetworkAccess.isLocalNetworkAddress(endpoint.host) {
            if let match = failures.first(where: { $0.endpoint == endpoint }) { return match }
        }
        for endpoint in ranked {
            if let match = failures.first(where: { $0.endpoint == endpoint }) { return match }
        }
        return failures.first
    }

    /// Every address and its reason, one per line, best-ranked first.
    ///
    /// This is deliberately shown to the user rather than only logged: there
    /// is no crash reporter and no server, so what the person reading the
    /// screen can tell you is the only diagnostic channel this project has.
    static func detail(_ failures: [EndpointFailure]) -> String {
        let ranked = EndpointRanker.rank(failures.map(\.endpoint))
        let ordered = ranked.compactMap { endpoint in
            failures.first(where: { $0.endpoint == endpoint })
        }
        let lines = (ordered.isEmpty ? failures : ordered).map { failure in
            "• \(failure.endpoint.authority) — \(failure.shortReason)"
        }
        return lines.joined(separator: "\n")
    }

    /// The whole message: what happened, then each address.
    static func summary(_ failures: [EndpointFailure], headline: String) -> String {
        guard !failures.isEmpty else { return headline }
        return "\(headline)\n\n\(detail(failures))"
    }
}
