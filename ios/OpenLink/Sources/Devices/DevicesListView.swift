//
//  DevicesListView.swift
//  OpenLink (parent app)
//
//  The list of PAIRED devices — held locally, not fetched from an account.
//  Each row shows its own live connection state and whether it's visible on
//  this Wi-Fi via Bonjour.
//

import SwiftUI

struct DevicesListView: View {
    @EnvironmentObject private var appState: AppState

    @State private var isPairing = false

    var body: some View {
        List {
            if !appState.registry.brokenDeviceIds.isEmpty {
                Section("Needs Re-Pairing") {
                    ForEach(appState.registry.brokenDeviceIds, id: \.self) { deviceId in
                        brokenRow(deviceId)
                    }
                }
            }

            Section {
                ForEach(appState.registry.sessions) { session in
                    NavigationLink(value: session.deviceId) {
                        deviceRow(session)
                    }
                }
            }
        }
        .navigationTitle("Devices")
        .navigationDestination(for: String.self) { deviceId in
            if let session = appState.registry.session(for: deviceId) {
                DeviceDetailView(session: session)
            } else {
                ContentUnavailableFallback(
                    title: "Device Removed",
                    message: "This device is no longer paired with this phone.",
                    systemImage: "iphone.slash"
                )
            }
        }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    isPairing = true
                } label: {
                    Label("Pair Device", systemImage: "plus")
                }
            }
        }
        .sheet(isPresented: $isPairing) {
            NavigationStack {
                PairDeviceView()
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Cancel") { isPairing = false }
                        }
                    }
            }
        }
        .refreshable {
            await appState.registry.refreshAllOnForeground()
        }
    }

    // MARK: - Rows

    @ViewBuilder
    private func deviceRow(_ session: DeviceSession) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(session.device.deviceName)
                    .font(.headline)

                HStack(spacing: 6) {
                    ConnectionDot(state: session.connectionState)
                    Text(statusText(session))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                HStack(spacing: 8) {
                    if session.isOnLocalNetwork {
                        Label("On this Wi-Fi", systemImage: "wifi")
                    }
                    if let battery = session.device.batteryLevel {
                        Label("\(battery)%", systemImage: "battery.100")
                    }
                    if session.isSocketConnected {
                        Label("Live", systemImage: "bolt.horizontal.fill")
                    }
                }
                .font(.caption2)
                .foregroundStyle(.secondary)
            }
            Spacer()
        }
        .padding(.vertical, 4)
    }

    @ViewBuilder
    private func brokenRow(_ deviceId: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Device \(deviceId.prefix(8))…")
                .font(.headline)
            Text("Its stored credentials are missing on this phone (they're never restored from a backup). Scan a fresh QR code to pair it again.")
                .font(.caption)
                .foregroundStyle(.secondary)
            Button("Remove", role: .destructive) {
                appState.registry.forgetBroken(deviceId: deviceId)
            }
            .font(.caption)
        }
        .padding(.vertical, 4)
    }

    private func statusText(_ session: DeviceSession) -> String {
        switch session.connectionState {
        case .idle:
            return "Not connected yet"
        case .connecting:
            return "Connecting…"
        case .connected(let via):
            return "Connected via \(via)"
        case .failed:
            if let last = session.device.lastConnectedAt {
                let formatter = RelativeDateTimeFormatter()
                formatter.unitsStyle = .abbreviated
                return "Unreachable — last seen \(formatter.localizedString(for: last, relativeTo: Date()))"
            }
            return "Unreachable"
        }
    }
}

/// Small coloured dot summarising a device's connection state.
struct ConnectionDot: View {
    let state: DeviceSession.ConnectionState

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: 8, height: 8)
    }

    private var color: Color {
        switch state {
        case .idle: return .gray
        case .connecting: return .orange
        case .connected: return .green
        case .failed: return .red
        }
    }
}

/// ContentUnavailableView-style fallback that works on iOS 16.
struct ContentUnavailableFallback: View {
    let title: String
    let message: String
    let systemImage: String

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 40))
                .foregroundStyle(.secondary)
            Text(title)
                .font(.headline)
            Text(message)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
