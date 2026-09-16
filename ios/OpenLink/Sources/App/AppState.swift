//
//  AppState.swift
//  OpenLink (parent app)
//
//  App-wide environment object: session state, the shared APIClient and
//  SocketManager, and the self-hosted server base URL setting.
//

import Foundation
import Combine

@MainActor
final class AppState: ObservableObject {
    @Published var isAuthenticated: Bool
    @Published var serverBaseURL: String {
        didSet {
            UserDefaults.standard.set(serverBaseURL, forKey: Self.serverURLDefaultsKey)
            apiClient.baseURLString = serverBaseURL
        }
    }
    @Published var lastError: String?

    /// Best-effort deviceId -> name cache, populated by the dashboard and
    /// read by the Requests screen so it can show a device name instead of
    /// a raw id without every screen re-fetching the device list.
    @Published var deviceNames: [String: String] = [:]

    let apiClient: APIClient
    let socketManager: SocketManager

    private static let serverURLDefaultsKey = "openlink.serverBaseURL"
    private let keychain = KeychainStore()
    private var cancellables = Set<AnyCancellable>()

    init() {
        let storedURL = UserDefaults.standard.string(forKey: Self.serverURLDefaultsKey) ?? ""
        let store = KeychainStore()
        let token = store.readToken()

        self.serverBaseURL = storedURL
        self.apiClient = APIClient(baseURLString: storedURL, authToken: token)
        self.socketManager = SocketManager()
        self.isAuthenticated = token != nil

        // SwiftUI views observe `appState` (an @EnvironmentObject), not the
        // nested `socketManager` object directly. Forward its
        // objectWillChange so that, e.g., a "Connected"/"Not connected"
        // label bound to `appState.socketManager.isConnected` actually
        // refreshes when that flips.
        socketManager.objectWillChange
            .sink { [weak self] _ in self?.objectWillChange.send() }
            .store(in: &cancellables)

        if let token, !storedURL.isEmpty {
            socketManager.connect(serverBaseURLString: storedURL, token: token)
        }
    }

    /// Call after a successful `/auth/register` or `/auth/login`.
    func completeAuth(token: String) {
        do {
            try keychain.save(token: token)
            apiClient.authToken = token
            lastError = nil
            isAuthenticated = true
            if !serverBaseURL.isEmpty {
                socketManager.connect(serverBaseURLString: serverBaseURL, token: token)
            }
        } catch {
            lastError = "Signed in, but couldn't securely store the session: \(error.localizedDescription)"
        }
    }

    func logout() {
        try? keychain.delete()
        apiClient.authToken = nil
        isAuthenticated = false
        deviceNames = [:]
        socketManager.disconnect()
    }

    /// Reconnects the socket if we have credentials but aren't connected —
    /// used when the app returns to the foreground.
    func reconnectSocketIfNeeded() {
        guard isAuthenticated, !serverBaseURL.isEmpty, !socketManager.isConnected else { return }
        guard let token = keychain.readToken() else { return }
        socketManager.connect(serverBaseURLString: serverBaseURL, token: token)
    }
}
