//
//  DeviceDetailView.swift
//  OpenLink (parent app)
//
//  GET /devices/:deviceId — lists the device's apps with today's usage and
//  per-app daily limit, plus a link into the schedule/downtime editor.
//

import SwiftUI

/// A merged view of one app's policy (if any) and today's usage (if any),
/// keyed by packageName. AppPolicy and UsageRecord are separate arrays in
/// the API response, so this ties them together for display.
struct AppUsageRow: Identifiable {
    let packageName: String
    let appName: String
    let dailyLimitMinutes: Int?
    let blocked: Bool
    let minutesUsedToday: Int

    var id: String { packageName }
}

struct DeviceDetailView: View {
    let deviceId: String

    @EnvironmentObject private var appState: AppState

    @State private var detail: ChildDeviceDetail?
    @State private var isLoading = false
    @State private var errorMessage: String?
    @State private var isTogglingLock = false
    @State private var editingRow: AppUsageRow?
    @State private var showScheduleEditor = false

    var body: some View {
        Group {
            if let detail {
                List {
                    Section {
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(detail.name).font(.headline)
                                if let timezone = detail.timezone {
                                    Text(timezone).font(.caption).foregroundStyle(.secondary)
                                }
                                Text(lastSeenText(detail.lastSeenAt))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button {
                                Task { await toggleLock() }
                            } label: {
                                if isTogglingLock {
                                    ProgressView()
                                } else {
                                    Label(
                                        detail.isLocked ? "Locked" : "Unlocked",
                                        systemImage: detail.isLocked ? "lock.fill" : "lock.open"
                                    )
                                    .foregroundStyle(detail.isLocked ? .red : .green)
                                }
                            }
                            .disabled(isTogglingLock)
                        }
                    }

                    Section("Downtime Schedule") {
                        Button {
                            showScheduleEditor = true
                        } label: {
                            HStack {
                                Text(scheduleSummary(detail.schedule))
                                Spacer()
                                Image(systemName: "chevron.right").foregroundStyle(.secondary)
                            }
                        }
                    }

                    Section("Apps") {
                        if appRows.isEmpty {
                            Text("No apps reported yet. The Android app syncs its app catalog automatically.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                        ForEach(appRows) { row in
                            Button {
                                editingRow = row
                            } label: {
                                appRowView(row)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                .refreshable { await refresh() }
            } else if isLoading {
                ProgressView()
            } else if let errorMessage {
                ContentUnavailableFallback(
                    title: "Couldn't Load Device",
                    message: errorMessage,
                    systemImage: "exclamationmark.triangle"
                )
            }
        }
        .navigationTitle(detail?.name ?? "Device")
        .task { await refresh() }
        .sheet(item: $editingRow) { row in
            NavigationStack {
                PolicyEditorView(deviceId: deviceId, row: row) {
                    Task { await refresh() }
                }
            }
        }
        .sheet(isPresented: $showScheduleEditor) {
            NavigationStack {
                ScheduleEditorView(deviceId: deviceId, initialWindows: detail?.schedule ?? []) {
                    Task { await refresh() }
                }
            }
        }
    }

    private var appRows: [AppUsageRow] {
        guard let detail else { return [] }
        var usageByPackage: [String: Int] = [:]
        for record in detail.usage {
            usageByPackage[record.packageName] = record.minutesUsed
        }

        var rows: [String: AppUsageRow] = [:]
        for policy in detail.policies {
            rows[policy.packageName] = AppUsageRow(
                packageName: policy.packageName,
                appName: policy.appName ?? policy.packageName,
                dailyLimitMinutes: policy.dailyLimitMinutes,
                blocked: policy.blocked,
                minutesUsedToday: usageByPackage[policy.packageName] ?? 0
            )
        }
        // Usage rows for apps that have usage today but no policy yet.
        for record in detail.usage where rows[record.packageName] == nil {
            rows[record.packageName] = AppUsageRow(
                packageName: record.packageName,
                appName: record.packageName,
                dailyLimitMinutes: nil,
                blocked: false,
                minutesUsedToday: record.minutesUsed
            )
        }
        return rows.values.sorted { $0.appName.localizedCaseInsensitiveCompare($1.appName) == .orderedAscending }
    }

    @ViewBuilder
    private func appRowView(_ row: AppUsageRow) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(row.appName)
                    .foregroundStyle(.primary)
                Text(row.packageName)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                HStack(spacing: 8) {
                    Text("\(row.minutesUsedToday) min today")
                    if let limit = row.dailyLimitMinutes {
                        Text("· limit \(limit) min")
                    } else {
                        Text("· unlimited")
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
            Spacer()
            if row.blocked {
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

    private func scheduleSummary(_ windows: [ScheduleWindow]) -> String {
        windows.isEmpty ? "No downtime windows set" : "\(windows.count) downtime window\(windows.count == 1 ? "" : "s")"
    }

    private func lastSeenText(_ date: Date?) -> String {
        guard let date else { return "Never seen" }
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return "Last seen \(formatter.localizedString(for: date, relativeTo: Date()))"
    }

    private func toggleLock() async {
        guard let detail else { return }
        isTogglingLock = true
        defer { isTogglingLock = false }
        do {
            _ = try await appState.apiClient.setLock(deviceId: deviceId, locked: !detail.isLocked)
            await refresh()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func refresh() async {
        isLoading = detail == nil
        defer { isLoading = false }
        do {
            let fetched = try await appState.apiClient.fetchDevice(id: deviceId)
            detail = fetched
            appState.deviceNames[fetched.id] = fetched.name
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
