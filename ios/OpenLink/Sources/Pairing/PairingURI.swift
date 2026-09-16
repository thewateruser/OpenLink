//
//  PairingURI.swift
//  OpenLink (parent app)
//
//  Parses the QR payload from docs/PROTOCOL.md:
//
//    openlink://pair?v=1
//      &id=<deviceId>
//      &name=<url-encoded device name>
//      &fp=<base64url(SHA-256(certificate DER))>
//      &psk=<base64url(32 random bytes)>
//      &ep=<host:port,host:port,...>
//
//  `fp` is the out-of-band trust anchor for the whole device relationship, so
//  it is validated strictly here (must decode to exactly 32 bytes). `psk` is
//  the single-use, 5-minute pairing secret; same treatment.
//

import Foundation

struct PairingURI: Equatable {
    static let scheme = "openlink"
    static let host = "pair"
    static let supportedVersion = 1

    let version: Int
    let deviceId: String
    let deviceName: String
    /// base64url(SHA-256(cert DER)) — kept in its text form because that's
    /// what gets persisted; `fingerprintBytes` gives the 32 raw bytes.
    let fingerprintBase64URL: String
    /// The 32 raw bytes of the single-use pairing secret.
    let psk: Data
    let endpoints: [DeviceEndpoint]

    var fingerprintBytes: Data {
        // Guaranteed non-nil and 32 bytes by `parse`.
        Base64URL.decode(fingerprintBase64URL) ?? Data()
    }

    enum ParseError: LocalizedError {
        case notAPairingURI
        case unsupportedVersion(Int)
        case missing(field: String)
        case badFingerprint
        case badPSK
        case noEndpoints

        var errorDescription: String? {
            switch self {
            case .notAPairingURI:
                return "That isn't an OpenLink pairing code."
            case .unsupportedVersion(let version):
                return "This pairing code uses format v\(version), which this version of OpenLink doesn't understand. Update the app."
            case .missing(let field):
                return "The pairing code is missing its “\(field)” field."
            case .badFingerprint:
                return "The pairing code's certificate fingerprint is malformed. Show a fresh QR code on the child device."
            case .badPSK:
                return "The pairing code's secret is malformed. Show a fresh QR code on the child device."
            case .noEndpoints:
                return "The pairing code doesn't list any address to connect to."
            }
        }
    }

    static func parse(_ raw: String) throws -> PairingURI {
        guard let components = URLComponents(string: raw.trimmingCharacters(in: .whitespacesAndNewlines)),
              components.scheme?.lowercased() == scheme,
              components.host?.lowercased() == host else {
            throw ParseError.notAPairingURI
        }

        // URLComponents already percent-decodes query item values, so the
        // url-encoded `name` needs no extra work.
        var values: [String: String] = [:]
        for item in components.queryItems ?? [] {
            if let value = item.value { values[item.name] = value }
        }

        let version = values["v"].flatMap(Int.init) ?? 1
        guard version == supportedVersion else {
            throw ParseError.unsupportedVersion(version)
        }

        guard let deviceId = values["id"], !deviceId.isEmpty else {
            throw ParseError.missing(field: "id")
        }
        guard let fingerprint = values["fp"], !fingerprint.isEmpty else {
            throw ParseError.missing(field: "fp")
        }
        guard let fingerprintBytes = Base64URL.decode(fingerprint), fingerprintBytes.count == 32 else {
            throw ParseError.badFingerprint
        }
        guard let pskText = values["psk"], !pskText.isEmpty else {
            throw ParseError.missing(field: "psk")
        }
        guard let pskBytes = Base64URL.decode(pskText), pskBytes.count == 32 else {
            throw ParseError.badPSK
        }
        guard let endpointText = values["ep"], !endpointText.isEmpty else {
            throw ParseError.missing(field: "ep")
        }

        let endpoints = DeviceEndpoint.parseCommaSeparated(endpointText, source: .pairingQR)
        guard !endpoints.isEmpty else { throw ParseError.noEndpoints }

        return PairingURI(
            version: version,
            deviceId: deviceId,
            deviceName: values["name"] ?? "Child device",
            fingerprintBase64URL: fingerprint,
            psk: pskBytes,
            endpoints: endpoints
        )
    }
}
