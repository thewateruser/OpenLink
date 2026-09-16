//
//  ScheduleEditorView.swift
//  OpenLink (parent app)
//
//  Add/remove ScheduleWindows (days-of-week + start/end time) and save via
//  PUT /devices/:deviceId/schedule, which replaces the device's whole
//  downtime schedule.
//

import SwiftUI

struct ScheduleEditorView: View {
    let deviceId: String
    var onSaved: () -> Void

    @EnvironmentObject private var appState: AppState
    @Environment(\.dismiss) private var dismiss

    @State private var windows: [ScheduleWindow]
    @State private var isSaving = false
    @State private var errorMessage: String?

    init(deviceId: String, initialWindows: [ScheduleWindow], onSaved: @escaping () -> Void) {
        self.deviceId = deviceId
        self.onSaved = onSaved
        _windows = State(initialValue: initialWindows)
    }

    var body: some View {
        Form {
            if windows.isEmpty {
                Section {
                    Text("No downtime windows. Add one below to block apps during bedtime or study time.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }

            ForEach($windows) { $window in
                Section {
                    windowEditor($window)
                    Button(role: .destructive) {
                        windows.removeAll { $0.id == window.id }
                    } label: {
                        Label("Delete Window", systemImage: "trash")
                    }
                }
            }

            Section {
                Button {
                    // Sensible default: weekdays (Mon-Fri), 8pm-7am.
                    windows.append(
                        ScheduleWindow(daysOfWeek: 0b0111110, startMinute: 20 * 60, endMinute: 7 * 60, label: nil)
                    )
                } label: {
                    Label("Add Downtime Window", systemImage: "plus.circle")
                }
            }

            if let errorMessage {
                Section {
                    Text(errorMessage)
                        .foregroundStyle(.red)
                        .font(.footnote)
                }
            }
        }
        .navigationTitle("Downtime Schedule")
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

    @ViewBuilder
    private func windowEditor(_ window: Binding<ScheduleWindow>) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Days")
                .font(.caption)
                .foregroundStyle(.secondary)
            HStack(spacing: 8) {
                ForEach(0..<7, id: \.self) { index in
                    dayToggle(window, index: index)
                }
            }

            DatePicker(
                "Start",
                selection: minutesBinding(window, keyPath: \.startMinute),
                displayedComponents: .hourAndMinute
            )
            DatePicker(
                "End",
                selection: minutesBinding(window, keyPath: \.endMinute),
                displayedComponents: .hourAndMinute
            )

            TextField(
                "Label (optional)",
                text: Binding(
                    get: { window.wrappedValue.label ?? "" },
                    set: { window.wrappedValue.label = $0.isEmpty ? nil : $0 }
                )
            )
        }
        .padding(.vertical, 4)
    }

    @ViewBuilder
    private func dayToggle(_ window: Binding<ScheduleWindow>, index: Int) -> some View {
        let included = window.wrappedValue.includesDay(index)
        Button {
            window.wrappedValue.setDay(index, included: !included)
        } label: {
            Text(ScheduleWindow.dayLabels[index])
                .font(.caption.bold())
                .frame(width: 34, height: 34)
                .background(included ? Color.accentColor : Color(.systemGray5))
                .foregroundStyle(included ? Color.white : Color.primary)
                .clipShape(Circle())
        }
        .buttonStyle(.plain)
    }

    private func minutesBinding(_ window: Binding<ScheduleWindow>, keyPath: WritableKeyPath<ScheduleWindow, Int>) -> Binding<Date> {
        Binding<Date>(
            get: { Self.date(fromMinutesSinceMidnight: window.wrappedValue[keyPath: keyPath]) },
            set: { window.wrappedValue[keyPath: keyPath] = Self.minutesSinceMidnight(from: $0) }
        )
    }

    private static func date(fromMinutesSinceMidnight minutes: Int) -> Date {
        let calendar = Calendar.current
        var components = calendar.dateComponents([.year, .month, .day], from: Date())
        components.hour = minutes / 60
        components.minute = minutes % 60
        return calendar.date(from: components) ?? Date()
    }

    private static func minutesSinceMidnight(from date: Date) -> Int {
        let components = Calendar.current.dateComponents([.hour, .minute], from: date)
        return (components.hour ?? 0) * 60 + (components.minute ?? 0)
    }

    private func save() async {
        isSaving = true
        errorMessage = nil
        defer { isSaving = false }
        do {
            try await appState.apiClient.updateSchedule(deviceId: deviceId, windows: windows)
            onSaved()
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
