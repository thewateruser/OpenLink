//
//  DeviceDetailView.swift
//  OpenLink (parent app)
//
//  One child device: downtime schedule, per-app limits from GET /apps, and
//  the connection/endpoint details.
//

import SwiftUI

struct DeviceDetailView: View {
    @ObservedObject var session: DeviceSession

    @EnvironmentObject private var appState: AppState

    @State private var editingApp: AppEntry?
    @State private var showScheduleEditor = false
    @State private var showEndpoints = false
    @State private var showUnpairConfirmation = false
    @State private var hidesSystemApps = true

    var body: some View {
        List {
            statusSection
            scheduleSection
            appsSection
            connectionSection
            dangerSection
        }
        .navigationTitle(session.device.deviceName)
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await session.refresh() }
        .task { await session.refresh() }
        .sheet(item: $editingApp) { app in
            NavigationView {
                PolicyEditorView(session: session, app: app)
            }
            .navigationViewStyle(.stack)
        }
        .sheet(isPresented: $showScheduleEditor) {
            NavigationView {
                ScheduleEditorView(session: session, initialWindows: session.schedule)
            }
            .navigationViewStyle(.stack)
        }
        .sheet(isPresented: $showEndpoints) {
            NavigationView {
                EndpointsView(session: session)
            }
            .navigationViewStyle(.stack)
        }
        .confirmationDialog(
            "Remove “\(session.device.deviceName)”?",
            isPresented: $showUnpairConfirmation,
            titleVisibility: .visible
        ) {
            Button("Remove Device", role: .destructive) {
                Task { await appState.registry.unpair(deviceId: session.deviceId) }
            }
        } message: {
            Text("This phone will forget the device's token and pinned certificate, and will ask the device to revoke this parent. Enforcement on the child device keeps running.")
        }
    }

    // MARK: - Sections

    @ViewBuilder
    private var statusSection: some View {
        Section {
            HStack(spacing: 8) {
                ConnectionDot(state: session.connectionState)
                VStack(alignment: .leading, spacing: 2) {
                    Text(connectionSummary)
                        .font(.subheadline)
                    if let info = session.deviceInfo {
                        Text([info.platform, info.appVersion].compactMap { $0 }.joined(separator: " · "))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
                if session.isOnLocalNetwork {
                    Image(systemName: "wifi")
                        .foregroundStyle(.green)
                }
            }

            if let failure = session.connectionState.describedFailure {
                Text(failure)
                    .font(.footnote)
                    .foregroundStyle(.red)
                Button("Try Again") { session.reconnect() }
                    .font(.footnote)
            }
        }
    }

    @ViewBuilder
    private var scheduleSection: some View {
        Section("Downtime Schedule") {
            Button {
                showScheduleEditor = true
            } label: {
                HStack {
                    Text(scheduleSummary)
                        .foregroundStyle(.primary)
                    Spacer()
                    Image(systemName: "chevron.right").foregroundStyle(.secondary)
                }
            }
        }
    }

    @ViewBuilder
    private var appsSection: some View {
        Section {
            if visibleApps.isEmpty {
                Text(session.apps.isEmpty
                     ? "No apps reported yet. Connect to the device to list them."
                     : "All installed apps are system apps.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            ForEach(visibleApps) { app in
                Button {
                    editingApp = app
                } label: {
                    appRow(app)
                }
                .buttonStyle(.plain)
            }
        } header: {
            HStack {
                Text("Apps")
                Spacer()
                Toggle("Hide system apps", isOn: $hidesSystemApps)
                    .labelsHidden()
                    .toggleStyle(.switch)
                    .scaleEffect(0.8)
            }
        } footer: {
            Text(hidesSystemApps ? "System apps are hidden." : "Showing all installed apps.")
        }
    }

    @ViewBuilder
    private var connectionSection: some View {
        Section("Connection") {
            Button {
                showEndpoints = true
            } label: {
                HStack {
                    Text("Addresses")
                        .foregroundStyle(.primary)
                    Spacer()
                    Text("\(session.device.endpoints.count)")
                        .foregroundStyle(.secondary)
                    Image(systemName: "chevron.right").foregroundStyle(.secondary)
                }
            }
            Label(
                session.isSocketConnected ? "Live updates connected" : "Live updates off — polling every 30s",
                systemImage: session.isSocketConnected ? "bolt.horizontal.fill" : "arrow.clockwise"
            )
            .font(.footnote)
            .foregroundStyle(session.isSocketConnected ? .green : .secondary)
        }
    }

    @ViewBuilder
    private var dangerSection: some View {
        Section {
            Button("Remove This Device", role: .destructive) {
                showUnpairConfirmation = true
            }
        }
    }

    // MARK: - Rows and helpers

    @ViewBuilder
    private func appRow(_ app: AppEntry) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(app.appName)
                    .foregroundStyle(.primary)
                Text(app.packageName)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                HStack(spacing: 4) {
                    Text("\(app.todayMinutes) min today")
                    if let limit = app.dailyLimitMinutes {
                        Text("· limit \(limit) min")
                    } else {
                        Text("· unlimited")
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
            Spacer()
            if app.isBlocked {
                Text("Blocked")
                    .font(.caption2.bold())
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(Color.red.opacity(0.15))
                    .foregroundStyle(.red)
                    .clipShape(Capsule())
            }
        }
        .padding(.vertical, 2)
    }

    private var visibleApps: [AppEntry] {
        hidesSystemApps ? session.apps.filter { !$0.isSystemApp } : session.apps
    }

    private var scheduleSummary: String {
        let count = session.schedule.count
        return count == 0 ? "No downtime windows set" : "\(count) downtime window\(count == 1 ? "" : "s")"
    }

    private var connectionSummary: String {
        switch session.connectionState {
        case .idle: return "Not connected"
        case .connecting: return "Connecting…"
        case .connected(let via): return "Connected via \(via)"
        case .failed: return "Can't reach this device"
        }
    }
}
