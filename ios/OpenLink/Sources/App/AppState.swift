//
//  AppState.swift
//  OpenLink (parent app)
//
//  App-wide environment object. With no server there is no session, no JWT
//  and no server-URL setting — this just owns the paired-device registry and
//  the Bonjour browser, and wires the two together.
//

import Foundation
import Combine

@MainActor
final class AppState: ObservableObject {
    let registry: DeviceRegistry
    let bonjour: BonjourBrowser

    private var cancellables = Set<AnyCancellable>()

    init(registry: DeviceRegistry = DeviceRegistry(), bonjour: BonjourBrowser = BonjourBrowser()) {
        self.registry = registry
        self.bonjour = bonjour

        // Views observe `appState`; forward the nested objects' changes so a
        // "on this Wi-Fi" badge or a connection dot actually refreshes.
        registry.objectWillChange
            .sink { [weak self] _ in self?.objectWillChange.send() }
            .store(in: &cancellables)
        bonjour.objectWillChange
            .sink { [weak self] _ in self?.objectWillChange.send() }
            .store(in: &cancellables)

        bonjour.onDiscovery = { [weak self] discovery in
            guard let self else { return }
            self.registry.applyDiscovery(discovery)
            self.registry.updateLocalNetworkPresence(using: self.bonjour)
        }
    }

    /// Called on launch and whenever the app becomes active.
    func startAll() {
        bonjour.start()
        registry.startAll()
        registry.updateLocalNetworkPresence(using: bonjour)
    }

    func handleForeground() {
        bonjour.start()
        registry.startAll()
        Task {
            await LocalNotificationManager.shared.refreshAuthorizationStatus()
            await registry.refreshAllOnForeground()
            registry.updateLocalNetworkPresence(using: bonjour)
        }
    }

    /// The socket is left running when backgrounded on purpose: iOS keeps it
    /// alive for a while, and that window is the only one in which a
    /// `request:new` can raise a local notification (there is no APNs path —
    /// see LocalNotificationManager). Only Bonjour, which is comparatively
    /// expensive, is stopped.
    func handleBackground() {
        bonjour.stop()
    }
}
