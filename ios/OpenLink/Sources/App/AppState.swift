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

    /// Both dependencies default to `nil` rather than to `DeviceRegistry()` /
    /// `BonjourBrowser()`. A default argument expression is evaluated in a
    /// *nonisolated* context at the call site, so it cannot construct these
    /// `@MainActor` types; the initializer body can, because it inherits this
    /// class's isolation. Injecting them explicitly still works for tests.
    init(registry: DeviceRegistry? = nil, bonjour: BonjourBrowser? = nil) {
        let registry = registry ?? DeviceRegistry()
        let bonjour = bonjour ?? BonjourBrowser()
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
        // Needs no permission and raises no prompt; it only tells us whether a
        // Wi-Fi path exists, which is what separates "you're not on Wi-Fi"
        // from "Local Network access is off" when a connection fails.
        LocalNetworkMonitor.shared.start()
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
