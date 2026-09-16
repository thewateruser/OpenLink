//
//  DeviceRegistry.swift
//  OpenLink (parent app)
//
//  The N paired child devices. There is no account tying them together —
//  each is an independent trust relationship established by scanning one QR
//  code, with its own token, its own pinned certificate and its own endpoint
//  list. Removing one has no effect on the others.
//

import Foundation
import Combine
#if canImport(UIKit)
import UIKit
#endif

@MainActor
final class DeviceRegistry: ObservableObject {
    @Published private(set) var sessions: [DeviceSession] = []
    /// Devices whose stored credentials are missing or corrupt. They can't be
    /// connected to (we never fall back to unpinned TLS) and must be re-paired.
    @Published private(set) var brokenDeviceIds: [String] = []

    private let deviceStore: PairedDeviceStore
    private let credentialStore: DeviceCredentialStore
    private var cancellables = Set<AnyCancellable>()

    var isEmpty: Bool { sessions.isEmpty && brokenDeviceIds.isEmpty }

    init(
        deviceStore: PairedDeviceStore = PairedDeviceStore(),
        credentialStore: DeviceCredentialStore = DeviceCredentialStore()
    ) {
        self.deviceStore = deviceStore
        self.credentialStore = credentialStore
        load()
    }

    // MARK: - Loading

    private func load() {
        for device in deviceStore.load() {
            guard let credentials = credentialStore.load(for: device.deviceId) else {
                // Metadata without credentials: the Keychain item was lost
                // (restored-from-backup phone, since we store ThisDeviceOnly).
                brokenDeviceIds.append(device.deviceId)
                continue
            }
            attach(device: device, credentials: credentials)
        }
    }

    private func attach(device: PairedDevice, credentials: DeviceCredentials) {
        guard let session = try? DeviceSession(device: device, credentials: credentials) else {
            brokenDeviceIds.append(device.deviceId)
            return
        }
        session.onDeviceUpdated = { [weak self] updated in
            self?.persist(updated)
        }
        // A DeviceSession is a nested ObservableObject, so views observing the
        // registry (the devices list) need its changes forwarded.
        session.objectWillChange
            .sink { [weak self] _ in self?.objectWillChange.send() }
            .store(in: &cancellables)
        sessions.append(session)
    }

    func session(for deviceId: String) -> DeviceSession? {
        sessions.first { $0.deviceId == deviceId }
    }

    func contains(deviceId: String) -> Bool {
        sessions.contains { $0.deviceId == deviceId } || brokenDeviceIds.contains(deviceId)
    }

    // MARK: - Lifecycle

    func startAll() {
        for session in sessions {
            session.start()
        }
    }

    func stopAll() {
        for session in sessions {
            session.stop()
        }
    }

    func refreshAllOnForeground() async {
        for session in sessions {
            await session.refreshOnForeground()
        }
    }

    // MARK: - Pairing

    /// Runs the handshake, persists the result, and brings the new device
    /// online. Returns the paired deviceId.
    @discardableResult
    func pair(with uri: PairingURI) async throws -> String {
        let parentName = Self.parentDeviceName()
        let outcome = try await PairingService.pair(with: uri, parentName: parentName)

        do {
            try credentialStore.save(outcome.credentials, for: outcome.device.deviceId)
        } catch {
            throw PairingError.keychain(error)
        }

        brokenDeviceIds.removeAll { $0 == outcome.device.deviceId }
        attach(device: outcome.device, credentials: outcome.credentials)
        persist(outcome.device)

        session(for: outcome.device.deviceId)?.start()
        await LocalNotificationManager.shared.requestAuthorizationIfNeeded()
        return outcome.device.deviceId
    }

    // MARK: - Unpairing

    /// Tells the child to revoke our token (best effort), then forgets
    /// everything locally: metadata, Keychain credentials and the pinned
    /// fingerprint.
    func unpair(deviceId: String) async {
        if let session = session(for: deviceId) {
            await session.unpairRemotely()
            await session.shutdown()
        }
        sessions.removeAll { $0.deviceId == deviceId }
        brokenDeviceIds.removeAll { $0 == deviceId }
        try? credentialStore.delete(for: deviceId)

        var stored = deviceStore.load()
        stored.removeAll { $0.deviceId == deviceId }
        deviceStore.save(stored)
    }

    /// Drops a device whose credentials are unreadable, without trying to
    /// contact it (we have no token to authenticate with).
    func forgetBroken(deviceId: String) {
        brokenDeviceIds.removeAll { $0 == deviceId }
        try? credentialStore.delete(for: deviceId)
        var stored = deviceStore.load()
        stored.removeAll { $0.deviceId == deviceId }
        deviceStore.save(stored)
    }

    // MARK: - Bonjour

    /// Routes a local-network sighting to the matching paired device.
    func applyDiscovery(_ discovery: BonjourBrowser.Discovery) {
        guard let deviceId = discovery.deviceId,
              let session = session(for: deviceId) else { return }
        session.applyBonjourEndpoint(discovery.endpoint)
    }

    func updateLocalNetworkPresence(using browser: BonjourBrowser) {
        for session in sessions {
            session.setLocalNetworkPresence(browser.isPresentOnLocalNetwork(deviceId: session.deviceId))
        }
    }

    // MARK: - Persistence

    private func persist(_ device: PairedDevice) {
        var stored = deviceStore.load()
        if let index = stored.firstIndex(where: { $0.deviceId == device.deviceId }) {
            stored[index] = device
        } else {
            stored.append(device)
        }
        deviceStore.save(stored)
    }

    /// A human-readable name for this phone, sent as `parentName` so the
    /// child device can show which parent is paired. iOS 16 returns the
    /// model name ("iPhone") rather than the user-assigned device name
    /// unless the app is specially entitled, which is fine here.
    private static func parentDeviceName() -> String {
        #if canImport(UIKit)
        let name = UIDevice.current.name
        return name.isEmpty ? "iPhone" : name
        #else
        return "Parent device"
        #endif
    }
}
