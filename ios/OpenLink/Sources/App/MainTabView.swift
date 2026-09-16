//
//  MainTabView.swift
//  OpenLink (parent app)
//
//  Signed-in shell: dashboard, time requests, pairing and settings.
//

import SwiftUI

struct MainTabView: View {
    var body: some View {
        TabView {
            NavigationStack {
                DevicesListView()
            }
            .tabItem { Label("Devices", systemImage: "iphone.and.arrow.forward") }

            NavigationStack {
                RequestsListView()
            }
            .tabItem { Label("Requests", systemImage: "clock.badge.questionmark") }

            NavigationStack {
                PairingView()
            }
            .tabItem { Label("Pair Device", systemImage: "qrcode") }

            NavigationStack {
                SettingsView()
            }
            .tabItem { Label("Settings", systemImage: "gearshape") }
        }
    }
}
