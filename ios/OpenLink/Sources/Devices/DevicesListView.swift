//
//  DevicesListView.swift
//  OpenLink (parent app)
//
//  Dashboard: GET /devices, showing name, last-seen, lock status and a
//  lock/unlock toggle (POST /devices/:deviceId/lock). Live-updates from
//  Socket.IO `device:heartbeat`, with a ~30s foreground poll fallback for
//  when the socket isn't connected.
//

import SwiftUI

struct DevicesListView: View {
    @EnvironmentObject private var appState: AppState

    @State private var devices: [ChildDeviceSummary] = []
    @State private var isLoading = false
    @State private var errorMessage: String?
    @State private var lockInFlight: Set<String> = []
    @State private var hasLoadedOnce = false

    private static let relativeFormatter: RelativeDateTimeFormatter = {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter
    }()

    var body: some View {
        Group {
            if devices.isEmpty && !isLoading && hasLoadedOnce {
                ContentUnavailableFallback(
                    title: "No Paired Devices",
                    message: "Use the Pair Device tab to link an Android device.",
                    systemImage: "iphone.slash"
                )
            } else if devices.isEmpty && isLoading {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List {
                    if let errorMessage {
                        Section {
                            Text(errorMessage)
                                .foregroundStyle(.red)
                                .font(.footnote)
                        }
                    }
                    ForEach(devices) { device in
                        NavigationLink(value: device.id) {
                            deviceRow(device)
                        }
                    }
                }
                .refreshable { await refresh() }
            }
        }
        .navigationTitle("Devices")
        .navigationDestination(for: String.self) { deviceId in
            DeviceDetailView(deviceId: deviceId)
        }
        .task {
            appState.socketManager.onDeviceHeartbeat = { payload in
                applyHeartbeat(payload)
            }
            await refresh()
            await pollLoop()
        }
    }

    @ViewBuilder
    private func deviceRow(_ device: ChildDeviceSummary) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(device.name)
                    .font(.headline)
                Text(lastSeenText(device.lastSeenAt))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text("\(device.appCount) app\(device.appCount == 1 ? "" : "s") tracked")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            lockToggle(device)
        }
        .padding(.vertical, 4)
    }

    @ViewBuilder
    private func lockToggle(_ device: ChildDeviceSummary) -> some View {
        Button {
            Task { await toggleLock(device) }
        } label: {
            if lockInFlight.contains(device.id) {
                ProgressView()
                    .frame(width: 28, height: 28)
            } else {
                Image(systemName: device.isLocked ? "lock.fill" : "lock.open")
                    .font(.title3)
                    .foregroundStyle(device.isLocked ? .red : .green)
                    .frame(width: 28, height: 28)
            }
        }
        .buttonStyle(.plain)
        .disabled(lockInFlight.contains(device.id))
    }

    private func lastSeenText(_ date: Date?) -> String {
        guard let date else { return "Never seen" }
        return "Last seen \(Self.relativeFormatter.localizedString(for: date, relativeTo: Date()))"
    }

    private func applyHeartbeat(_ payload: DeviceHeartbeatPayload) {
        guard let index = devices.firstIndex(where: { $0.id == payload.deviceId }) else { return }
        let existing = devices[index]
        devices[index] = ChildDeviceSummary(
            id: existing.id,
            name: existing.name,
            platform: existing.platform,
            lastSeenAt: payload.lastSeenAt,
            isLocked: existing.isLocked,
            appCount: existing.appCount
        )
    }

    private func toggleLock(_ device: ChildDeviceSummary) async {
        lockInFlight.insert(device.id)
        defer { lockInFlight.remove(device.id) }

        let newValue = !device.isLocked
        // Optimistic update, reverted on failure.
        if let index = devices.firstIndex(where: { $0.id == device.id }) {
            devices[index] = ChildDeviceSummary(
                id: device.id, name: device.name, platform: device.platform,
                lastSeenAt: device.lastSeenAt, isLocked: newValue, appCount: device.appCount
            )
        }
        do {
            _ = try await appState.apiClient.setLock(deviceId: device.id, locked: newValue)
        } catch {
            if let index = devices.firstIndex(where: { $0.id == device.id }) {
                devices[index] = device // revert
            }
            errorMessage = error.localizedDescription
        }
    }

    private func refresh() async {
        isLoading = true
        errorMessage = nil
        defer {
            isLoading = false
            hasLoadedOnce = true
        }
        do {
            let fetched = try await appState.apiClient.fetchDevices()
            devices = fetched
            for device in fetched {
                appState.deviceNames[device.id] = device.name
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// Foreground fallback poll every ~30s, only while the socket isn't
    /// connected (docs/API.md: "iOS falls back to polling GET /devices and
    /// GET /requests when the app is foregrounded").
    private func pollLoop() async {
        while !Task.isCancelled {
            try? await Task.sleep(for: .seconds(30))
            if Task.isCancelled { break }
            if !appState.socketManager.isConnected {
                await refresh()
            }
        }
    }
}

/// Small ContentUnavailableView-style fallback that also works pre-iOS 17
/// (deployment target here is iOS 16).
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

#Preview {
    NavigationStack {
        DevicesListView()
    }
    .environmentObject(AppState())
}
