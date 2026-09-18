//
//  ScheduleEditorView.swift
//  OpenLink (parent app)
//
//  Add/remove ScheduleWindows (days-of-week + start/end time) and save via
//  PUT /schedule, which replaces the device's whole downtime schedule.
//
//  Times are minute-of-day in the CHILD DEVICE's local time, which is what
//  enforcement uses. The pickers below therefore show wall-clock times that
//  belong to that device, not to this phone.
//
//  Each window also carries its own allow-list: the apps it lets through.
//  That is what makes a 22:00-07:00 window safe to turn on at all — a child
//  who cannot dial a phone overnight is a worse outcome than one who stays
//  up late.
//

import SwiftUI

struct ScheduleEditorView: View {
    @ObservedObject var session: DeviceSession

    @Environment(\.dismiss) private var dismiss

    @State private var windows: [ScheduleWindow]
    @State private var isSaving = false
    @State private var errorMessage: String?

    init(session: DeviceSession, initialWindows: [ScheduleWindow]) {
        self.session = session
        _windows = State(initialValue: initialWindows)
    }

    var body: some View {
        Form {
            Section {
                Text("Times are in the child device's local time.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            if windows.isEmpty {
                Section {
                    Text("No downtime windows. Add one below to block apps during bedtime or study time — you can let specific apps, like the phone, through each one.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }

            ForEach($windows) { $window in
                Section {
                    windowEditor($window)
                    exemptAppsLink($window)
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

    /// Kept out of the VStack above so it is a direct child of the Section:
    /// a NavigationLink only renders as a real row, with its disclosure
    /// chevron, when the Form can see it as one.
    @ViewBuilder
    private func exemptAppsLink(_ window: Binding<ScheduleWindow>) -> some View {
        NavigationLink {
            ExemptAppsPicker(
                apps: session.apps,
                selection: window.exemptPackages,
                windowDescription: Self.describe(window.wrappedValue)
            )
        } label: {
            HStack {
                Text("Always allowed")
                Spacer()
                Text(Self.exemptSummary(window.wrappedValue, apps: session.apps))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
    }

    /// "Nothing" rather than "None": the row reads as an answer to "what gets
    /// through this window?", and the honest answer for an empty list is that
    /// the window blocks everything.
    private static func exemptSummary(_ window: ScheduleWindow, apps: [AppEntry]) -> String {
        guard !window.exemptPackages.isEmpty else { return "Nothing" }
        let names = window.exemptPackages.map { packageName in
            apps.first(where: { $0.packageName == packageName })?.appName ?? packageName
        }
        .sorted()

        if names.count <= 2 { return names.joined(separator: ", ") }
        return "\(names[0]), \(names[1]) +\(names.count - 2)"
    }

    private static func describe(_ window: ScheduleWindow) -> String {
        if let label = window.label, !label.isEmpty { return label }
        let start = String(format: "%02d:%02d", window.startMinute / 60, window.startMinute % 60)
        let end = String(format: "%02d:%02d", window.endMinute / 60, window.endMinute % 60)
        return "\(start)–\(end)"
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
            try await session.saveSchedule(windows)
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}


/// Picks the apps one downtime window lets through.
///
/// Shows every app the child has, including ones with no policy of their own:
/// the whole point is to name apps that would otherwise be blocked, so
/// filtering to "apps with limits" would hide exactly the ones wanted here.
/// System apps are behind a toggle for the same reason they are on the device
/// list — the dialer is a system app on most phones, so hiding them by default
/// would bury the single most likely choice.
private struct ExemptAppsPicker: View {
    let apps: [AppEntry]
    @Binding var selection: [String]
    let windowDescription: String

    @State private var showsSystemApps = true
    @State private var query = ""

    private var visibleApps: [AppEntry] {
        apps
            .filter { showsSystemApps || !$0.isSystemApp || selection.contains($0.packageName) }
            .filter {
                query.isEmpty
                    || $0.appName.localizedCaseInsensitiveContains(query)
                    || $0.packageName.localizedCaseInsensitiveContains(query)
            }
            .sorted { $0.appName.localizedCaseInsensitiveCompare($1.appName) == .orderedAscending }
    }

    /// Anything selected but no longer installed (or not in the fetched list)
    /// would silently vanish on save, so it is listed rather than dropped.
    private var missingSelections: [String] {
        selection.filter { packageName in !apps.contains(where: { $0.packageName == packageName }) }
    }

    var body: some View {
        List {
            Section {
                Text("These apps keep working during “\(windowDescription)”. Everything else is blocked while it's running.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            if apps.isEmpty {
                Section {
                    Text("No app list yet — open the device screen while it's connected to load one.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }

            if !missingSelections.isEmpty {
                Section("Not installed any more") {
                    ForEach(missingSelections, id: \.self) { packageName in
                        Button {
                            toggle(packageName)
                        } label: {
                            HStack {
                                Text(packageName).font(.footnote)
                                Spacer()
                                Image(systemName: "checkmark").foregroundStyle(Color.accentColor)
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
            }

            Section {
                ForEach(visibleApps) { app in
                    Button {
                        toggle(app.packageName)
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(app.appName)
                                Text(app.packageName)
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            if selection.contains(app.packageName) {
                                Image(systemName: "checkmark")
                                    .foregroundStyle(Color.accentColor)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                }
            } header: {
                Toggle("Show system apps", isOn: $showsSystemApps)
                    .font(.footnote)
                    .textCase(nil)
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Always Allowed")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func toggle(_ packageName: String) {
        if let index = selection.firstIndex(of: packageName) {
            selection.remove(at: index)
        } else {
            selection.append(packageName)
        }
    }
}
