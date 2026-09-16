//
//  RootView.swift
//  OpenLink (parent app)
//
//  Top-level switch between the signed-out (Auth) flow and the signed-in
//  (MainTabView) flow.
//

import SwiftUI

struct RootView: View {
    @EnvironmentObject private var appState: AppState

    var body: some View {
        Group {
            if appState.isAuthenticated {
                MainTabView()
            } else {
                AuthView()
            }
        }
        .animation(.default, value: appState.isAuthenticated)
    }
}
