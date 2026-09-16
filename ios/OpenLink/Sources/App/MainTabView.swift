//
//  MainTabView.swift
//  OpenLink (parent app)
//
//  Three tabs: the paired devices, time requests across all of them, and
//  settings. Pairing lives behind a "+" on the devices list rather than
//  occupying a tab of its own — it's a rare action now that it's per-device
//  rather than per-account.
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
                SettingsView()
            }
            .tabItem { Label("Settings", systemImage: "gearshape") }
        }
    }
}
