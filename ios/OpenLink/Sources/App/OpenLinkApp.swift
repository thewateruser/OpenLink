//
//  OpenLinkApp.swift
//  OpenLink (parent app)
//
//  SwiftUI app entry point. No UIApplicationDelegate any more: the only
//  reason there was one was APNs registration, which no longer exists.
//

import SwiftUI

@main
struct OpenLinkApp: App {
    @StateObject private var appState = AppState()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(appState)
                .task { appState.startAll() }
        }
        .onChange(of: scenePhase) { newPhase in
            switch newPhase {
            case .active:
                appState.handleForeground()
            case .background:
                appState.handleBackground()
            default:
                break
            }
        }
    }
}
