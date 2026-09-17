//
//  PairedDevice.swift
//  OpenLink (parent app)
//
//  A child device this app has paired with. There are no accounts and no
//  server: the app just holds N of these, each independently trusted via a
//  pinned certificate fingerprint and its own bearer token.
//
//  The *secrets* (parentToken, parentId) and the integrity-critical pinned
//  fingerprint live in the Keychain (`DeviceCredentialStore`); this struct
//  holds only the non-secret metadata that goes in UserDefaults.
//

import Foundation

struct PairedDevice: Codable, Identifiable, Equatable {
    /// The child's `deviceId`, as carried in the pairing QR and returned by
    /// `POST /pair`. This is the Keychain key for the device's credentials.
    let deviceId: String
    var deviceName: String
    var platform: String?
    var appVersion: String?

    /// Every address we currently believe this device might answer on.
    /// Seeded from the QR's `ep=`, then continuously merged from
    /// `GET /device` and `device:state` — this is how a device paired at home
    /// teaches the app its Tailscale address without the user doing anything.
    var endpoints: [DeviceEndpoint]

    var pairedAt: Date
    var lastConnectedAt: Date?

    // Cached last-known state, so the UI has something to show before the
    // first successful connection of a session.
    var batteryLevel: Int?

    var id: String { deviceId }

    init(
        deviceId: String,
        deviceName: String,
        platform: String? = nil,
        appVersion: String? = nil,
        endpoints: [DeviceEndpoint],
        pairedAt: Date = Date(),
        lastConnectedAt: Date? = nil,
        batteryLevel: Int? = nil
    ) {
        self.deviceId = deviceId
        self.deviceName = deviceName
        self.platform = platform
        self.appVersion = appVersion
        self.endpoints = endpoints
        self.pairedAt = pairedAt
        self.lastConnectedAt = lastConnectedAt
        self.batteryLevel = batteryLevel
    }

    /// Merges newly-observed addresses into `endpoints`, preserving existing
    /// entries' `source` and `lastSucceededAt` (an address we already know to
    /// work shouldn't be demoted to "learned" just because it was echoed
    /// back), and capping the list so a device with many interfaces can't
    /// grow it without bound.
    ///
    /// Returns `true` if anything changed, so callers can avoid a needless
    /// persist + `objectWillChange`.
    @discardableResult
    mutating func merge(endpoints newEndpoints: [DeviceEndpoint], limit: Int = 12) -> Bool {
        var changed = false
        for candidate in newEndpoints {
            if let index = endpoints.firstIndex(of: candidate) {
                // Known address. Only upgrade its source if the new sighting
                // is more authoritative about *right now* (Bonjour) or more
                // deliberate (manual).
                let existing = endpoints[index]
                if existing.source == .learned && (candidate.source == .bonjour || candidate.source == .manual) {
                    endpoints[index].source = candidate.source
                    changed = true
                }
            } else {
                endpoints.append(candidate)
                changed = true
            }
        }

        if endpoints.count > limit {
            // Drop the least promising first: never a manual entry, since
            // that's the user's explicit escape hatch.
            let ranked = EndpointRanker.rank(endpoints)
            var kept = ranked.filter { $0.source == .manual }
            for endpoint in ranked where endpoint.source != .manual && kept.count < limit {
                kept.append(endpoint)
            }
            endpoints = EndpointRanker.rank(kept)
            changed = true
        }

        return changed
    }

    /// Records that a particular address just worked, which floats it to the
    /// front of the next connection race.
    mutating func markSucceeded(_ endpoint: DeviceEndpoint, at date: Date = Date()) {
        if let index = endpoints.firstIndex(of: endpoint) {
            endpoints[index].lastSucceededAt = date
        } else {
            var copy = endpoint
            copy.lastSucceededAt = date
            endpoints.append(copy)
        }
        lastConnectedAt = date
    }
}
