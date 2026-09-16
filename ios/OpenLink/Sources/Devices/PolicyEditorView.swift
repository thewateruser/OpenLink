//
//  PolicyEditorView.swift
//  OpenLink (parent app)
//
//  Set/clear an app's dailyLimitMinutes and toggle a hard `blocked` switch.
//  PUT /devices/:deviceId/policies/:packageName
//

import SwiftUI

struct PolicyEditorView: View {
    let deviceId: String
    let row: AppUsageRow
    var onSaved: () -> Void

    @EnvironmentObject private var appState: AppState
    @Environment(\.dismiss) private var dismiss

    @State private var isLimited: Bool
    @State private var limitMinutes: Double
    @State private var isBlocked: Bool
    @State private var isSaving = false
    @State private var errorMessage: String?

    init(deviceId: String, row: AppUsageRow, onSaved: @escaping () -> Void) {
        self.deviceId = deviceId
        self.row = row
        self.onSaved = onSaved
        _isLimited = State(initialValue: row.dailyLimitMinutes != nil)
        _limitMinutes = State(initialValue: Double(row.dailyLimitMinutes ?? 60))
        _isBlocked = State(initialValue: row.blocked)
    }

    var body: some View {
        Form {
            Section(row.appName) {
                Text(row.packageName)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text("\(row.minutesUsedToday) min used today")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section("Daily Limit") {
                Toggle("Limit daily usage", isOn: $isLimited.animation())
                if isLimited {
                    Stepper(value: $limitMinutes, in: 5...600, step: 5) {
                        Text("\(Int(limitMinutes)) minutes / day")
                    }
                }
            }

            Section("Hard Block") {
                Toggle("Block completely", isOn: $isBlocked)
                Text("Overrides the daily limit — the app stays blocked regardless of remaining time.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            if let errorMessage {
                Section {
                    Text(errorMessage)
                        .foregroundStyle(.red)
                        .font(.footnote)
                }
            }
        }
        .navigationTitle("Edit App")
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("Save") {
                    Task { await save() }
                }
                .disabled(isSaving)
            }
        }
        .disabled(isSaving)
    }

    private func save() async {
        isSaving = true
        errorMessage = nil
        defer { isSaving = false }

        var body = PolicyUpdateRequest()
        body.dailyLimitMinutes = isLimited ? .set(Int(limitMinutes)) : .clear
        body.blocked = isBlocked

        do {
            _ = try await appState.apiClient.updatePolicy(
                deviceId: deviceId,
                packageName: row.packageName,
                body: body
            )
            onSaved()
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
