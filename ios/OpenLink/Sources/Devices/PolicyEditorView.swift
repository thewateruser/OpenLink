//
//  PolicyEditorView.swift
//  OpenLink (parent app)
//
//  Set/clear an app's dailyLimitMinutes and toggle a hard `blocked` switch.
//  PUT /policies/{packageName} on the child device.
//

import SwiftUI

struct PolicyEditorView: View {
    @ObservedObject var session: DeviceSession
    let app: AppEntry

    @Environment(\.dismiss) private var dismiss

    @State private var isLimited: Bool
    @State private var limitMinutes: Double
    @State private var isBlocked: Bool
    @State private var isSaving = false
    @State private var errorMessage: String?

    init(session: DeviceSession, app: AppEntry) {
        self.session = session
        self.app = app
        _isLimited = State(initialValue: app.dailyLimitMinutes != nil)
        _limitMinutes = State(initialValue: Double(app.dailyLimitMinutes ?? 60))
        _isBlocked = State(initialValue: app.isBlocked)
    }

    var body: some View {
        Form {
            Section(app.appName) {
                Text(app.packageName)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text("\(app.todayMinutes) min used today")
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
        .navigationBarTitleDisplayMode(.inline)
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
        // "Not limited" is an explicit JSON null (clear the limit), not an
        // omission — omitting would leave the existing limit in place.
        body.dailyLimitMinutes = isLimited ? .set(Int(limitMinutes)) : .clear
        body.blocked = isBlocked

        do {
            try await session.updatePolicy(packageName: app.packageName, body: body)
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
