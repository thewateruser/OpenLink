//
//  DeviceConnection.swift
//  OpenLink (parent app)
//
//  The REST client for ONE paired child device: pinned TLS, bearer token,
//  and the endpoint race that decides which of the device's addresses to
//  actually dial.
//
//  This replaces the old server-oriented APIClient. The shape is the same —
//  URLSession + async/await, one method per route — but the base URL is
//  resolved dynamically per connection instead of being a user-typed setting,
//  and every route is one of the child-served routes in docs/PROTOCOL.md.
//
//  An `actor` so the mutable bits (the currently-active endpoint, the
//  in-flight connection attempt) are safe to touch from the socket task, the
//  poll loop and the UI at once.
//

import Foundation

enum DeviceConnectionError: LocalizedError {
    case noEndpoints
    case invalidEndpoint(String)
    case unreachable(underlying: Error?)
    case pinningRejected(PinningError)
    case unauthorized
    case http(status: Int, message: String)
    case decoding(Error)
    case encoding(Error)

    var errorDescription: String? {
        switch self {
        case .noEndpoints:
            return "No known addresses for this device. Add one manually, or re-pair it."
        case .invalidEndpoint(let raw):
            return "“\(raw)” isn't a usable address."
        case .unreachable(let underlying):
            if let underlying {
                return "Couldn't reach the device: \(underlying.localizedDescription)"
            }
            return "Couldn't reach the device on any known address."
        case .pinningRejected(let error):
            return error.localizedDescription
        case .unauthorized:
            return "This device rejected our pairing token. It may have been unpaired from the child device — scan a new QR code to pair again."
        case .http(let status, let message):
            return "The device returned an error (\(status)): \(message)"
        case .decoding(let error):
            return "Couldn't read the device's response: \(error.localizedDescription)"
        case .encoding(let error):
            return "Couldn't prepare the request: \(error.localizedDescription)"
        }
    }
}

/// Best-effort decode of an error body. PROTOCOL.md doesn't define an error
/// envelope, so try the two obvious shapes and fall back to raw text.
private struct RemoteErrorBody: Decodable {
    let error: String?
    let message: String?
    var displayMessage: String? { error ?? message }
}

actor DeviceConnection {
    /// How long a single endpoint probe gets before it's written off.
    private static let probeTimeout: TimeInterval = 4
    /// Head start the highest-ranked endpoint gets before the next one is
    /// dialed in parallel. Short enough that a dead LAN address barely
    /// delays the overlay fallback; long enough that we don't storm every
    /// address on every connect.
    private static let hedgeDelay: TimeInterval = 0.6
    /// Normal per-request timeout once an endpoint has been chosen.
    private static let requestTimeout: TimeInterval = 15

    let deviceId: String

    private let transport: PinnedTransport
    private let token: String
    private var endpoints: [DeviceEndpoint]
    private(set) var activeEndpoint: DeviceEndpoint?
    private var connectTask: Task<(DeviceEndpoint, DeviceInfo), Error>?

    private let decoder = JSONCoding.decoder
    private let encoder = JSONCoding.encoder

    /// - Throws: if the stored fingerprint is malformed. A device whose pin
    ///   can't be read is unusable by design — we never fall back to
    ///   unpinned TLS.
    init(deviceId: String, credentials: DeviceCredentials, endpoints: [DeviceEndpoint]) throws {
        guard let fingerprint = credentials.pinnedFingerprintBytes else {
            // Never degrade to unpinned TLS. A device whose pin can't be read
            // is simply unusable until it's re-paired.
            throw PinningError.malformedPinnedFingerprint
        }
        self.deviceId = deviceId
        self.token = credentials.parentToken
        self.endpoints = endpoints
        self.transport = PinnedTransport(pinnedFingerprint: fingerprint, requestTimeout: Self.requestTimeout)
    }

    // MARK: - Endpoint management

    func setEndpoints(_ newEndpoints: [DeviceEndpoint]) {
        endpoints = newEndpoints
        // If the address we're pinned to is no longer in the list, drop it so
        // the next request re-races.
        if let active = activeEndpoint, !newEndpoints.contains(active) {
            activeEndpoint = nil
        }
    }

    func knownEndpoints() -> [DeviceEndpoint] { endpoints }

    /// Forgets the current address, forcing the next call to race again.
    /// Used when Bonjour reports the device has appeared on this Wi-Fi.
    func resetActiveEndpoint() {
        activeEndpoint = nil
    }

    func invalidate() {
        connectTask?.cancel()
        connectTask = nil
        activeEndpoint = nil
        transport.invalidate()
    }

    // MARK: - Connecting

    /// Races the known endpoints and returns the winner plus the `GET /device`
    /// body it answered with (which the caller feeds back into endpoint
    /// learning).
    @discardableResult
    func connect() async throws -> (endpoint: DeviceEndpoint, info: DeviceInfo) {
        // Coalesce concurrent attempts: the poll loop, the socket and a
        // pull-to-refresh can all land here at the same moment.
        if let existing = connectTask {
            return try await existing.value
        }
        let task = Task<(DeviceEndpoint, DeviceInfo), Error> { [self] in
            try await race()
        }
        connectTask = task
        defer { connectTask = nil }
        let result = try await task.value
        activeEndpoint = result.0
        return (result.0, result.1)
    }

    /// Hedged race: dial the best endpoint immediately, and start each
    /// subsequent one after a short stagger rather than all at once. First
    /// endpoint to complete a pinned TLS handshake *and* an authenticated
    /// `GET /device` wins; the rest are cancelled.
    ///
    /// The stagger is what makes this both "in order of expected latency"
    /// and a race: a working LAN address almost always finishes inside its
    /// head start, so the overlay address is usually never dialed at all.
    private func race() async throws -> (DeviceEndpoint, DeviceInfo) {
        let ordered = EndpointRanker.rank(endpoints)
        guard !ordered.isEmpty else { throw DeviceConnectionError.noEndpoints }

        return try await withThrowingTaskGroup(of: (DeviceEndpoint, DeviceInfo).self) { group in
            for (index, endpoint) in ordered.enumerated() {
                group.addTask { [self] in
                    if index > 0 {
                        let delay = Self.hedgeDelay * Double(index)
                        try await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
                    }
                    let info = try await probe(endpoint)
                    return (endpoint, info)
                }
            }

            var lastError: Error?
            while let result = await group.nextResult() {
                switch result {
                case .success(let winner):
                    group.cancelAll()
                    return winner
                case .failure(let error):
                    // A losing task cancelled by an earlier success reports
                    // CancellationError; that isn't a real failure reason.
                    if !(error is CancellationError) {
                        lastError = error
                    }
                }
            }

            if let pinningFailure = transport.lastPinningFailure {
                throw DeviceConnectionError.pinningRejected(pinningFailure)
            }
            if let connectionError = lastError as? DeviceConnectionError {
                throw connectionError
            }
            throw DeviceConnectionError.unreachable(underlying: lastError)
        }
    }

    /// A single endpoint attempt: authenticated `GET /device` with a short
    /// timeout. Doubles as the liveness check and the source of learned
    /// endpoints.
    private func probe(_ endpoint: DeviceEndpoint) async throws -> DeviceInfo {
        let request = try makeRequest(
            endpoint: endpoint,
            path: "/device",
            method: "GET",
            timeout: Self.probeTimeout
        )
        return try await perform(request)
    }

    // MARK: - Routes (docs/PROTOCOL.md)

    /// `GET /device`. Callers should merge `info.endpoints` into the stored
    /// endpoint list — see `DeviceSession.applyLearnedEndpoints`.
    func fetchDevice() async throws -> DeviceInfo {
        try await send(path: "/device", method: "GET")
    }

    /// `DELETE /pair`. `parentId == nil` unpairs us; a value revokes that
    /// other parent.
    func unpair(parentId: String?) async throws {
        try await sendNoContent(path: "/pair", method: "DELETE", body: UnpairRequestBody(parentId: parentId))
    }

    /// `GET /apps`.
    func fetchApps() async throws -> [AppEntry] {
        try await send(path: "/apps", method: "GET")
    }

    /// `PUT /policies/{packageName}` -> the updated policy.
    func updatePolicy(packageName: String, body: PolicyUpdateRequest) async throws -> AppPolicy {
        try await send(path: "/policies/\(Self.pathEncoded(packageName))", method: "PUT", body: body)
    }

    /// `GET /schedule` -> `{ windows }`.
    func fetchSchedule() async throws -> [ScheduleWindow] {
        let envelope: ScheduleEnvelope = try await send(path: "/schedule", method: "GET")
        return envelope.windows
    }

    /// `PUT /schedule { windows }` -> `{ windows }`. Replaces the whole set.
    func updateSchedule(_ windows: [ScheduleWindow]) async throws -> [ScheduleWindow] {
        let envelope: ScheduleEnvelope = try await send(
            path: "/schedule",
            method: "PUT",
            body: ScheduleEnvelope(windows: windows)
        )
        return envelope.windows
    }

    /// `GET /usage?date=YYYY-MM-DD`. Omit `date` for today.
    func fetchUsage(date: String? = nil) async throws -> UsageResponse {
        let query = date.map { [URLQueryItem(name: "date", value: $0)] } ?? []
        return try await send(path: "/usage", method: "GET", query: query)
    }

    /// `GET /requests?status=pending|approved|denied`. Omit for all.
    func fetchRequests(status: TimeRequestStatus? = nil) async throws -> [TimeRequest] {
        let query = status.map { [URLQueryItem(name: "status", value: $0.rawValue)] } ?? []
        return try await send(path: "/requests", method: "GET", query: query)
    }

    /// `POST /requests/{id}/approve { grantedMinutes }`.
    func approveRequest(id: String, grantedMinutes: Int) async throws -> TimeRequest {
        try await send(
            path: "/requests/\(Self.pathEncoded(id))/approve",
            method: "POST",
            body: ApproveRequestBody(grantedMinutes: grantedMinutes)
        )
    }

    /// `POST /requests/{id}/deny { reason? }`.
    func denyRequest(id: String, reason: String?) async throws -> TimeRequest {
        try await send(
            path: "/requests/\(Self.pathEncoded(id))/deny",
            method: "POST",
            body: DenyRequestBody(reason: reason)
        )
    }

    // MARK: - WebSocket

    /// Builds the `/events` WebSocket task on the *same pinned session*, with
    /// the same bearer token in the `Authorization` header.
    func makeEventsWebSocketTask() async throws -> URLSessionWebSocketTask {
        let endpoint: DeviceEndpoint
        if let active = activeEndpoint {
            endpoint = active
        } else {
            endpoint = try await connect().endpoint
        }
        guard let base = endpoint.webSocketBaseURL,
              var components = URLComponents(url: base, resolvingAgainstBaseURL: false) else {
            throw DeviceConnectionError.invalidEndpoint(endpoint.authority)
        }
        components.path = "/events"
        guard let url = components.url else {
            throw DeviceConnectionError.invalidEndpoint(endpoint.authority)
        }

        var request = URLRequest(url: url)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.timeoutInterval = Self.requestTimeout
        return transport.session.webSocketTask(with: request)
    }

    /// Surfaced so the socket loop can report a pin rejection properly
    /// instead of a bare "cancelled".
    func pinningFailure() -> PinningError? {
        transport.lastPinningFailure
    }

    // MARK: - Request plumbing

    private static func pathEncoded(_ raw: String) -> String {
        raw.addingPercentEncoding(withAllowedCharacters: .alphanumerics.union(CharacterSet(charactersIn: "-._~"))) ?? raw
    }

    private func makeRequest(
        endpoint: DeviceEndpoint,
        path: String,
        method: String,
        query: [URLQueryItem] = [],
        timeout: TimeInterval = DeviceConnection.requestTimeout
    ) throws -> URLRequest {
        guard let base = endpoint.baseURL,
              var components = URLComponents(url: base, resolvingAgainstBaseURL: false) else {
            throw DeviceConnectionError.invalidEndpoint(endpoint.authority)
        }
        components.path = path
        if !query.isEmpty { components.queryItems = query }
        guard let url = components.url else {
            throw DeviceConnectionError.invalidEndpoint(endpoint.authority)
        }

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = timeout
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        // Every route but POST /pair is authenticated, and this client never
        // performs pairing, so the token is unconditional here.
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        return request
    }

    /// Resolves an endpoint (racing if needed), issues the request, and on a
    /// transport failure re-races once — the common case being "we moved off
    /// the home Wi-Fi and the LAN address we were using just died".
    private func send<T: Decodable>(
        path: String,
        method: String,
        query: [URLQueryItem] = []
    ) async throws -> T {
        let data = try await sendRaw(path: path, method: method, query: query, body: nil)
        return try decode(data)
    }

    private func send<T: Decodable, Body: Encodable>(
        path: String,
        method: String,
        body: Body,
        query: [URLQueryItem] = []
    ) async throws -> T {
        let encoded = try encodeBody(body)
        let data = try await sendRaw(path: path, method: method, query: query, body: encoded)
        return try decode(data)
    }

    private func sendNoContent<Body: Encodable>(
        path: String,
        method: String,
        body: Body,
        query: [URLQueryItem] = []
    ) async throws {
        let encoded = try encodeBody(body)
        _ = try await sendRaw(path: path, method: method, query: query, body: encoded)
    }

    private func sendRaw(
        path: String,
        method: String,
        query: [URLQueryItem],
        body: Data?
    ) async throws -> Data {
        var attemptedReconnect = false

        while true {
            let endpoint: DeviceEndpoint
            if let active = activeEndpoint {
                endpoint = active
            } else {
                endpoint = try await connect().endpoint
                attemptedReconnect = true
            }

            var request = try makeRequest(endpoint: endpoint, path: path, method: method, query: query)
            request.httpBody = body

            do {
                let (data, _) = try await performRaw(request)
                return data
            } catch DeviceConnectionError.unreachable(let underlying) {
                // The address we had stopped working. Drop it and re-race
                // once; if that fails too, the error is genuine.
                activeEndpoint = nil
                if attemptedReconnect {
                    throw DeviceConnectionError.unreachable(underlying: underlying)
                }
                attemptedReconnect = true
            }
        }
    }

    private func perform<T: Decodable>(_ request: URLRequest) async throws -> T {
        let (data, _) = try await performRaw(request)
        return try decode(data)
    }

    private func performRaw(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await transport.session.data(for: request)
        } catch {
            // A pin rejection surfaces as a cancelled auth challenge, i.e.
            // NSURLErrorCancelled — meaningless on its own, so translate it.
            if let pinningFailure = transport.lastPinningFailure {
                throw DeviceConnectionError.pinningRejected(pinningFailure)
            }
            throw DeviceConnectionError.unreachable(underlying: error)
        }

        guard let http = response as? HTTPURLResponse else {
            throw DeviceConnectionError.unreachable(underlying: URLError(.badServerResponse))
        }
        guard (200..<300).contains(http.statusCode) else {
            if http.statusCode == 401 || http.statusCode == 403 {
                throw DeviceConnectionError.unauthorized
            }
            let message = (try? JSONCoding.decoder.decode(RemoteErrorBody.self, from: data))?.displayMessage
                ?? String(data: data, encoding: .utf8).flatMap { $0.isEmpty ? nil : $0 }
                ?? HTTPURLResponse.localizedString(forStatusCode: http.statusCode)
            throw DeviceConnectionError.http(status: http.statusCode, message: message)
        }
        return (data, http)
    }

    private func encodeBody<Body: Encodable>(_ body: Body) throws -> Data {
        do {
            return try encoder.encode(body)
        } catch {
            throw DeviceConnectionError.encoding(error)
        }
    }

    private func decode<T: Decodable>(_ data: Data) throws -> T {
        // 204 No Content with a non-Void expectation shouldn't happen on the
        // routes we call, but an empty body decoding into an optional should
        // not crash.
        do {
            return try decoder.decode(T.self, from: data)
        } catch {
            throw DeviceConnectionError.decoding(error)
        }
    }
}
