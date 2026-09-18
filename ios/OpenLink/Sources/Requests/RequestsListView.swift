//
//  RequestsListView.swift
//  OpenLink (parent app)
//
//  Pending time requests across every paired device, with approve/deny, plus
//  recently-resolved ones.
//
//  Each device is queried independently (GET /requests on that device), so a
//  device that's unreachable simply contributes nothing rather than failing
//  the whole screen. Requests carry no deviceId in the protocol — the owning
//  device is the DeviceSession that fetched them.
//

import SwiftUI

struct RequestsListView: View {
    @EnvironmentObject private var appState: AppState

    @State private var activeSheet: ActiveSheet?
    @State private var errorMessage: String?

    /// A request plus the device it belongs to.
    ///
    /// `deviceId` is copied in rather than read back off `session`, because
    /// `Identifiable.id` is nonisolated and `DeviceSession` is `@MainActor` —
    /// the getter cannot touch the session. Construction always happens in the
    /// view body, which is main-actor isolated, so copying it is free.
    private struct Item: Identifiable {
        let session: DeviceSession
        let deviceId: String
        let request: TimeRequest
        var id: String { "\(deviceId)/\(request.id)" }

        @MainActor
        init(session: DeviceSession, request: TimeRequest) {
            self.session = session
            self.deviceId = session.deviceId
            self.request = request
        }
    }

    private enum ActiveSheet: Identifiable {
        case approve(deviceId: String, request: TimeRequest)
        case deny(deviceId: String, request: TimeRequest)

        var id: String {
            switch self {
            case .approve(let deviceId, let request): return "approve-\(deviceId)-\(request.id)"
            case .deny(let deviceId, let request): return "deny-\(deviceId)-\(request.id)"
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
                ForEach(pending) { item in
                    requestRow(item, isResolved: false)
                }
            }

            Section("Recently Resolved") {
                if resolved.isEmpty {
                    Text("Nothing resolved yet.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                ForEach(resolved) { item in
                    requestRow(item, isResolved: true)
                }
            }

            if unreachableDevices.isEmpty == false {
                Section {
                    Text("Can't reach: \(unreachableDevices.joined(separator: ", ")). Requests from those devices will appear once they're connectable — they queue safely on the device itself.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle("Time Requests")
        .refreshable { await refresh() }
        .task { await refresh() }
        .sheet(item: $activeSheet) { sheet in
            NavigationView {
                switch sheet {
                case .approve(let deviceId, let request):
                    ApproveRequestView(request: request) { minutes in
                        await respond(deviceId: deviceId) { session in
                            try await session.approve(requestId: request.id, grantedMinutes: minutes)
                        }
                    }
                case .deny(let deviceId, let request):
                    DenyRequestView(request: request) { reason in
                        await respond(deviceId: deviceId) { session in
                            try await session.deny(requestId: request.id, reason: reason)
                        }
                    }
                }
            }
            .navigationViewStyle(.stack)
        }
    }

    // MARK: - Data

    private var pending: [Item] {
        appState.registry.sessions
            .flatMap { session in session.pendingRequests.map { Item(session: session, request: $0) } }
            .sorted { $0.request.createdAt > $1.request.createdAt }
    }

    private var resolved: [Item] {
        appState.registry.sessions
            .flatMap { session in session.resolvedRequests.map { Item(session: session, request: $0) } }
            .sorted {
                ($0.request.respondedAt ?? $0.request.createdAt) > ($1.request.respondedAt ?? $1.request.createdAt)
            }
            .prefix(30)
            .map { $0 }
    }

    private var unreachableDevices: [String] {
        appState.registry.sessions
            .filter { !$0.connectionState.isConnected }
            .map(\.device.deviceName)
    }

    // MARK: - Rows

    @ViewBuilder
    private func requestRow(_ item: Item, isResolved: Bool) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(item.session.device.deviceName)
                    .font(.headline)
                Spacer()
                statusBadge(item.request)
            }
            Text(item.request.displayName)
                .font(.subheadline)
            if item.request.displayName != item.request.packageName {
                Text(item.request.packageName)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            let messageSuffix = item.request.message.map { " — “\($0)”" } ?? ""
            Text("Asked for \(item.request.minutesRequested) more min\(messageSuffix)")
                .font(.subheadline)
                .foregroundStyle(.secondary)

            if let grantedMinutes = item.request.grantedMinutes {
                Text("Granted \(grantedMinutes) min")
                    .font(.caption)
                    .foregroundStyle(.green)
            }
            if let note = item.request.responseNote {
                Text("Note: \(note)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if !isResolved {
                HStack {
                    Button("Approve") {
                        activeSheet = .approve(deviceId: item.session.deviceId, request: item.request)
                    }
                    .buttonStyle(.borderedProminent)
                    Button("Deny", role: .destructive) {
                        activeSheet = .deny(deviceId: item.session.deviceId, request: item.request)
                    }
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

    // MARK: - Actions

    private func respond(deviceId: String, _ action: (DeviceSession) async throws -> Void) async {
        guard let session = appState.registry.session(for: deviceId) else { return }
        do {
            try await action(session)
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func refresh() async {
        await appState.registry.refreshAllOnForeground()
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
                Text(request.displayName)
                if let message = request.message {
                    Text(message).font(.footnote).foregroundStyle(.secondary)
                }
                Text("Asked for \(request.minutesRequested) min")
            }
            Section("Grant") {
                Stepper(value: $minutes, in: 1...600, step: 5) {
                    Text("\(Int(minutes)) minutes")
                }
                Text("Takes effect on the child device immediately.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Approve Request")
        .navigationBarTitleDisplayMode(.inline)
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
                Text(request.displayName)
                Text("Asked for \(request.minutesRequested) min")
            }
            Section("Reason (optional)") {
                // `axis:` is iOS 16+; this app targets 15.1.
                TextField("Let them know why", text: $reason)
            }
        }
        .navigationTitle("Deny Request")
        .navigationBarTitleDisplayMode(.inline)
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
