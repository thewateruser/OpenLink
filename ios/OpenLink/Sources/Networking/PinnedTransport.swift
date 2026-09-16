//
//  PinnedTransport.swift
//  OpenLink (parent app)
//
//  A URLSession whose TLS trust comes from one pinned certificate
//  fingerprint. One of these per paired device; both the REST calls and the
//  `/events` WebSocket task are created from it, so they share exactly the
//  same trust decision.
//

import Foundation

final class PinnedTransport {
    let session: URLSession
    private let pinningDelegate: CertificatePinningDelegate

    /// - Parameters:
    ///   - pinnedFingerprint: 32 raw SHA-256 bytes of the child's cert DER.
    ///   - requestTimeout: default per-request timeout; individual requests
    ///     override this (endpoint probes use a much shorter one).
    init(pinnedFingerprint: Data, requestTimeout: TimeInterval = 15) {
        let delegate = CertificatePinningDelegate(pinnedFingerprint: pinnedFingerprint)
        self.pinningDelegate = delegate

        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = requestTimeout
        configuration.timeoutIntervalForResource = 60
        // The child is on a LAN or an overlay network, never behind a proxy
        // or a caching layer we should honour.
        configuration.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        configuration.httpShouldSetCookies = false
        configuration.httpCookieAcceptPolicy = .never
        configuration.waitsForConnectivity = false
        // Allow cellular: away-from-home access over an overlay network is a
        // first-class case, not an edge case.
        configuration.allowsCellularAccess = true

        // `delegateQueue: nil` gives URLSession its own serial queue. The
        // delegate is immutable apart from one lock-guarded field, so this
        // is safe.
        self.session = URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
    }

    /// The reason the last TLS handshake was refused, if it was refused by
    /// us. URLSession reports a cancelled auth challenge as a generic
    /// `NSURLErrorCancelled`, which is useless to show a user.
    var lastPinningFailure: PinningError? { pinningDelegate.lastPinningFailure }

    /// URLSession holds a strong reference to its delegate until invalidated,
    /// so this must be called when a device is unpaired.
    func invalidate() {
        session.invalidateAndCancel()
    }

    deinit {
        session.invalidateAndCancel()
    }
}
