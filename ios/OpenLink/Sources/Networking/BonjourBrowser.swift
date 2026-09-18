//
//  BonjourBrowser.swift
//  OpenLink (parent app)
//
//  Browses `_openlink._tcp` on the local network with NWBrowser, then
//  resolves each advertisement to a concrete host:port.
//
//  Two jobs:
//    1. Speed. A freshly-seen LAN address is the fastest thing to dial, and
//       it's correct even after the child's DHCP lease changes — which is the
//       one case where a stored LAN address goes stale silently.
//    2. Presence. "This paired device is here on your Wi-Fi" is a useful
//       thing to show even before anything is fetched.
//
//  NWBrowser reports services, not addresses, so each result is resolved by
//  opening a throwaway TCP connection to the `.service` endpoint and reading
//  back `currentPath.remoteEndpoint`. That's the supported way to get an
//  address out of Network.framework without NetService.
//
//  TXT RECORD: docs/PROTOCOL.md doesn't specify one, but the Android
//  advertiser (android/.../server/NsdAdvertiser.kt) publishes `v`, `id`
//  (the deviceId) and `fp` (the certificate fingerprint). We read `id` to
//  match a sighting to a paired device. `fp` is deliberately ignored — the
//  pin comes from the QR code, and a fingerprint advertised over mDNS by
//  whoever is on the network is not a trust anchor.
//

import Foundation
import Network

@MainActor
final class BonjourBrowser: ObservableObject {
    static let serviceType = "_openlink._tcp"

    struct Discovery: Equatable {
        let deviceId: String?
        let serviceName: String
        let endpoint: DeviceEndpoint
        let seenAt: Date
    }

    /// Everything currently advertised, keyed by Bonjour service name.
    @Published private(set) var discoveries: [String: Discovery] = [:]
    @Published private(set) var isBrowsing = false
    @Published private(set) var permissionDenied = false

    private var browser: NWBrowser?
    private var resolvers: [String: NWConnection] = [:]

    /// Called whenever a device is (re)discovered, so the registry can feed
    /// the address into the right `DeviceSession`.
    var onDiscovery: ((Discovery) -> Void)?

    func start() {
        guard browser == nil else { return }

        let parameters = NWParameters()
        parameters.includePeerToPeer = false

        let descriptor = NWBrowser.Descriptor.bonjourWithTXTRecord(type: Self.serviceType, domain: nil)
        let browser = NWBrowser(for: descriptor, using: parameters)

        browser.stateUpdateHandler = { [weak self] state in
            Task { @MainActor in
                guard let self else { return }
                switch state {
                case .ready:
                    self.isBrowsing = true
                    self.permissionDenied = false
                case .cancelled:
                    self.isBrowsing = false
                case .failed(let error):
                    self.isBrowsing = false
                    self.permissionDenied = Self.isPermissionDenial(error)
                case .waiting(let error):
                    // A missing local-network permission can surface either
                    // here or as an outright failure, depending on iOS
                    // version, so both are checked the same way.
                    self.isBrowsing = false
                    self.permissionDenied = Self.isPermissionDenial(error)
                default:
                    break
                }
            }
        }

        browser.browseResultsChangedHandler = { [weak self] results, _ in
            Task { @MainActor in
                self?.handle(results: results)
            }
        }

        self.browser = browser
        browser.start(queue: .main)
    }

    /// Whether an NWBrowser error is iOS refusing local-network access.
    ///
    /// This is a HINT for the pre-scan banner, not the basis of any hard
    /// claim: the error shape differs across iOS versions, and a browser can
    /// also sit in `.waiting` for perfectly ordinary reasons. The actionable
    /// diagnosis comes from the -1009 a real connection attempt returns
    /// (LocalNetworkAccess.swift), which is stable.
    private static func isPermissionDenial(_ error: NWError) -> Bool {
        switch error {
        case .posix(let code):
            return code == .EPERM || code == .EACCES
        case .dns(let code):
            // kDNSServiceErr_PolicyDenied from dns_sd.h.
            return code == -65570
        default:
            return false
        }
    }

    func stop() {
        browser?.cancel()
        browser = nil
        isBrowsing = false
        for connection in resolvers.values {
            connection.cancel()
        }
        resolvers.removeAll()
    }

    /// Endpoints seen for a given deviceId within the freshness window.
    func endpoints(forDeviceId deviceId: String, freshFor interval: TimeInterval = 120) -> [DeviceEndpoint] {
        let cutoff = Date().addingTimeInterval(-interval)
        return discoveries.values
            .filter { $0.deviceId == deviceId && $0.seenAt >= cutoff }
            .map(\.endpoint)
    }

    func isPresentOnLocalNetwork(deviceId: String) -> Bool {
        !endpoints(forDeviceId: deviceId).isEmpty
    }

    // MARK: - Result handling

    private func handle(results: Set<NWBrowser.Result>) {
        var liveNames = Set<String>()

        for result in results {
            guard case let .service(name, type, domain, _) = result.endpoint else { continue }
            liveNames.insert(name)

            // The deviceId from the TXT record, if the child publishes one.
            var deviceId: String?
            if case let .bonjour(txt) = result.metadata {
                deviceId = txt["id"] ?? txt["deviceId"]
            }

            // Re-resolving an unchanged service every callback would be
            // wasteful; only resolve names we don't already have an address
            // for (or whose resolution is stale).
            if let existing = discoveries[name],
               Date().timeIntervalSince(existing.seenAt) < 30,
               existing.deviceId == (deviceId ?? existing.deviceId) {
                continue
            }

            resolve(serviceName: name, type: type, domain: domain, deviceId: deviceId)
        }

        // Forget services that stopped advertising.
        for name in Array(discoveries.keys) where !liveNames.contains(name) {
            discoveries.removeValue(forKey: name)
            resolvers.removeValue(forKey: name)?.cancel()
        }
    }

    /// Resolves a Bonjour service to host:port by briefly connecting to it.
    private func resolve(serviceName: String, type: String, domain: String, deviceId: String?) {
        resolvers[serviceName]?.cancel()

        let endpoint = NWEndpoint.service(name: serviceName, type: type, domain: domain, interface: nil)
        // Plain TCP: we only want the resolved address. The real connection
        // is made afterwards by URLSession, with certificate pinning.
        let connection = NWConnection(to: endpoint, using: .tcp)
        resolvers[serviceName] = connection

        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                let remote = connection.currentPath?.remoteEndpoint
                connection.cancel()
                Task { @MainActor in
                    self?.finishResolve(serviceName: serviceName, deviceId: deviceId, remote: remote)
                }
            case .failed, .cancelled:
                connection.cancel()
                Task { @MainActor in
                    self?.resolvers.removeValue(forKey: serviceName)
                }
            default:
                break
            }
        }
        connection.start(queue: .main)
    }

    private func finishResolve(serviceName: String, deviceId: String?, remote: NWEndpoint?) {
        resolvers.removeValue(forKey: serviceName)
        guard case let .hostPort(host, port)? = remote else { return }

        let hostText: String
        switch host {
        case .ipv4(let address):
            hostText = "\(address)"
        case .ipv6(let address):
            // Network.framework renders scoped addresses as "fe80::1%en0";
            // the zone id is meaningless to URLSession, so strip it.
            hostText = String("\(address)".split(separator: "%").first ?? "")
        case .name(let name, _):
            hostText = name
        @unknown default:
            return
        }
        guard !hostText.isEmpty else { return }

        // No TXT `id` means we can't tell which paired device this is. The
        // sighting is still recorded (useful for diagnostics) but it will
        // never be matched to a session — better than guessing from the
        // service name, which is "OpenLink <device name>" and could collide.
        let discovery = Discovery(
            deviceId: deviceId,
            serviceName: serviceName,
            endpoint: DeviceEndpoint(host: hostText, port: Int(port.rawValue), source: .bonjour),
            seenAt: Date()
        )
        discoveries[serviceName] = discovery
        onDiscovery?(discovery)
    }
}
