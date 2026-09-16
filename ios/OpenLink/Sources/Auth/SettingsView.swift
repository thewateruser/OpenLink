//
//  SettingsView.swift
//  OpenLink (parent app)
//
//  Lets a signed-in parent change the self-hosted server address later, and
//  sign out.
//

import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var appState: AppState
    @State private var serverURLText: String = ""
    @State private var showSavedConfirmation = false

    var body: some View {
        Form {
            Section("Self-Hosted Server") {
                TextField("https://your-server.example.com", text: $serverURLText)
                    #if os(iOS)
                    .textInputAutocapitalization(.never)
                    .keyboardType(.URL)
                    #endif
                    .autocorrectionDisabled()

                Button("Save") {
                    appState.serverBaseURL = serverURLText.trimmingCharacters(in: .whitespaces)
                    appState.reconnectSocketIfNeeded()
                    showSavedConfirmation = true
                }
                .disabled(serverURLText.trimmingCharacters(in: .whitespaces).isEmpty)
            }

            Section("Realtime") {
                Label(
                    appState.socketManager.isConnected ? "Connected" : "Not connected (using polling)",
                    systemImage: appState.socketManager.isConnected ? "bolt.horizontal.circle.fill" : "bolt.horizontal.circle"
                )
                .foregroundStyle(appState.socketManager.isConnected ? .green : .secondary)
            }

            Section {
                Button("Log Out", role: .destructive) {
                    appState.logout()
                }
            }
        }
        .navigationTitle("Settings")
        .onAppear {
            serverURLText = appState.serverBaseURL
        }
        .alert("Saved", isPresented: $showSavedConfirmation) {
            Button("OK", role: .cancel) {}
        }
    }
}

#Preview {
    NavigationStack {
        SettingsView()
    }
    .environmentObject(AppState())
}
