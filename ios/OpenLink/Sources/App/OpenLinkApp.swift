//
//  OpenLinkApp.swift
//  OpenLink (parent app)
//
//  SwiftUI app entry point.
//

import SwiftUI

@main
struct OpenLinkApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var appState = AppState()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(appState)
        }
        .onChange(of: scenePhase) { _, newPhase in
            if newPhase == .active {
                appState.reconnectSocketIfNeeded()
            }
        }
    }
}
