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
            NavigationView {
                DevicesListView()
            }
            .navigationViewStyle(.stack)
            .tabItem { Label("Devices", systemImage: "iphone.and.arrow.forward") }

            NavigationView {
                RequestsListView()
            }
            .navigationViewStyle(.stack)
            .tabItem { Label("Requests", systemImage: "clock.badge.questionmark") }

            NavigationView {
                SettingsView()
            }
            .navigationViewStyle(.stack)
            .tabItem { Label("Settings", systemImage: "gearshape") }
        }
    }
}
