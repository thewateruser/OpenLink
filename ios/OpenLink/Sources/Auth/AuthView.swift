//
//  AuthView.swift
//  OpenLink (parent app)
//
//  Register / login screen, plus the self-hosted server base URL field —
//  this is not a hosted SaaS, so there is no default/hardcoded domain.
//

import SwiftUI

struct AuthView: View {
    private enum Mode: String, CaseIterable, Identifiable {
        case login = "Log In"
        case register = "Register"
        var id: String { rawValue }
    }

    @EnvironmentObject private var appState: AppState

    @State private var mode: Mode = .login
    @State private var serverURLText: String = ""
    @State private var email = ""
    @State private var password = ""
    @State private var familyName = ""
    @State private var isSubmitting = false
    @State private var errorMessage: String?

    var body: some View {
        Form {
            Section("Self-Hosted Server") {
                TextField("https://your-server.example.com", text: $serverURLText)
                    #if os(iOS)
                    .textInputAutocapitalization(.never)
                    .keyboardType(.URL)
                    #endif
                    .autocorrectionDisabled()
                Text("OpenLink talks to a server you host yourself — there's no default domain. Ask whoever runs it for the address.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section {
                Picker("Mode", selection: $mode) {
                    ForEach(Mode.allCases) { mode in
                        Text(mode.rawValue).tag(mode)
                    }
                }
                .pickerStyle(.segmented)

                TextField("Email", text: $email)
                    #if os(iOS)
                    .textInputAutocapitalization(.never)
                    .keyboardType(.emailAddress)
                    #endif
                    .autocorrectionDisabled()
                SecureField("Password", text: $password)

                if mode == .register {
                    TextField("Family name", text: $familyName)
                }
            }

            if let errorMessage {
                Section {
                    Text(errorMessage)
                        .foregroundStyle(.red)
                        .font(.footnote)
                }
            }

            Section {
                Button {
                    Task { await submit() }
                } label: {
                    if isSubmitting {
                        ProgressView()
                            .frame(maxWidth: .infinity)
                    } else {
                        Text(mode == .login ? "Log In" : "Create Family & Register")
                            .frame(maxWidth: .infinity)
                    }
                }
                .disabled(!isFormValid || isSubmitting)
            }
        }
        .navigationTitle("OpenLink")
        .onAppear {
            serverURLText = appState.serverBaseURL
        }
    }

    private var isFormValid: Bool {
        guard !serverURLText.trimmingCharacters(in: .whitespaces).isEmpty,
              !email.trimmingCharacters(in: .whitespaces).isEmpty,
              !password.isEmpty else { return false }
        if mode == .register {
            return !familyName.trimmingCharacters(in: .whitespaces).isEmpty
        }
        return true
    }

    private func submit() async {
        errorMessage = nil
        isSubmitting = true
        defer { isSubmitting = false }

        appState.serverBaseURL = serverURLText.trimmingCharacters(in: .whitespaces)

        do {
            let token: String
            switch mode {
            case .login:
                let response = try await appState.apiClient.login(email: email, password: password)
                token = response.token
            case .register:
                let response = try await appState.apiClient.register(
                    email: email,
                    password: password,
                    familyName: familyName
                )
                token = response.token
            }
            appState.completeAuth(token: token)
            if let storageError = appState.lastError {
                errorMessage = storageError
            }
            PushNotificationManager.shared.requestAuthorizationAndRegister()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

#Preview {
    NavigationStack {
        AuthView()
    }
    .environmentObject(AppState())
}
