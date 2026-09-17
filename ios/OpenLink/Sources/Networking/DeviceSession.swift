//
//  DeviceSession.swift
//  OpenLink (parent app)
//
//  Everything the UI knows about ONE paired child device: its cached data,
//  its connection state, its event socket, and its 30s REST fallback poll.
//
//  The child device is the source of truth (docs/PROTOCOL.md); this is a
//  cache with a remote control attached. So every mutation goes out over
//  REST and the local copy is only updated from what comes back.
//

import Foundation
import Combine

@MainActor
final class DeviceSession: ObservableObject, Identifiable {
    enum ConnectionState: Equatable {
        case idle
        case connecting
        case connected(via: String)
        case failed(String)

        var isConnected: Bool {
            if case .connected = self { return true }
            return false
        }

        var describedFailure: String? {
            if case .failed(let message) = self { return message }
            return nil
        }
    }

    // MARK: - Published state

    @Published private(set) var device: PairedDevice
    @Published private(set) var connectionState: ConnectionState = .idle
    @Published private(set) var isSocketConnected = false
    @Published private(set) var isOnLocalNetwork = false

    @Published private(set) var deviceInfo: DeviceInfo?
    @Published private(set) var apps: [AppEntry] = []
    @Published private(set) var schedule: [ScheduleWindow] = []
    @Published private(set) var pendingRequests: [TimeRequest] = []
    @Published private(set) var resolvedRequests: [TimeRequest] = []

    @Published var lastError: String?

    var id: String { device.deviceId }
    var deviceId: String { device.deviceId }

    /// Fired whenever `device` changes in a way worth persisting (name,
    /// endpoint list, cached battery level). The registry owns persistence.
    var onDeviceUpdated: ((PairedDevice) -> Void)?

    // MARK: - Collaborators

    private let credentials: DeviceCredentials
    private let connection: DeviceConnection
    private var socket: EventSocket?
    private var pollTask: Task<Void, Never>?

    /// Poll cadence when the socket isn't up (PROTOCOL.md: "re-fetches over
    /// REST on foreground and every 30s whenever the socket isn't connected").
    private static let pollInterval: TimeInterval = 30

    init(device: PairedDevice, credentials: DeviceCredentials) throws {
        self.device = device
        self.credentials = credentials
        self.connection = try DeviceConnection(
            deviceId: device.deviceId,
            credentials: credentials,
            endpoints: device.endpoints
        )
    }

    // MARK: - Lifecycle

    func start() {
        guard pollTask == nil else { return }
        startSocket()
        pollTask = Task { [weak self] in
            await self?.pollLoop()
        }
        Task { await refresh() }
    }

    func stop() {
        pollTask?.cancel()
        pollTask = nil
        let socket = self.socket
        self.socket = nil
        Task { await socket?.stop() }
        isSocketConnected = false
    }

    /// Full teardown, for unpairing.
    func shutdown() async {
        stop()
        await connection.invalidate()
    }

    private func startSocket() {
        guard socket == nil else { return }
        let socket = EventSocket(
            connection: connection,
            onEvent: { [weak self] event in
                Task { @MainActor in self?.handle(event) }
            },
            onConnectionChange: { [weak self] connected in
                Task { @MainActor in self?.isSocketConnected = connected }
            }
        )
        self.socket = socket
        Task { await socket.start() }
    }

    private func pollLoop() async {
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: UInt64(Self.pollInterval * 1_000_000_000))
            if Task.isCancelled { return }
            // The socket is a convenience; when it's down, REST keeps
            // everything current. When it's up, nothing here is needed.
            if !isSocketConnected {
                await refresh()
            }
        }
    }

    /// Called when the app returns to the foreground.
    func refreshOnForeground() async {
        await refresh()
        if !isSocketConnected {
            startSocket()
        }
    }

    // MARK: - Fetching

    func refresh() async {
        if !connectionState.isConnected {
            connectionState = .connecting
        }
        do {
            // The endpoint race probes with `GET /device`, so when we're not
            // yet connected its result IS the device body — no need to ask
            // twice.
            let info: DeviceInfo
            if await connection.activeEndpoint == nil {
                info = try await connection.connect().info
            } else {
                info = try await connection.fetchDevice()
            }
            apply(info)

            // Endpoints can change between these calls (that's the point of
            // learning), so fetch the rest after the device is confirmed.
            async let appsTask = connection.fetchApps()
            async let scheduleTask = connection.fetchSchedule()
            async let requestsTask = connection.fetchRequests(status: nil)

            let (fetchedApps, fetchedSchedule, fetchedRequests) = try await (appsTask, scheduleTask, requestsTask)

            apps = fetchedApps.sorted {
                $0.appName.localizedCaseInsensitiveCompare($1.appName) == .orderedAscending
            }
            schedule = fetchedSchedule
            applyRequests(fetchedRequests)

            if let active = await connection.activeEndpoint {
                device.markSucceeded(active)
                connectionState = .connected(via: active.authority)
            } else {
                connectionState = .connected(via: "unknown")
            }
            persist()
            lastError = nil
        } catch {
            connectionState = .failed(error.localizedDescription)
            lastError = error.localizedDescription
        }
    }

    /// Applies a `GET /device` body, including the endpoint learning that
    /// PROTOCOL.md hangs the whole away-from-home story on.
    private func apply(_ info: DeviceInfo) {
        deviceInfo = info
        device.deviceName = info.deviceName.isEmpty ? device.deviceName : info.deviceName
        device.platform = info.platform ?? device.platform
        device.appVersion = info.appVersion ?? device.appVersion
        device.batteryLevel = info.batteryLevel ?? device.batteryLevel
        applyLearnedEndpoints(info.endpoints)
    }

    /// Merges endpoints the child reports about itself into the stored list.
    ///
    /// This is the mechanism that makes "paired at home, works away from
    /// home" happen with no configuration: the first time the app connects
    /// while the overlay network is up, the child's Tailscale/WireGuard
    /// address arrives in this array and is persisted alongside the LAN one.
    private func applyLearnedEndpoints(_ raw: [String]) {
        let learned = DeviceEndpoint.parseList(raw, source: .learned)
        guard !learned.isEmpty else { return }
        if device.merge(endpoints: learned) {
            let updated = device.endpoints
            Task { await connection.setEndpoints(updated) }
            persist()
        }
    }

    /// Feeds a live Bonjour sighting in. Also used to show "on this Wi-Fi".
    func applyBonjourEndpoint(_ endpoint: DeviceEndpoint) {
        isOnLocalNetwork = true
        let wasKnown = device.endpoints.contains(endpoint)
        device.merge(endpoints: [endpoint])
        let updated = device.endpoints
        Task { await connection.setEndpoints(updated) }
        persist()

        // A brand-new LAN address for a device we're failing to reach almost
        // certainly means its DHCP lease changed. Re-race immediately.
        if !wasKnown, !connectionState.isConnected {
            Task {
                await connection.resetActiveEndpoint()
                await refresh()
            }
        }
    }

    func setLocalNetworkPresence(_ present: Bool) {
        isOnLocalNetwork = present
    }

    private func applyRequests(_ all: [TimeRequest]) {
        pendingRequests = all
            .filter { $0.status == .pending }
            .sorted { $0.createdAt > $1.createdAt }
        resolvedRequests = Array(
            all.filter { $0.status != .pending }
                .sorted { ($0.respondedAt ?? $0.createdAt) > ($1.respondedAt ?? $1.createdAt) }
                .prefix(20)
        )
    }

    private func persist() {
        onDeviceUpdated?(device)
    }

    // MARK: - Commands

    func updatePolicy(packageName: String, body: PolicyUpdateRequest) async throws {
        let updated = try await connection.updatePolicy(packageName: packageName, body: body)
        if let index = apps.firstIndex(where: { $0.packageName == packageName }) {
            apps[index] = apps[index].applying(updated)
        }
    }

    func saveSchedule(_ windows: [ScheduleWindow]) async throws {
        schedule = try await connection.updateSchedule(windows)
    }

    func approve(requestId: String, grantedMinutes: Int) async throws {
        let updated = try await connection.approveRequest(id: requestId, grantedMinutes: grantedMinutes)
        applyUpdatedRequest(updated)
    }

    func deny(requestId: String, reason: String?) async throws {
        let updated = try await connection.denyRequest(id: requestId, reason: reason)
        applyUpdatedRequest(updated)
    }

    private func applyUpdatedRequest(_ request: TimeRequest) {
        pendingRequests.removeAll { $0.id == request.id }
        resolvedRequests.removeAll { $0.id == request.id }
        if request.status == .pending {
            pendingRequests.insert(request, at: 0)
        } else {
            resolvedRequests.insert(request, at: 0)
            resolvedRequests = Array(resolvedRequests.prefix(20))
        }
    }

    // MARK: - Endpoints (manual escape hatch)

    func addManualEndpoint(_ raw: String) throws {
        guard let endpoint = DeviceEndpoint.parse(raw, source: .manual) else {
            throw DeviceConnectionError.invalidEndpoint(raw)
        }
        device.merge(endpoints: [endpoint])
        let updated = device.endpoints
        Task {
            await connection.setEndpoints(updated)
            await connection.resetActiveEndpoint()
            await refresh()
        }
        persist()
    }

    func removeEndpoint(_ endpoint: DeviceEndpoint) {
        device.endpoints.removeAll { $0 == endpoint }
        let updated = device.endpoints
        Task { await connection.setEndpoints(updated) }
        persist()
    }

    /// Forces a fresh endpoint race, e.g. from a "Reconnect" button.
    func reconnect() {
        Task {
            await connection.resetActiveEndpoint()
            await socket?.reconnect()
            await refresh()
        }
    }

    // MARK: - Unpairing

    /// `DELETE /pair` with our own parentId, best-effort: if the device can't
    /// be reached we still forget it locally, because the alternative is a
    /// device the user can never remove.
    func unpairRemotely() async {
        do {
            try await connection.unpair(parentId: credentials.parentId)
        } catch {
            lastError = "Removed locally, but the child device couldn't be told: \(error.localizedDescription)"
        }
    }

    // MARK: - Events

    private func handle(_ event: DeviceEvent) {
        switch event {
        case .requestNew(let request):
            if !pendingRequests.contains(where: { $0.id == request.id }) {
                pendingRequests.insert(request, at: 0)
            }
            LocalNotificationManager.shared.notifyNewRequest(request, deviceName: device.deviceName)

        case .usageUpdate(let payload):
            // `payload.date` is the CHILD's local date, which can differ from
            // this phone's by a day either side of midnight. Since this event
            // is a live tick (~1/min) it always describes the child's
            // "today" — the same thing `AppEntry.todayMinutes` means — so it
            // is applied regardless of what date this phone thinks it is.
            if let index = apps.firstIndex(where: { $0.packageName == payload.packageName }) {
                let existing = apps[index]
                apps[index] = AppEntry(
                    packageName: existing.packageName,
                    appName: existing.appName,
                    isSystemApp: existing.isSystemApp,
                    policy: existing.policy,
                    todayMinutes: payload.minutesUsed
                )
            }

        case .policyUpdate(let payload):
            for (packageName, policy) in payload.policies {
                if let index = apps.firstIndex(where: { $0.packageName == packageName }) {
                    apps[index] = apps[index].applying(policy)
                }
            }
            if !payload.schedule.isEmpty || !schedule.isEmpty {
                schedule = payload.schedule
            }

        case .deviceState(let payload):
            if let battery = payload.batteryLevel {
                device.batteryLevel = battery
            }
            applyLearnedEndpoints(payload.endpoints)
            persist()

        case .unknown:
            break
        }
    }
}
