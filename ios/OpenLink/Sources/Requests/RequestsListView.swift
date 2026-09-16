//
//  RequestsListView.swift
//  OpenLink (parent app)
//
//  Lists pending TimeRequests across all devices (GET /requests?status=pending)
//  with approve/deny actions, plus recently-resolved requests.
//
//  Assumption: docs/API.md only shows `?status=pending` as an example, but
//  TimeRequest.status is documented as `pending|approved|denied`, so
//  "recently resolved" is built by also querying `status=approved` and
//  `status=denied` on the same endpoint and merging the results client-side
//  (see ios/README.md "Assumptions").
//

import SwiftUI

struct RequestsListView: View {
    @EnvironmentObject private var appState: AppState

    @State private var pending: [TimeRequest] = []
    @State private var resolved: [TimeRequest] = []
    @State private var isLoading = false
    @State private var errorMessage: String?
    @State private var activeSheet: ActiveSheet?

    private enum ActiveSheet: Identifiable {
        case approve(TimeRequest)
        case deny(TimeRequest)

        var id: String {
            switch self {
            case .approve(let request): return "approve-\(request.id)"
            case .deny(let request): return "deny-\(request.id)"
            }
        }
    }

    var body: some View {
        List {
            if let errorMessage {
                Section {
                    Text(errorMessage)
                        .foregroundStyle(.red)
                        .font(.footnote)
                }
            }

            Section("Pending") {
                if pending.isEmpty {
                    Text("No pending time requests.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                ForEach(pending) { request in
                    requestRow(request, isResolved: false)
                }
            }

            Section("Recently Resolved") {
                if resolved.isEmpty {
                    Text("Nothing resolved yet.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                ForEach(resolved) { request in
                    requestRow(request, isResolved: true)
                }
            }
        }
        .navigationTitle("Time Requests")
        .refreshable { await refresh() }
        .task {
            appState.socketManager.onNewRequest = { request in
                if !pending.contains(where: { $0.id == request.id }) {
                    pending.insert(request, at: 0)
                }
            }
            await refresh()
            await pollLoop()
        }
        .sheet(item: $activeSheet) { sheet in
            NavigationStack {
                switch sheet {
                case .approve(let request):
                    ApproveRequestView(request: request) { minutes in
                        await approve(request, grantedMinutes: minutes)
                    }
                case .deny(let request):
                    DenyRequestView(request: request) { reason in
                        await deny(request, reason: reason)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func requestRow(_ request: TimeRequest, isResolved: Bool) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(deviceName(for: request.deviceId))
                    .font(.headline)
                Spacer()
                statusBadge(request)
            }
            Text(request.packageName)
                .font(.caption)
                .foregroundStyle(.secondary)

            let messageSuffix = request.message.map { " — \($0)" } ?? ""
            Text("Requested \(request.minutesRequested) min\(messageSuffix)")
                .font(.subheadline)

            if let grantedMinutes = request.grantedMinutes {
                Text("Granted \(grantedMinutes) min")
                    .font(.caption)
                    .foregroundStyle(.green)
            }

            if !isResolved {
                HStack {
                    Button("Approve") { activeSheet = .approve(request) }
                        .buttonStyle(.borderedProminent)
                    Button("Deny", role: .destructive) { activeSheet = .deny(request) }
                        .buttonStyle(.bordered)
                }
                .padding(.top, 4)
            }
        }
        .padding(.vertical, 4)
    }

    @ViewBuilder
    private func statusBadge(_ request: TimeRequest) -> some View {
        Text(request.status.rawValue.capitalized)
            .font(.caption2.bold())
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(color(for: request.status).opacity(0.15))
            .foregroundStyle(color(for: request.status))
            .clipShape(Capsule())
    }

    private func color(for status: TimeRequestStatus) -> Color {
        switch status {
        case .pending: return .orange
        case .approved: return .green
        case .denied: return .red
        }
    }

    private func deviceName(for deviceId: String) -> String {
        appState.deviceNames[deviceId] ?? "Device \(deviceId.prefix(6))"
    }

    private func approve(_ request: TimeRequest, grantedMinutes: Int) async {
        do {
            _ = try await appState.apiClient.approveRequest(id: request.id, grantedMinutes: grantedMinutes)
            await refresh()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func deny(_ request: TimeRequest, reason: String?) async {
        do {
            _ = try await appState.apiClient.denyRequest(id: request.id, reason: reason)
            await refresh()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func refresh() async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            async let devicesTask = appState.apiClient.fetchDevices()
            async let pendingTask = appState.apiClient.fetchRequests(status: "pending")
            async let approvedTask = appState.apiClient.fetchRequests(status: "approved")
            async let deniedTask = appState.apiClient.fetchRequests(status: "denied")

            let (devices, pendingResult, approvedResult, deniedResult) = try await (
                devicesTask, pendingTask, approvedTask, deniedTask
            )

            for device in devices {
                appState.deviceNames[device.id] = device.name
            }

            pending = pendingResult.sorted { $0.createdAt > $1.createdAt }
            let combinedResolved = (approvedResult + deniedResult)
                .sorted { ($0.respondedAt ?? $0.createdAt) > ($1.respondedAt ?? $1.createdAt) }
            resolved = Array(combinedResolved.prefix(20))
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// Foreground fallback poll every ~30s while the socket isn't connected.
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

private struct ApproveRequestView: View {
    let request: TimeRequest
    var onSubmit: (Int) async -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var minutes: Double
    @State private var isSubmitting = false

    init(request: TimeRequest, onSubmit: @escaping (Int) async -> Void) {
        self.request = request
        self.onSubmit = onSubmit
        _minutes = State(initialValue: Double(max(request.minutesRequested, 1)))
    }

    var body: some View {
        Form {
            Section("Request") {
                Text(request.packageName)
                if let message = request.message {
                    Text(message).font(.footnote).foregroundStyle(.secondary)
                }
                Text("Requested \(request.minutesRequested) min")
            }
            Section("Grant") {
                Stepper(value: $minutes, in: 1...600, step: 5) {
                    Text("\(Int(minutes)) minutes")
                }
            }
        }
        .navigationTitle("Approve Request")
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("Approve") {
                    isSubmitting = true
                    Task {
                        await onSubmit(Int(minutes))
                        dismiss()
                    }
                }
                .disabled(isSubmitting)
            }
        }
        .disabled(isSubmitting)
    }
}

private struct DenyRequestView: View {
    let request: TimeRequest
    var onSubmit: (String?) async -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var reason = ""
    @State private var isSubmitting = false

    var body: some View {
        Form {
            Section("Request") {
                Text(request.packageName)
                Text("Requested \(request.minutesRequested) min")
            }
            Section("Reason (optional)") {
                TextField("Let them know why", text: $reason, axis: .vertical)
            }
        }
        .navigationTitle("Deny Request")
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("Deny", role: .destructive) {
                    isSubmitting = true
                    Task {
                        await onSubmit(reason.isEmpty ? nil : reason)
                        dismiss()
                    }
                }
                .disabled(isSubmitting)
            }
        }
        .disabled(isSubmitting)
    }
}

#Preview {
    NavigationStack {
        RequestsListView()
    }
    .environmentObject(AppState())
}
