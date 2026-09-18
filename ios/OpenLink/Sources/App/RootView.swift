//
//  RootView.swift
//  OpenLink (parent app)
//
//  There is no sign-in. The app either has paired devices (main UI) or it
//  doesn't (a welcome screen whose only action is "scan a QR code").
//

import SwiftUI

struct RootView: View {
    @EnvironmentObject private var appState: AppState

    var body: some View {
        Group {
            if appState.registry.isEmpty {
                WelcomeView()
            } else {
                MainTabView()
            }
        }
        .animation(.default, value: appState.registry.isEmpty)
    }
}

/// First-run screen. Deliberately explains the trust model in one sentence —
/// people should know what scanning that code actually does.
struct WelcomeView: View {
    @State private var isPairing = false

    var body: some View {
        NavigationView {
            VStack(spacing: 24) {
                Spacer()

                Image(systemName: "qrcode.viewfinder")
                    .font(.system(size: 64))
                    .foregroundStyle(Color.accentColor)

                Text("Pair a child device")
                    .font(.title2.bold())

                Text("""
                OpenLink talks directly to the child's Android device — there is no account and no server in between.

                Open OpenLink on that device, choose “Pair a parent”, and scan the QR code it shows. That code carries the device's certificate, which this phone will trust from then on.
                """)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal)

                Spacer()

                Button {
                    isPairing = true
                } label: {
                    Label("Scan Pairing Code", systemImage: "qrcode.viewfinder")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .padding(.horizontal)
                .padding(.bottom)
            }
            .navigationTitle("OpenLink")
            .navigationBarTitleDisplayMode(.inline)
            .sheet(isPresented: $isPairing) {
                NavigationView {
                    PairDeviceView()
                        .toolbar {
                            ToolbarItem(placement: .cancellationAction) {
                                Button("Cancel") { isPairing = false }
                            }
                        }
                }
                .navigationViewStyle(.stack)
            }
        }
        .navigationViewStyle(.stack)
    }
}
