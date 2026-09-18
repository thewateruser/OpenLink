//
//  EndpointsView.swift
//  OpenLink (parent app)
//
//  The addresses this app will try for a device, where they came from, and
//  the manual escape hatch for typing one in (a Tailscale hostname, say).
//
//  Most people should never need this screen: addresses are seeded from the
//  pairing QR and then learned automatically from every `GET /device`
//  response. It exists for the case where automatic learning can't help —
//  e.g. the overlay network was never up while the app was connected.
//

import SwiftUI

struct EndpointsView: View {
    @ObservedObject var session: DeviceSession

    @Environment(\.dismiss) private var dismiss

    @State private var newEndpoint = ""
    @State private var errorMessage: String?

    var body: some View {
        Form {
            Section {
                Text("OpenLink tries these in order of expected speed — addresses on your Wi-Fi first, then overlay-network addresses — and uses whichever answers first.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section("Known Addresses") {
                if session.device.endpoints.isEmpty {
                    Text("None. Add one below, or re-pair the device.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                ForEach(EndpointRanker.rank(session.device.endpoints)) { endpoint in
                    endpointRow(endpoint)
                }
            }

            Section("Add an Address") {
                TextField("100.101.102.103:8765 or myphone.tail1234.ts.net", text: $newEndpoint)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                Text("Port \(openLinkDefaultPort) is assumed if you don't give one.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Button("Add") { add() }
                    .disabled(newEndpoint.trimmingCharacters(in: .whitespaces).isEmpty)
            }

            if let errorMessage {
                Section {
                    Text(errorMessage)
                        .font(.footnote)
                        .foregroundStyle(.red)
                }
            }

            Section {
                Button("Reconnect Now") {
                    session.reconnect()
                    dismiss()
                }
            }
        }
        .navigationTitle("Addresses")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Done") { dismiss() }
            }
        }
    }

    @ViewBuilder
    private func endpointRow(_ endpoint: DeviceEndpoint) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(endpoint.authority)
                    .font(.system(.body, design: .monospaced))
                Text(sourceLabel(endpoint.source))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            if endpoint.lastSucceededAt != nil {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(.green)
                    .accessibilityLabel("Worked recently")
            }
        }
        .swipeActions {
            Button("Remove", role: .destructive) {
                session.removeEndpoint(endpoint)
            }
        }
    }

    private func sourceLabel(_ source: DeviceEndpoint.Source) -> String {
        switch source {
        case .pairingQR: return "From the pairing QR code"
        case .learned: return "Learned from the device"
        case .bonjour: return "Seen on this Wi-Fi"
        case .manual: return "Added by you"
        }
    }

    private func add() {
        errorMessage = nil
        do {
            try session.addManualEndpoint(newEndpoint)
            newEndpoint = ""
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
