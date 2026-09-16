//
//  QRScannerView.swift
//  OpenLink (parent app)
//
//  AVFoundation QR scanner wrapped as a SwiftUI view. Requires
//  NSCameraUsageDescription (declared in project.yml).
//
//  It reports every decoded string it sees; deciding whether a string is a
//  valid `openlink://pair?...` URI is `PairingURI`'s job, and the caller
//  stops the session as soon as it accepts one.
//

import SwiftUI
import UIKit
import AVFoundation
import AudioToolbox

enum CameraPermission {
    case notDetermined
    case authorized
    case denied

    static var current: CameraPermission {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: return .authorized
        case .notDetermined: return .notDetermined
        default: return .denied
        }
    }

    static func request() async -> CameraPermission {
        let granted = await AVCaptureDevice.requestAccess(for: .video)
        return granted ? .authorized : .denied
    }
}

struct QRScannerView: UIViewControllerRepresentable {
    /// Called with each decoded payload. Return `true` to stop scanning.
    var onScan: (String) -> Bool
    var onError: (Error) -> Void

    func makeUIViewController(context: Context) -> QRScannerViewController {
        let controller = QRScannerViewController()
        controller.onScan = onScan
        controller.onError = onError
        return controller
    }

    func updateUIViewController(_ uiViewController: QRScannerViewController, context: Context) {
        uiViewController.onScan = onScan
        uiViewController.onError = onError
    }
}

enum ScannerError: LocalizedError {
    case noCamera
    case cannotConfigure

    var errorDescription: String? {
        switch self {
        case .noCamera:
            return "This device doesn't have a usable camera."
        case .cannotConfigure:
            return "Couldn't start the camera."
        }
    }
}

final class QRScannerViewController: UIViewController {
    var onScan: ((String) -> Bool)?
    var onError: ((Error) -> Void)?

    private let session = AVCaptureSession()
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private let sessionQueue = DispatchQueue(label: "org.openlink.parent.camera")
    private var hasAccepted = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        configureSession()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        startRunning()
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        sessionQueue.async { [session] in
            if session.isRunning { session.stopRunning() }
        }
    }

    private func configureSession() {
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: device) else {
            onError?(ScannerError.noCamera)
            return
        }

        session.beginConfiguration()
        guard session.canAddInput(input) else {
            session.commitConfiguration()
            onError?(ScannerError.cannotConfigure)
            return
        }
        session.addInput(input)

        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else {
            session.commitConfiguration()
            onError?(ScannerError.cannotConfigure)
            return
        }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        // Must be set after the output is added to the session.
        output.metadataObjectTypes = [.qr]
        session.commitConfiguration()

        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.bounds
        view.layer.addSublayer(layer)
        previewLayer = layer
    }

    private func startRunning() {
        hasAccepted = false
        sessionQueue.async { [session] in
            if !session.isRunning { session.startRunning() }
        }
    }
}

extension QRScannerViewController: AVCaptureMetadataOutputObjectsDelegate {
    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard !hasAccepted else { return }
        for object in metadataObjects {
            guard let readable = object as? AVMetadataMachineReadableCodeObject,
                  readable.type == .qr,
                  let value = readable.stringValue else { continue }

            if onScan?(value) == true {
                hasAccepted = true
                AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
                sessionQueue.async { [session] in
                    if session.isRunning { session.stopRunning() }
                }
                return
            }
        }
    }
}
