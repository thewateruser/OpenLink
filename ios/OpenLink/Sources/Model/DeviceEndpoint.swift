//
//  DeviceEndpoint.swift
//  OpenLink (parent app)
//
//  One address at which a paired child device might be reachable, plus the
//  ranking logic that decides which to dial first.
//
//  PROTOCOL.md: "On connect the parent races its known endpoints (LAN first,
//  since it's lowest latency) and uses whichever completes the TLS handshake
//  first."
//

import Foundation

/// The default port the child's embedded Ktor listener binds.
let openLinkDefaultPort = 8765

struct DeviceEndpoint: Codable, Hashable, Identifiable {
    /// Where this address came from. Purely informational for the user, plus
    /// a tie-breaker in ranking.
    enum Source: String, Codable {
        /// Listed in the `ep=` parameter of the pairing QR.
        case pairingQR
        /// Merged in from a `GET /device` / `device:state` `endpoints` array.
        case learned
        /// Resolved right now from an `_openlink._tcp` Bonjour advertisement.
        case bonjour
        /// Typed in by the user as an escape hatch.
        case manual
    }

    var host: String
    var port: Int
    var source: Source
    /// Last time a TLS handshake + authenticated request against this address
    /// succeeded. Used to float recently-working addresses up the list.
    var lastSucceededAt: Date?

    init(host: String, port: Int = openLinkDefaultPort, source: Source, lastSucceededAt: Date? = nil) {
        self.host = host
        self.port = port
        self.source = source
        self.lastSucceededAt = lastSucceededAt
    }

    /// Identity is the address only — the same host:port learned twice from
    /// two different sources is one endpoint.
    var id: String { authority }

    static func == (lhs: DeviceEndpoint, rhs: DeviceEndpoint) -> Bool {
        lhs.host.lowercased() == rhs.host.lowercased() && lhs.port == rhs.port
    }

    func hash(into hasher: inout Hasher) {
        hasher.combine(host.lowercased())
        hasher.combine(port)
    }

    /// `host:port`, with IPv6 literals bracketed so they're URL-legal.
    var authority: String {
        if host.contains(":") && !host.hasPrefix("[") {
            return "[\(host)]:\(port)"
        }
        return "\(host):\(port)"
    }

    var baseURL: URL? {
        URL(string: "https://\(authority)")
    }

    /// `wss://host:port` — the WebSocket peer for `/events`.
    var webSocketBaseURL: URL? {
        URL(string: "wss://\(authority)")
    }

    var displayName: String {
        switch source {
        case .pairingQR: return "\(authority) (from pairing code)"
        case .learned: return "\(authority) (learned)"
        case .bonjour: return "\(authority) (on this Wi-Fi)"
        case .manual: return "\(authority) (added manually)"
        }
    }

    // MARK: - Parsing

    /// Parses a `host:port` / `[v6]:port` / bare-host string as the child
    /// sends them in `endpoints` and in the QR's `ep=` list.
    /// A bare host takes `openLinkDefaultPort`.
    static func parse(_ raw: String, source: Source) -> DeviceEndpoint? {
        var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return nil }

        // Tolerate a scheme if the child (or a user) includes one.
        for prefix in ["https://", "wss://", "http://", "ws://"] {
            if text.lowercased().hasPrefix(prefix) {
                text = String(text.dropFirst(prefix.count))
            }
        }
        if let slash = text.firstIndex(of: "/") {
            text = String(text[text.startIndex..<slash])
        }
        guard !text.isEmpty else { return nil }

        // Bracketed IPv6: [::1]:8765 or [::1]
        if text.hasPrefix("[") {
            guard let close = text.firstIndex(of: "]") else { return nil }
            let host = String(text[text.index(after: text.startIndex)..<close])
            guard !host.isEmpty else { return nil }
            let rest = text[text.index(after: close)...]
            let port = rest.hasPrefix(":") ? Int(rest.dropFirst()) : nil
            return DeviceEndpoint(host: host, port: port ?? openLinkDefaultPort, source: source)
        }

        // Bare IPv6 (more than one colon and no port suffix we can trust).
        if text.filter({ $0 == ":" }).count > 1 {
            return DeviceEndpoint(host: text, port: openLinkDefaultPort, source: source)
        }

        if let colon = text.lastIndex(of: ":") {
            let host = String(text[text.startIndex..<colon])
            let portText = text[text.index(after: colon)...]
            guard !host.isEmpty, let port = Int(portText), (1...65535).contains(port) else { return nil }
            return DeviceEndpoint(host: host, port: port, source: source)
        }

        return DeviceEndpoint(host: text, port: openLinkDefaultPort, source: source)
    }

    static func parseList(_ raws: [String], source: Source) -> [DeviceEndpoint] {
        raws.compactMap { parse($0, source: source) }
    }

    /// Splits and parses a comma-separated list, as used by the QR's `ep=`.
    static func parseCommaSeparated(_ raw: String, source: Source) -> [DeviceEndpoint] {
        parseList(raw.split(separator: ",").map(String.init), source: source)
    }
}

// MARK: - Ranking

enum EndpointRanker {
    /// Rough latency class. Lower dials first.
    ///
    /// The ordering encodes the reachability table in PROTOCOL.md: a LAN
    /// address is a single hop, an overlay address (Tailscale's 100.64/10
    /// CGNAT range, or WireGuard's usual RFC1918 tunnel subnets) goes through
    /// a tunnel, and anything else is a guess.
    static func latencyClass(_ endpoint: DeviceEndpoint) -> Int {
        let host = endpoint.host.lowercased()

        // Live Bonjour result: we literally just saw it advertise on this
        // Wi-Fi, so it's the best bet regardless of its address shape.
        if endpoint.source == .bonjour { return 0 }

        if isTailscaleCGNAT(host) { return 3 }
        if isPrivateIPv4(host) || isLinkLocal(host) { return 1 }
        if host.hasSuffix(".local") { return 1 }
        if isIPv6UniqueLocalOrLinkLocal(host) { return 2 }
        // Tailscale MagicDNS names and other overlay hostnames.
        if host.hasSuffix(".ts.net") { return 3 }
        return 4
    }

    /// Orders endpoints for a connection attempt: latency class first, then
    /// most-recently-successful, then manual entries ahead of stale learned
    /// ones (a user who typed an address probably knows something we don't).
    static func rank(_ endpoints: [DeviceEndpoint]) -> [DeviceEndpoint] {
        endpoints.sorted { lhs, rhs in
            let lClass = latencyClass(lhs)
            let rClass = latencyClass(rhs)
            if lClass != rClass { return lClass < rClass }

            switch (lhs.lastSucceededAt, rhs.lastSucceededAt) {
            case let (l?, r?) where l != r: return l > r
            case (_?, nil): return true
            case (nil, _?): return false
            default: break
            }

            if lhs.source != rhs.source {
                return sourceRank(lhs.source) < sourceRank(rhs.source)
            }
            return lhs.authority < rhs.authority
        }
    }

    private static func sourceRank(_ source: DeviceEndpoint.Source) -> Int {
        switch source {
        case .bonjour: return 0
        case .manual: return 1
        case .learned: return 2
        case .pairingQR: return 3
        }
    }

    // MARK: - Address shape helpers

    private static func ipv4Octets(_ host: String) -> [Int]? {
        let parts = host.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4 else { return nil }
        let octets = parts.compactMap { Int($0) }
        guard octets.count == 4, octets.allSatisfy({ (0...255).contains($0) }) else { return nil }
        return octets
    }

    static func isPrivateIPv4(_ host: String) -> Bool {
        guard let o = ipv4Octets(host) else { return false }
        if o[0] == 10 { return true }
        if o[0] == 172 && (16...31).contains(o[1]) { return true }
        if o[0] == 192 && o[1] == 168 { return true }
        return false
    }

    static func isLinkLocal(_ host: String) -> Bool {
        guard let o = ipv4Octets(host) else { return false }
        return o[0] == 169 && o[1] == 254
    }

    /// Tailscale hands out addresses from 100.64.0.0/10.
    static func isTailscaleCGNAT(_ host: String) -> Bool {
        guard let o = ipv4Octets(host) else { return false }
        return o[0] == 100 && (64...127).contains(o[1])
    }

    static func isIPv6UniqueLocalOrLinkLocal(_ host: String) -> Bool {
        let lowered = host.lowercased()
        guard lowered.contains(":") else { return false }
        return lowered.hasPrefix("fe80:") || lowered.hasPrefix("fd") || lowered.hasPrefix("fc")
    }
}
