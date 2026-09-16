//
//  SocketManager.swift
//  OpenLink (parent app)
//
//  Wraps the `socket.io-client-swift` SPM package (see ios/project.yml) to
//  give a live connection to the server's Socket.IO endpoint at path
//  "/socket.io", per docs/API.md "Realtime" section.
//
//  Note on naming: this file declares our own `SocketManager` class, which
//  shadows the library's `SocketIO.SocketManager` type within this module.
//  Wherever we need the library type we spell it out as `SocketIO.SocketManager`;
//  everywhere else in the app, `SocketManager` unambiguously means this class.
//

import Foundation
import SocketIO

@MainActor
final class SocketManager: ObservableObject {
    @Published private(set) var isConnected = false

    /// Fired on `device:heartbeat` (server -> parent, family room).
    var onDeviceHeartbeat: ((DeviceHeartbeatPayload) -> Void)?
    /// Fired on `request:new` (server -> parent, family room).
    var onNewRequest: ((TimeRequest) -> Void)?

    private var manager: SocketIO.SocketManager?
    private var socket: SocketIOClient?

    /// Connects (or reconnects) using the given server root and parent JWT.
    /// Per docs/API.md, the JWT is passed as `token` in the Socket.IO `auth`
    /// handshake payload; the server then places this connection in the
    /// `family:<familyId>` room automatically based on that token's identity
    /// — there is no explicit "join room" call to make from the client.
    func connect(serverBaseURLString: String, token: String) {
        disconnect()

        let trimmed = serverBaseURLString.trimmingCharacters(in: CharacterSet(charactersIn: "/ "))
        guard let url = URL(string: trimmed), !trimmed.isEmpty else { return }

        let manager = SocketIO.SocketManager(
            socketURL: url,
            config: [
                .path("/socket.io"),
                // Socket.IO v3/v4 handshake `auth` payload — requires
                // socket.io-client-swift 16.x (pinned in project.yml), which
                // targets the same protocol version as a modern Socket.IO
                // server. If a different client version ever gets pinned
                // and `.auth` isn't available, `.connectParams(["token": token])`
                // is the fallback (query-string auth) many Socket.IO servers
                // also accept.
                .auth(["token": token]),
                .log(false),
                .compress,
                .reconnects(true),
                .reconnectWait(3),
                .reconnectWaitMax(30)
            ]
        )
        self.manager = manager

        let socket = manager.defaultSocket
        self.socket = socket
        socket.handleQueue = .main

        socket.on(clientEvent: .connect) { [weak self] _, _ in
            Task { @MainActor in self?.isConnected = true }
        }
        socket.on(clientEvent: .disconnect) { [weak self] _, _ in
            Task { @MainActor in self?.isConnected = false }
        }
        socket.on(clientEvent: .error) { [weak self] _, _ in
            Task { @MainActor in self?.isConnected = false }
        }

        socket.on("device:heartbeat") { [weak self] data, _ in
            guard let payload = Self.decode(DeviceHeartbeatPayload.self, from: data) else { return }
            Task { @MainActor in self?.onDeviceHeartbeat?(payload) }
        }
        socket.on("request:new") { [weak self] data, _ in
            guard let payload = Self.decode(TimeRequest.self, from: data) else { return }
            Task { @MainActor in self?.onNewRequest?(payload) }
        }

        socket.connect()
    }

    func disconnect() {
        socket?.removeAllHandlers()
        socket?.disconnect()
        socket = nil
        manager = nil
        isConnected = false
    }

    /// Socket.IO event callbacks hand back `[Any]` (raw JSON already
    /// deserialized into Foundation objects). Round-trip through
    /// JSONSerialization -> Data so we can reuse the app's normal Codable
    /// models and date-decoding strategy instead of hand-parsing `Any`.
    private static func decode<T: Decodable>(_ type: T.Type, from items: [Any]) -> T? {
        guard let first = items.first,
              JSONSerialization.isValidJSONObject(first),
              let data = try? JSONSerialization.data(withJSONObject: first) else {
            return nil
        }
        return try? JSONCoding.decoder.decode(T.self, from: data)
    }
}
