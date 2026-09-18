//
//  SettingsView.swift
//  OpenLink (parent app)
//
//  There is no account and no server address to configure any more, so this
//  is mostly a status and explanation screen: what's connected, what this app
//  can and can't do, and why.
//

import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var appState: AppState
    @ObservedObject private var notifications = LocalNotificationManager.shared

    var body: some View {
        Form {
            Section("Paired Devices") {
                if appState.registry.sessions.isEmpty {
                    Text("No devices paired.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                ForEach(appState.registry.sessions) { session in
                    HStack {
                        ConnectionDot(state: session.connectionState)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(session.device.deviceName)
                            Text(session.isSocketConnected ? "Live updates connected" : "Polling every 30s")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        if session.isOnLocalNetwork {
                            Image(systemName: "wifi").foregroundStyle(.green)
                        }
                    }
                }
            }

            Section("Local Network") {
                Label(
                    appState.bonjour.isBrowsing ? "Looking for devices on this Wi-Fi" : "Not browsing",
                    systemImage: appState.bonjour.isBrowsing ? "dot.radiowaves.left.and.right" : "wifi.slash"
                )
                .font(.footnote)
                .foregroundStyle(appState.bonjour.isBrowsing ? .green : .secondary)

                if appState.bonjour.permissionDenied {
                    Text("Local network access is off. Enable it in Settings → OpenLink → Local Network so paired devices on your Wi-Fi are found quickly.")
                        .font(.caption)
                        .foregroundStyle(.red)
                }
            }

            Section("Notifications") {
                Label(
                    notifications.isAuthorized ? "Local notifications allowed" : "Local notifications off",
                    systemImage: notifications.isAuthorized ? "bell.fill" : "bell.slash"
                )
                .font(.footnote)
                Text("""
                OpenLink can only notify you while it's running or recently backgrounded. Push notifications would need an Apple push server, and OpenLink has no server by design — so if the app is fully closed, you'll see pending requests the next time you open it.
                """)
                .font(.caption)
                .foregroundStyle(.secondary)
            }

            Section("How This Works") {
                infoRow(
                    "No account, no server",
                    "Each child device is its own server. This app talks to it directly over your Wi-Fi, or over an overlay network (Tailscale/WireGuard) when you're away."
                )
                infoRow(
                    "Certificate pinning",
                    "Scanning a pairing QR records that device's exact TLS certificate. Any other certificate is refused, which is what stops someone on your network impersonating it."
                )
                infoRow(
                    "The device decides",
                    "Limits, schedules and tallies live on the child device and keep being enforced even when nothing is connected. This app is a remote control."
                )
            }

            Section {
                if let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String {
                    HStack {
                        Text("Version")
                        Spacer()
                        Text(version)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .navigationTitle("Settings")
        .task { await notifications.refreshAuthorizationStatus() }
    }

    @ViewBuilder
    private func infoRow(_ title: String, _ detail: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.subheadline.bold())
            Text(detail).font(.caption).foregroundStyle(.secondary)
        }
        .padding(.vertical, 2)
    }
}
