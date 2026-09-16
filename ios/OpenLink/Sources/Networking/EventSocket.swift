//
//  EventSocket.swift
//  OpenLink (parent app)
//
//  The `/events` WebSocket, on Apple's own URLSessionWebSocketTask — no
//  third-party dependency. It runs over the same pinned TLS session as the
//  REST calls and authenticates with the same bearer token.
//
//  Per docs/PROTOCOL.md this is a convenience, not a requirement: everything
//  it delivers is also available over REST, and `DeviceSession` polls every
//  30s whenever this isn't connected. So the reconnect policy here is
//  deliberately unexcited — back off, keep trying, never block anything.
//

import Foundation

/// Drives one device's event stream. Callbacks are invoked on the actor's
/// executor; `DeviceSession` hops them to the main actor.
actor EventSocket {
    private let connection: DeviceConnection
    private let onEvent: @Sendable (DeviceEvent) -> Void
    private let onConnectionChange: @Sendable (Bool) -> Void

    private var task: URLSessionWebSocketTask?
    private var runLoopTask: Task<Void, Never>?
    private var isRunning = false

    /// Reconnect backoff, in seconds.
    private static let backoffSchedule: [UInt64] = [1, 2, 5, 10, 20, 30]
    /// Keepalive ping interval. The child also sends `device:state` about
    /// every 60s, but a ping from our side detects a half-open NAT mapping
    /// sooner — the common failure when a phone changes networks.
    private static let pingInterval: TimeInterval = 25

    init(
        connection: DeviceConnection,
        onEvent: @escaping @Sendable (DeviceEvent) -> Void,
        onConnectionChange: @escaping @Sendable (Bool) -> Void
    ) {
        self.connection = connection
        self.onEvent = onEvent
        self.onConnectionChange = onConnectionChange
    }

    func start() {
        guard !isRunning else { return }
        isRunning = true
        runLoopTask = Task { [weak self] in
            await self?.runLoop()
        }
    }

    func stop() {
        isRunning = false
        runLoopTask?.cancel()
        runLoopTask = nil
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        onConnectionChange(false)
    }

    /// Drop and immediately re-dial — used when the connection's chosen
    /// endpoint changes underneath us (e.g. we arrived home and Bonjour
    /// found a much better address).
    func reconnect() {
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
    }

    // MARK: - Loop

    private func runLoop() async {
        var attempt = 0
        while isRunning && !Task.isCancelled {
            do {
                try await openAndPump()
                // A clean close is still a disconnect; retry from the top of
                // the backoff schedule since the connection clearly worked.
                attempt = 0
            } catch is CancellationError {
                break
            } catch {
                attempt += 1
            }

            onConnectionChange(false)
            guard isRunning && !Task.isCancelled else { break }

            let index = min(max(attempt - 1, 0), Self.backoffSchedule.count - 1)
            let seconds = Self.backoffSchedule[index]
            try? await Task.sleep(nanoseconds: seconds * 1_000_000_000)
        }
        onConnectionChange(false)
    }

    /// Opens the socket and receives until it fails or is cancelled.
    private func openAndPump() async throws {
        let socket = try await connection.makeEventsWebSocketTask()
        task = socket
        socket.resume()

        // URLSessionWebSocketTask reports the HTTP upgrade failure (a 401
        // from the child, say) through the first `receive()`, not through
        // `resume()`, so we only claim to be connected after one successful
        // message — except that the child may be quiet for up to 60s. The
        // pragmatic compromise: treat a successful ping as "connected".
        try await socket.sendPing()
        onConnectionChange(true)

        let pinger = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: UInt64(Self.pingInterval * 1_000_000_000))
                if Task.isCancelled { return }
                guard let self else { return }
                let stillAlive = await self.ping()
                if !stillAlive { return }
            }
        }
        defer { pinger.cancel() }

        while isRunning && !Task.isCancelled {
            let message = try await socket.receive()
            handle(message)
        }
    }

    private func ping() async -> Bool {
        guard let socket = task else { return false }
        do {
            try await socket.sendPing()
            return true
        } catch {
            socket.cancel(with: .abnormalClosure, reason: nil)
            return false
        }
    }

    private func handle(_ message: URLSessionWebSocketTask.Message) {
        let data: Data?
        switch message {
        case .data(let payload):
            data = payload
        case .string(let text):
            data = text.data(using: .utf8)
        @unknown default:
            data = nil
        }
        guard let data else { return }

        do {
            let event = try JSONCoding.decoder.decode(DeviceEvent.self, from: data)
            onEvent(event)
        } catch {
            // An unparseable frame is not a reason to tear down a working
            // connection — REST polling still backstops every event type.
            #if DEBUG
            print("OpenLink: dropped unparseable event frame: \(error)")
            #endif
        }
    }
}

private extension URLSessionWebSocketTask {
    /// async/await wrapper over the callback-based `sendPing`.
    func sendPing() async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            sendPing { error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume()
                }
            }
        }
    }
}
