//
//  PairedDeviceStore.swift
//  OpenLink (parent app)
//
//  Persistence for the non-secret half of a pairing: names, endpoint lists,
//  cached battery state. Secrets live in `DeviceCredentialStore`.
//
//  UserDefaults is enough here — this is a small, frequently-rewritten list
//  (every endpoint-learning merge touches it) and none of it is sensitive.
//

import Foundation

struct PairedDeviceStore {
    private let defaultsKey = "openlink.pairedDevices.v2"
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func load() -> [PairedDevice] {
        guard let data = defaults.data(forKey: defaultsKey) else { return [] }
        return (try? JSONDecoder().decode([PairedDevice].self, from: data)) ?? []
    }

    func save(_ devices: [PairedDevice]) {
        guard let data = try? JSONEncoder().encode(devices) else { return }
        defaults.set(data, forKey: defaultsKey)
    }
}
