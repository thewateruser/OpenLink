//
//  PairDeviceView.swift
//  OpenLink (parent app)
//
//  The first screen a new user sees, and the "+" flow afterwards: point the
//  camera at the QR code on the child device's screen, then run the handshake
//  from docs/PROTOCOL.md.
//

import SwiftUI

struct PairDeviceView: View {
    @EnvironmentObject private var appState: AppState
    @Environment(\.dismiss) private var dismiss

    /// Called with the freshly-paired device id so the caller can navigate to it.
    var onPaired: ((String) -> Void)?

    @State private var permission: CameraPermission = CameraPermission.current
    @State private var phase: Phase = .scanning
    @State private var errorMessage: String?
    @State private var scannedName: String?

    private enum Phase: Equatable {
        case scanning
        case pairing
        case paired(deviceName: String)
    }

    var body: some View {
        VStack(spacing: 0) {
            switch phase {
            case .scanning:
                scannerSection
            case .pairing:
                statusSection(
                    systemImage: "antenna.radiowaves.left.and.right",
                    title: "Pairing with \(scannedName ?? "the device")…",
                    detail: "Verifying the device's certificate and exchanging a token.",
                    showsProgress: true
                )
            case .paired(let deviceName):
                statusSection(
                    systemImage: "checkmark.seal.fill",
                    title: "Paired with \(deviceName)",
                    detail: "This device's certificate is now pinned on this phone. You can manage it from the Devices tab.",
                    showsProgress: false
                )
            }

            if let errorMessage {
                Text(errorMessage)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
                    .padding()
            }

            footer
        }
        .navigationTitle("Pair Device")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            if permission == .notDetermined {
                permission = await CameraPermission.request()
            }
        }
    }

    // MARK: - Sections

    @ViewBuilder
    private var scannerSection: some View {
        switch permission {
        case .authorized:
            ZStack {
                QRScannerView(
                    onScan: { payload in handleScan(payload) },
                    onError: { error in errorMessage = error.localizedDescription }
                )
                .ignoresSafeArea(edges: .horizontal)

                RoundedRectangle(cornerRadius: 24)
                    .stroke(Color.white.opacity(0.9), lineWidth: 3)
                    .frame(width: 240, height: 240)
                    .shadow(radius: 8)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.black)

        case .notDetermined:
            statusSection(
                systemImage: "camera",
                title: "Camera access needed",
                detail: "OpenLink uses the camera only to read the pairing QR code shown on the child device.",
                showsProgress: true
            )

        case .denied:
            VStack(spacing: 16) {
                Image(systemName: "camera.badge.ellipsis")
                    .font(.system(size: 40))
                    .foregroundStyle(.secondary)
                Text("Camera Access Denied")
                    .font(.headline)
                Text("Pairing needs the camera to read the QR code on the child device. Enable it in Settings → OpenLink → Camera.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    Link("Open Settings", destination: url)
                        .buttonStyle(.borderedProminent)
                }
            }
            .padding()
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    @ViewBuilder
    private func statusSection(systemImage: String, title: String, detail: String, showsProgress: Bool) -> some View {
        VStack(spacing: 16) {
            if showsProgress {
                ProgressView()
                    .controlSize(.large)
            } else {
                Image(systemName: systemImage)
                    .font(.system(size: 44))
                    .foregroundStyle(.green)
            }
            Text(title)
                .font(.headline)
                .multilineTextAlignment(.center)
            Text(detail)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    @ViewBuilder
    private var footer: some View {
        VStack(spacing: 12) {
            switch phase {
            case .scanning:
                Text("On the child's Android device open OpenLink → Pair a parent, and point this camera at the QR code it shows.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            case .pairing:
                EmptyView()
            case .paired:
                Button("Done") { dismiss() }
                    .buttonStyle(.borderedProminent)
            }
        }
        .padding()
    }

    // MARK: - Actions

    /// Returns `true` to tell the scanner to stop.
    private func handleScan(_ payload: String) -> Bool {
        guard phase == .scanning else { return true }
        let uri: PairingURI
        do {
            uri = try PairingURI.parse(payload)
        } catch {
            // Not an OpenLink QR (or a malformed one). Keep scanning, but say
            // why if the user is clearly pointing at the wrong thing.
            if payload.lowercased().hasPrefix("\(PairingURI.scheme)://") {
                errorMessage = error.localizedDescription
            }
            return false
        }

        if appState.registry.contains(deviceId: uri.deviceId) {
            errorMessage = PairingError.alreadyPaired(deviceName: uri.deviceName).localizedDescription
            return false
        }

        errorMessage = nil
        scannedName = uri.deviceName
        phase = .pairing

        Task {
            do {
                let deviceId = try await appState.registry.pair(with: uri)
                phase = .paired(deviceName: scannedName ?? uri.deviceName)
                onPaired?(deviceId)
            } catch {
                errorMessage = error.localizedDescription
                phase = .scanning
            }
        }
        return true
    }
}
