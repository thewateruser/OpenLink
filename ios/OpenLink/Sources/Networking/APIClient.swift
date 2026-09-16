//
//  APIClient.swift
//  OpenLink (parent app)
//
//  Thin async/await REST client over URLSession. Paths, headers and JSON
//  shapes follow docs/API.md. Base URL is the user-configured self-hosted
//  server root (e.g. "https://myhouse.example.com"); "/api" is appended
//  here per "Base URL: https://<your-server>/api" in docs/API.md.
//

import Foundation

enum APIError: LocalizedError {
    case invalidURL
    case notAuthenticated
    case encoding(Error)
    case decoding(Error)
    case transport(Error)
    case server(status: Int, message: String)

    var errorDescription: String? {
        switch self {
        case .invalidURL:
            return "The server address looks invalid. Check it under Settings."
        case .notAuthenticated:
            return "You're not signed in."
        case .encoding(let error):
            return "Could not prepare the request: \(error.localizedDescription)"
        case .decoding(let error):
            return "Could not read the server's response: \(error.localizedDescription)"
        case .transport(let error):
            return "Network error: \(error.localizedDescription)"
        case .server(let status, let message):
            return "Server error (\(status)): \(message)"
        }
    }
}

/// Best-effort decode of a server error body. docs/API.md doesn't specify
/// an error envelope shape, so this tries a couple of common ones and falls
/// back to the raw response text.
private struct ServerErrorBody: Decodable {
    let error: String?
    let message: String?
    var displayMessage: String? { error ?? message }
}

@MainActor
final class APIClient: ObservableObject {
    var baseURLString: String
    var authToken: String?

    private let session: URLSession
    private let decoder = JSONCoding.decoder
    private let encoder = JSONCoding.encoder

    init(baseURLString: String, authToken: String? = nil) {
        self.baseURLString = baseURLString
        self.authToken = authToken
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 20
        self.session = URLSession(configuration: configuration)
    }

    // MARK: - Auth

    func register(email: String, password: String, familyName: String) async throws -> RegisterResponse {
        let req = try request(
            path: "/auth/register",
            method: "POST",
            body: RegisterRequest(email: email, password: password, familyName: familyName),
            requiresAuth: false
        )
        return try await send(req)
    }

    func login(email: String, password: String) async throws -> LoginResponse {
        let req = try request(
            path: "/auth/login",
            method: "POST",
            body: LoginRequest(email: email, password: password),
            requiresAuth: false
        )
        return try await send(req)
    }

    // MARK: - Pairing

    func generatePairingCode() async throws -> PairingGenerateResponse {
        let req = try request(path: "/pairing/generate", method: "POST")
        return try await send(req)
    }

    // MARK: - Devices

    func fetchDevices() async throws -> [ChildDeviceSummary] {
        let req = try request(path: "/devices", method: "GET")
        return try await send(req)
    }

    func fetchDevice(id: String) async throws -> ChildDeviceDetail {
        let req = try request(path: "/devices/\(pathEncoded(id))", method: "GET")
        return try await send(req)
    }

    func deleteDevice(id: String) async throws {
        let req = try request(path: "/devices/\(pathEncoded(id))", method: "DELETE")
        try await sendNoContent(req)
    }

    func setLock(deviceId: String, locked: Bool) async throws -> DeviceLockResult {
        let req = try request(
            path: "/devices/\(pathEncoded(deviceId))/lock",
            method: "POST",
            body: LockRequest(locked: locked)
        )
        return try await send(req)
    }

    func updatePolicy(deviceId: String, packageName: String, body: PolicyUpdateRequest) async throws -> AppPolicy {
        let req = try request(
            path: "/devices/\(pathEncoded(deviceId))/policies/\(pathEncoded(packageName))",
            method: "PUT",
            body: body
        )
        return try await send(req)
    }

    /// docs/API.md doesn't specify a response body for this endpoint beyond
    /// "replaces the device's downtime schedule". We only assert success
    /// here; callers should re-fetch the device (`fetchDevice`) afterwards
    /// to pick up the server's canonical, saved schedule.
    func updateSchedule(deviceId: String, windows: [ScheduleWindow]) async throws {
        let req = try request(
            path: "/devices/\(pathEncoded(deviceId))/schedule",
            method: "PUT",
            body: ScheduleUpdateRequest(windows: windows)
        )
        try await sendNoContent(req)
    }

    func fetchUsage(deviceId: String, date: String) async throws -> [UsageRecord] {
        let req = try request(
            path: "/devices/\(pathEncoded(deviceId))/usage",
            method: "GET",
            queryItems: [URLQueryItem(name: "date", value: date)]
        )
        return try await send(req)
    }

    // MARK: - Time requests

    /// `status` mirrors the `pending` value documented for
    /// `GET /requests?status=pending`. For "recently resolved" we also pass
    /// `approved`/`denied`, assuming the same filter accepts the other
    /// values in `TimeRequest.status` since docs/API.md doesn't enumerate
    /// them explicitly (see ios/README.md "Assumptions"). Pass `nil` for
    /// all requests unfiltered.
    func fetchRequests(status: String?) async throws -> [TimeRequest] {
        var items: [URLQueryItem] = []
        if let status {
            items.append(URLQueryItem(name: "status", value: status))
        }
        let req = try request(path: "/requests", method: "GET", queryItems: items)
        return try await send(req)
    }

    func approveRequest(id: String, grantedMinutes: Int) async throws -> TimeRequest {
        let req = try request(
            path: "/requests/\(pathEncoded(id))/approve",
            method: "POST",
            body: ApproveRequestBody(grantedMinutes: grantedMinutes)
        )
        return try await send(req)
    }

    func denyRequest(id: String, reason: String?) async throws -> TimeRequest {
        let req = try request(
            path: "/requests/\(pathEncoded(id))/deny",
            method: "POST",
            body: DenyRequestBody(reason: reason)
        )
        return try await send(req)
    }

    // MARK: - Request building

    private func pathEncoded(_ raw: String) -> String {
        raw.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? raw
    }

    private func url(path: String, queryItems: [URLQueryItem]) throws -> URL {
        let trimmedBase = baseURLString.trimmingCharacters(in: CharacterSet(charactersIn: "/ "))
        guard !trimmedBase.isEmpty,
              var components = URLComponents(string: trimmedBase + "/api" + path) else {
            throw APIError.invalidURL
        }
        if !queryItems.isEmpty {
            components.queryItems = queryItems
        }
        guard let url = components.url else { throw APIError.invalidURL }
        return url
    }

    private func baseRequest(path: String, method: String, queryItems: [URLQueryItem], requiresAuth: Bool) throws -> URLRequest {
        let target = try url(path: path, queryItems: queryItems)
        var req = URLRequest(url: target)
        req.httpMethod = method
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        if requiresAuth {
            guard let authToken else { throw APIError.notAuthenticated }
            req.setValue("Bearer \(authToken)", forHTTPHeaderField: "Authorization")
        }
        return req
    }

    /// No-body request (GET/DELETE/POST-with-no-payload).
    private func request(path: String, method: String, queryItems: [URLQueryItem] = [], requiresAuth: Bool = true) throws -> URLRequest {
        try baseRequest(path: path, method: method, queryItems: queryItems, requiresAuth: requiresAuth)
    }

    /// Request with a JSON-encodable body.
    private func request<Body: Encodable>(path: String, method: String, body: Body, queryItems: [URLQueryItem] = [], requiresAuth: Bool = true) throws -> URLRequest {
        var req = try baseRequest(path: path, method: method, queryItems: queryItems, requiresAuth: requiresAuth)
        do {
            req.httpBody = try encoder.encode(body)
        } catch {
            throw APIError.encoding(error)
        }
        return req
    }

    // MARK: - Sending

    private func send<T: Decodable>(_ request: URLRequest) async throws -> T {
        let (data, response) = try await perform(request)
        try Self.validate(response: response, data: data)
        do {
            return try decoder.decode(T.self, from: data)
        } catch {
            throw APIError.decoding(error)
        }
    }

    private func sendNoContent(_ request: URLRequest) async throws {
        let (data, response) = try await perform(request)
        try Self.validate(response: response, data: data)
    }

    private func perform(_ request: URLRequest) async throws -> (Data, URLResponse) {
        do {
            return try await session.data(for: request)
        } catch {
            throw APIError.transport(error)
        }
    }

    private static func validate(response: URLResponse, data: Data) throws {
        guard let http = response as? HTTPURLResponse else {
            throw APIError.transport(URLError(.badServerResponse))
        }
        guard (200..<300).contains(http.statusCode) else {
            let message = (try? JSONCoding.decoder.decode(ServerErrorBody.self, from: data))?.displayMessage
                ?? String(data: data, encoding: .utf8).flatMap { $0.isEmpty ? nil : $0 }
                ?? HTTPURLResponse.localizedString(forStatusCode: http.statusCode)
            throw APIError.server(status: http.statusCode, message: message)
        }
    }
}
