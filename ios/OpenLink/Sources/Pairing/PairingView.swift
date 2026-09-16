//
//  PairingView.swift
//  OpenLink (parent app)
//
//  Calls POST /pairing/generate and displays the returned code large and
//  clearly, plus a QR code encoding it and an expiresAt countdown, so the
//  parent can type or scan it into the Android app.
//

import SwiftUI
import UIKit

struct PairingView: View {
    @EnvironmentObject private var appState: AppState

    @State private var pairing: PairingGenerateResponse?
    @State private var isLoading = false
    @State private var errorMessage: String?

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                Text("Pair an Android Device")
                    .font(.title2.bold())

                Text("Open the OpenLink app on the child's Android device and enter this code, or scan the QR code.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)

                if let pairing {
                    if let image = QRCodeGenerator.image(from: pairing.code) {
                        Image(uiImage: image)
                            .interpolation(.none)
                            .resizable()
                            .scaledToFit()
                            .frame(width: 220, height: 220)
                            .padding()
                            .background(Color.white)
                            .clipShape(RoundedRectangle(cornerRadius: 16))
                            .shadow(radius: 2)
                            .accessibilityLabel("QR code for pairing code \(pairing.code)")
                    }

                    Text(pairing.code)
                        .font(.system(size: 48, weight: .bold, design: .monospaced))
                        .tracking(6)
                        .minimumScaleFactor(0.5)
                        .lineLimit(1)
                        .padding(.horizontal)
                        .accessibilityLabel("Pairing code \(pairing.code)")

                    TimelineView(.periodic(from: .now, by: 1)) { context in
                        countdownView(now: context.date, expiresAt: pairing.expiresAt)
                    }
                } else if isLoading {
                    ProgressView()
                        .padding()
                }

                if let errorMessage {
                    Text(errorMessage)
                        .foregroundStyle(.red)
                        .font(.footnote)
                        .multilineTextAlignment(.center)
                }

                Button {
                    Task { await generate() }
                } label: {
                    Label(pairing == nil ? "Generate Code" : "Generate New Code", systemImage: "arrow.clockwise")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(isLoading)
            }
            .padding()
        }
        .navigationTitle("Pair Device")
        .task {
            if pairing == nil {
                await generate()
            }
        }
    }

    @ViewBuilder
    private func countdownView(now: Date, expiresAt: Date) -> some View {
        let remaining = expiresAt.timeIntervalSince(now)
        if remaining > 0 {
            let minutes = Int(remaining) / 60
            let seconds = Int(remaining) % 60
            Text(String(format: "Expires in %02d:%02d", minutes, seconds))
                .font(.footnote)
                .foregroundStyle(.secondary)
                .monospacedDigit()
        } else {
            Text("This code has expired — generate a new one.")
                .font(.footnote)
                .foregroundStyle(.red)
        }
    }

    private func generate() async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            pairing = try await appState.apiClient.generatePairingCode()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

#Preview {
    NavigationStack {
        PairingView()
    }
    .environmentObject(AppState())
}
