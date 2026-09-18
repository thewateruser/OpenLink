//
//  LocalNetworkAccess.swift
//  OpenLink (parent app)
//
//  Turns "The Internet connection appears to be offline." into something a
//  person can act on.
//
//  That message is what URLSession returns (NSURLErrorNotConnectedToInternet,
//  -1009) when iOS has no usable network path from this app to the address it
//  was given. For an address on the public internet it means what it says. For
//  a home-network address like 192.168.1.5 it almost never does — the phone is
//  plainly online — and there are exactly two realistic causes:
//
//    1. Local Network access is off for OpenLink. Since iOS 14 an app cannot
//       reach LAN addresses without it, and URLSession's failure mode is this
//       same misleading -1009. Critically, iOS only ever asks ONCE: an app
//       cannot re-prompt, so once it's been dismissed or denied the only way
//       back is Settings. Nothing in the error text hints at any of that.
//    2. The iPhone isn't on Wi-Fi at all — cellular has no route to a private
//       subnet.
//
//  Those two need opposite actions, so `LocalNetworkMonitor` watches for a
//  Wi-Fi path (which needs no permission of its own) and `diagnose` uses it to
//  tell them apart instead of making the user guess.
//
//  The old text said "Make sure both devices are on the same Wi-Fi", which is
//  the one thing that is usually already true when this fires.
//

import Foundation
import Network

/// Why a connection attempt produced "no network path".
enum LocalNetworkDiagnosis: Equatable {
    /// Not a local-network problem — report the underlying error as-is.
    case notLocal
    /// This iPhone has no Wi-Fi path, so a private address is unroutable.
    case noWiFiPath
    /// Wi-Fi is up (or its state is unknown) and the address is local, so the
    /// Local Network privacy permission is the remaining explanation.
    case localNetworkPermission
}

enum LocalNetworkAccess {

    /// True for addresses that only exist on the local network, and so only
    /// reachable with Local Network permission.
    ///
    /// Deliberately excludes Tailscale/WireGuard addresses: those ride a
    /// tunnel interface that local-network privacy doesn't gate, so a failure
    /// to reach one is a different problem with different advice.
    static func isLocalNetworkAddress(_ host: String) -> Bool {
        let trimmed = host.trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        guard !trimmed.isEmpty else { return false }

        if EndpointRanker.isTailscaleCGNAT(trimmed) { return false }
        if EndpointRanker.isPrivateIPv4(trimmed) { return true }
        if EndpointRanker.isLinkLocal(trimmed) { return true }
        if EndpointRanker.isIPv6UniqueLocalOrLinkLocal(trimmed) { return true }
        if trimmed.lowercased().hasSuffix(".local") { return true }
        return false
    }

    static func containsLocalNetworkAddress(_ endpoints: [DeviceEndpoint]) -> Bool {
        endpoints.contains { isLocalNetworkAddress($0.host) }
    }

    /// True for the URLError codes iOS reports when it has no path to the
    /// destination, as opposed to reaching it and being refused.
    ///
    /// `.notConnectedToInternet` is the local-network-privacy signature.
    /// `.networkConnectionLost` shows up on the same path in some iOS
    /// versions, and is indistinguishable to us.
    static func indicatesNoNetworkPath(_ error: Error) -> Bool {
        guard let urlError = error as? URLError else { return false }
        switch urlError.code {
        case .notConnectedToInternet, .networkConnectionLost, .dataNotAllowed:
            return true
        default:
            return false
        }
    }

    /// Classifies a failed connection attempt.
    ///
    /// `hasWiFiPath` is nil when the monitor hasn't reported yet; an unknown
    /// Wi-Fi state is treated as "probably on Wi-Fi", because sending someone
    /// to check their Wi-Fi when the real problem is a permission toggle
    /// wastes more of their time than the reverse.
    static func diagnose(
        error: Error,
        endpoints: [DeviceEndpoint],
        hasWiFiPath: Bool?
    ) -> LocalNetworkDiagnosis {
        guard indicatesNoNetworkPath(error), containsLocalNetworkAddress(endpoints) else {
            return .notLocal
        }
        return hasWiFiPath == false ? .noWiFiPath : .localNetworkPermission
    }

    /// The user-facing explanation for a diagnosis. Returns nil for
    /// `.notLocal`, where the underlying error is the better message.
    static func explanation(for diagnosis: LocalNetworkDiagnosis) -> String? {
        switch diagnosis {
        case .notLocal:
            return nil
        case .noWiFiPath:
            return """
            This iPhone isn't on Wi-Fi, and the child device's addresses are \
            home-network addresses that cellular can't reach. Join the same \
            Wi-Fi network and try again.
            """
        case .localNetworkPermission:
            return """
            iOS says there's no route to the child device, which almost always \
            means Local Network access is turned off for OpenLink.

            Open Settings → OpenLink and turn on Local Network, then scan the \
            code again. iOS only asks for this once, and an app can't ask a \
            second time, so if you didn't see the prompt — or tapped Don't \
            Allow — Settings is the only way to switch it back on.
            """
        }
    }
}

/// Watches for a Wi-Fi path so `diagnose` can tell "permission is off" from
/// "this phone isn't on Wi-Fi".
///
/// `NWPathMonitor` needs no entitlement and raises no prompt: it reports which
/// interfaces could carry traffic, not what's on the network. Deliberately not
/// a `@MainActor` type — it is read from wherever a connection just failed.
final class LocalNetworkMonitor {
    static let shared = LocalNetworkMonitor()

    private let monitor = NWPathMonitor(requiredInterfaceType: .wifi)
    private let queue = DispatchQueue(label: "org.openlink.wifi-path")
    private let lock = NSLock()
    private var satisfied: Bool?
    private var started = false

    private init() {}

    /// nil until the monitor's first callback.
    var hasWiFiPath: Bool? {
        lock.lock()
        defer { lock.unlock() }
        return satisfied
    }

    func start() {
        lock.lock()
        if started {
            lock.unlock()
            return
        }
        started = true
        lock.unlock()

        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            self.lock.lock()
            self.satisfied = (path.status == .satisfied)
            self.lock.unlock()
        }
        monitor.start(queue: queue)
    }
}
