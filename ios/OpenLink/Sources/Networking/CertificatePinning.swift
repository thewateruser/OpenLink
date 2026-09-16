//
//  CertificatePinning.swift
//  OpenLink (parent app)
//
//  ============================================================================
//  THIS IS THE SECURITY BACKBONE OF THE APP. Read this before changing it.
//  ============================================================================
//
//  There is no server and no CA. The child device generates a long-lived
//  self-signed P-256 certificate on first run and keeps the private key in
//  the Android Keystore, non-exportable. It is reached by raw IP address
//  (a LAN address, or a Tailscale/WireGuard overlay address), never by a
//  DNS name anyone could get a WebPKI certificate for.
//
//  Consequences, all deliberate:
//
//  1. System trust evaluation ALWAYS fails here (self-signed, unknown issuer,
//     and the URL host is an IP with no matching SAN in the general case).
//     We therefore do not call `SecTrustEvaluateWithError` at all — its
//     verdict carries no information for us. Pinning *replaces* it rather
//     than supplementing it.
//
//  2. The only thing that authenticates the peer is: does SHA-256 of the
//     leaf certificate's DER encoding equal the fingerprint that arrived
//     out-of-band, in the pairing QR code, off the child's own screen?
//     That out-of-band channel is what defeats an active MITM on the same
//     Wi-Fi: an attacker would have to present the child's actual
//     certificate, whose private key never leaves the Keystore.
//
//  3. Because we pin the exact leaf, there is no notion of certificate
//     rotation or expiry checking. If the child regenerates its certificate
//     (app reinstall, Keystore wipe), every parent must re-pair by scanning
//     a fresh QR. PROTOCOL.md specifies exactly this ("the parent pins
//     exactly this fingerprint for this device, forever"), and it is the
//     safe failure mode: a changed fingerprint means "stop", never "trust
//     it anyway".
//
//  4. Hostname verification is intentionally NOT performed. Pinning a single
//     specific certificate is strictly stronger than name matching here: the
//     name we'd be checking against is an address the device told us about,
//     whereas the fingerprint came from the QR.
//
//  A pinning delegate is created per paired device, holds exactly one
//  fingerprint, and never falls back to default handling for a server-trust
//  challenge. Any mismatch cancels the connection.
//

import Foundation
import Security

enum PinningError: LocalizedError {
    case noServerTrust
    case emptyCertificateChain
    case malformedPinnedFingerprint
    case fingerprintMismatch(expected: String, presented: String)

    var errorDescription: String? {
        switch self {
        case .noServerTrust:
            return "The connection didn't present a TLS certificate."
        case .emptyCertificateChain:
            return "The device presented an empty certificate chain."
        case .malformedPinnedFingerprint:
            return "The stored certificate fingerprint for this device is unreadable, so it can't be trusted. Remove the device and pair it again."
        case .fingerprintMismatch(let expected, let presented):
            return """
            Certificate mismatch — refusing to connect. Expected \
            \(expected.prefix(12))… but the device presented \(presented.prefix(12))…. \
            Re-pair this device by scanning a new QR code if it was reinstalled.
            """
        }
    }
}

/// Validates a TLS server trust against a single pinned leaf-certificate
/// fingerprint.
///
/// Kept separate from the delegate so it can be reasoned about (and, on a
/// machine with a toolchain, unit-tested) without a live connection.
enum CertificatePinValidator {
    /// SHA-256 of the leaf certificate's DER encoding.
    static func leafFingerprint(of trust: SecTrust) throws -> Data {
        // Index 0 is the leaf. We never look further up the chain: there is
        // no chain to speak of (the certificate is self-signed) and an
        // intermediate would be irrelevant to a pin on the leaf itself.
        guard let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
              let leaf = chain.first else {
            throw PinningError.emptyCertificateChain
        }
        let der = SecCertificateCopyData(leaf) as Data
        guard !der.isEmpty else { throw PinningError.emptyCertificateChain }
        return CryptoUtil.sha256(der)
    }

    /// Throws unless the trust's leaf matches `pinnedFingerprint` (32 raw
    /// SHA-256 bytes).
    static func validate(trust: SecTrust, against pinnedFingerprint: Data) throws {
        let presented = try leafFingerprint(of: trust)
        guard CryptoUtil.constantTimeEquals(presented, pinnedFingerprint) else {
            throw PinningError.fingerprintMismatch(
                expected: Base64URL.encode(pinnedFingerprint),
                presented: Base64URL.encode(presented)
            )
        }
    }
}

/// `URLSessionDelegate` that pins one device's certificate.
///
/// Immutable after init and holds no reference back to the session or to any
/// actor, so it is safe to be called on URLSession's delegate queue.
final class CertificatePinningDelegate: NSObject, URLSessionDelegate {
    /// 32 raw bytes: SHA-256 of the child's certificate in DER form.
    private let pinnedFingerprint: Data

    /// Set when a challenge is rejected, so the caller can turn URLSession's
    /// generic `NSURLErrorCancelled` into a message that actually explains
    /// what happened.
    private let lock = NSLock()
    private var _lastPinningFailure: PinningError?
    var lastPinningFailure: PinningError? {
        lock.lock()
        defer { lock.unlock() }
        return _lastPinningFailure
    }

    /// - Parameter pinnedFingerprint: 32 raw SHA-256 bytes. Use
    ///   `init?(base64URLFingerprint:)` to build one from the QR's `fp=`.
    init(pinnedFingerprint: Data) {
        self.pinnedFingerprint = pinnedFingerprint
        super.init()
    }

    convenience init?(base64URLFingerprint: String) {
        guard let data = Base64URL.decode(base64URLFingerprint), data.count == 32 else {
            return nil
        }
        self.init(pinnedFingerprint: data)
    }

    func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        // Only server-trust challenges are ours to answer. Anything else
        // (client certs, HTTP auth) isn't part of this protocol; let the
        // system deal with it, which in practice means "decline".
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust else {
            completionHandler(.performDefaultHandling, nil)
            return
        }

        guard let trust = challenge.protectionSpace.serverTrust else {
            record(.noServerTrust)
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }

        do {
            // The whole security model, in one call. Note there is no
            // `SecTrustEvaluateWithError` here and no fallback path: either
            // the leaf is byte-for-byte the certificate the QR named, or the
            // connection dies.
            try CertificatePinValidator.validate(trust: trust, against: pinnedFingerprint)
            clearFailure()
            completionHandler(.useCredential, URLCredential(trust: trust))
        } catch let error as PinningError {
            record(error)
            completionHandler(.cancelAuthenticationChallenge, nil)
        } catch {
            record(.noServerTrust)
            completionHandler(.cancelAuthenticationChallenge, nil)
        }
    }

    private func record(_ error: PinningError) {
        lock.lock()
        _lastPinningFailure = error
        lock.unlock()
    }

    private func clearFailure() {
        lock.lock()
        _lastPinningFailure = nil
        lock.unlock()
    }
}
