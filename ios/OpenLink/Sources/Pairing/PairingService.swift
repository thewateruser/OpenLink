//
//  PairingService.swift
//  OpenLink (parent app)
//
//  The pairing handshake from docs/PROTOCOL.md, step for step:
//
//    1. Dial an endpoint from the QR's `ep=` over TLS, pinning the QR's `fp=`
//       and aborting on any mismatch. Because `fp` arrived out-of-band (off
//       the child's screen), this is what defeats an active MITM sitting on
//       the same Wi-Fi.
//    2. Generate a random 32-byte parentId.
//    3. proof = base64url(HMAC-SHA256(key: psk, msg: "openlink-pair-v1" || parentId))
//    4. POST /pair { parentId, parentName, proof }
//    5. Store parentToken + fp in the Keychain, keyed by deviceId.
//
//  NOTE ON ENDPOINT ORDER: unlike ordinary connections, pairing attempts are
//  made STRICTLY SEQUENTIALLY, never raced in parallel. The psk is single-use
//  and `POST /pair` is rate-limited to 5 attempts/minute; firing three
//  parallel attempts would burn most of that budget and could race the child
//  into invalidating the psk while another attempt is still in flight.
//

import Foundation

enum PairingError: LocalizedError {
    case allEndpointsFailed(underlying: Error?)
    case certificateRejected(PinningError)
    case proofRejected
    case rateLimited
    case deviceIdMismatch(expected: String, received: String)
    case alreadyPaired(deviceName: String)
    case keychain(Error)

    var errorDescription: String? {
        switch self {
        case .allEndpointsFailed(let underlying):
            let detail = underlying.map { ": \($0.localizedDescription)" } ?? "."
            return "Couldn't reach the child device at any address in the QR code\(detail) Make sure both devices are on the same Wi-Fi."
        case .certificateRejected(let error):
            return error.localizedDescription
        case .proofRejected:
            return "The child device rejected this pairing code. It may have expired — show a fresh QR code and scan it again."
        case .rateLimited:
            return "Too many pairing attempts. Wait a minute, show a fresh QR code, and try again."
        case .deviceIdMismatch(let expected, let received):
            return "The device answered as \(received) but the QR code said \(expected). Refusing to pair."
        case .alreadyPaired(let deviceName):
            return "“\(deviceName)” is already paired. Remove it first if you want to pair it again."
        case .keychain(let error):
            return "Paired, but the credentials couldn't be stored securely: \(error.localizedDescription)"
        }
    }
}

struct PairingOutcome {
    let device: PairedDevice
    let credentials: DeviceCredentials
}

enum PairingService {
    /// The domain-separation prefix from PROTOCOL.md.
    static let proofContext = "openlink-pair-v1"
    /// Pairing happens with both devices in hand, so a short timeout is right:
    /// fail over to the next address quickly rather than making the user wait.
    private static let attemptTimeout: TimeInterval = 6

    /// Computes `base64url(HMAC-SHA256(key: psk, msg: "openlink-pair-v1" || parentId))`.
    ///
    /// AMBIGUITY (see ios/README.md): PROTOCOL.md writes the message as
    /// `"openlink-pair-v1" || parentId` without saying whether `parentId`
    /// contributes its 32 raw bytes or the UTF-8 of its base64url text. We use
    /// the **UTF-8 bytes of the base64url string**, because that is literally
    /// the value that travels in the JSON body and therefore the only form
    /// the child is guaranteed to have when it recomputes the HMAC. The
    /// Android side must agree; if it hashes raw bytes instead, change this
    /// one line (`Data(parentIdBase64URL.utf8)` -> the decoded bytes).
    static func proof(psk: Data, parentIdBase64URL: String) -> String {
        var message = Data(proofContext.utf8)
        message.append(Data(parentIdBase64URL.utf8))
        return Base64URL.encode(CryptoUtil.hmacSHA256(key: psk, message: message))
    }

    /// Runs the full handshake and returns what should be persisted.
    /// Does not itself write to the Keychain — `DeviceRegistry` owns that, so
    /// storing and adding the device to the UI happen together.
    static func pair(with uri: PairingURI, parentName: String) async throws -> PairingOutcome {
        // Step 1 setup: a session pinned to the fingerprint from the QR. If
        // the device on the other end isn't holding that exact certificate's
        // private key, nothing below will ever complete a handshake.
        let transport = PinnedTransport(pinnedFingerprint: uri.fingerprintBytes, requestTimeout: attemptTimeout)
        defer { transport.invalidate() }

        // Step 2.
        let parentId = Base64URL.encode(CryptoUtil.randomBytes(count: 32))
        // Step 3.
        let body = PairRequestBody(
            parentId: parentId,
            parentName: parentName,
            proof: proof(psk: uri.psk, parentIdBase64URL: parentId)
        )
        let encoded = try JSONCoding.encoder.encode(body)

        // Step 4, one endpoint at a time.
        var lastError: Error?
        for endpoint in EndpointRanker.rank(uri.endpoints) {
            do {
                let response = try await postPair(encoded, to: endpoint, using: transport)

                guard response.deviceId == uri.deviceId else {
                    // The QR named one device and something else answered.
                    // This shouldn't be reachable — it would have to be
                    // holding the pinned certificate — but refuse loudly.
                    throw PairingError.deviceIdMismatch(expected: uri.deviceId, received: response.deviceId)
                }

                // Step 5's inputs.
                var device = PairedDevice(
                    deviceId: response.deviceId,
                    deviceName: response.deviceName.isEmpty ? uri.deviceName : response.deviceName,
                    endpoints: uri.endpoints
                )
                // The pair response already carries the device's full address
                // list — endpoint learning starts here, not at the first
                // GET /device.
                device.merge(endpoints: DeviceEndpoint.parseList(response.endpoints, source: .learned))
                device.markSucceeded(endpoint)

                let credentials = DeviceCredentials(
                    parentToken: response.parentToken,
                    fingerprintBase64URL: uri.fingerprintBase64URL,
                    parentId: parentId
                )
                return PairingOutcome(device: device, credentials: credentials)
            } catch let error as PairingError {
                // Semantic failures (bad proof, rate limit, wrong device) mean
                // the device answered — trying another address won't help.
                throw error
            } catch {
                lastError = error
            }
        }

        if let pinningFailure = transport.lastPinningFailure {
            throw PairingError.certificateRejected(pinningFailure)
        }
        throw PairingError.allEndpointsFailed(underlying: lastError)
    }

    private static func postPair(
        _ body: Data,
        to endpoint: DeviceEndpoint,
        using transport: PinnedTransport
    ) async throws -> PairResponse {
        guard let base = endpoint.baseURL,
              var components = URLComponents(url: base, resolvingAgainstBaseURL: false) else {
            throw DeviceConnectionError.invalidEndpoint(endpoint.authority)
        }
        components.path = "/pair"
        guard let url = components.url else {
            throw DeviceConnectionError.invalidEndpoint(endpoint.authority)
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = attemptTimeout
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = body
        // Deliberately no Authorization header: /pair is the only
        // unauthenticated route.

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await transport.session.data(for: request)
        } catch {
            if let pinningFailure = transport.lastPinningFailure {
                throw PairingError.certificateRejected(pinningFailure)
            }
            throw DeviceConnectionError.unreachable(underlying: error)
        }

        guard let http = response as? HTTPURLResponse else {
            throw DeviceConnectionError.unreachable(underlying: URLError(.badServerResponse))
        }
        switch http.statusCode {
        case 200..<300:
            do {
                return try JSONCoding.decoder.decode(PairResponse.self, from: data)
            } catch {
                throw DeviceConnectionError.decoding(error)
            }
        case 401, 403:
            throw PairingError.proofRejected
        case 429:
            throw PairingError.rateLimited
        default:
            let message = String(data: data, encoding: .utf8).flatMap { $0.isEmpty ? nil : $0 }
                ?? HTTPURLResponse.localizedString(forStatusCode: http.statusCode)
            throw DeviceConnectionError.http(status: http.statusCode, message: message)
        }
    }
}
