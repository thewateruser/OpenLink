//
//  DeviceCredentialStore.swift
//  OpenLink (parent app)
//
//  Per-device credentials in the iOS Keychain, keyed by `deviceId`.
//
//  Two different kinds of thing live here for two different reasons:
//    - `parentToken` and `parentId` are secrets — the bearer token is the
//      only thing standing between an attacker and full control of a child
//      device, so it must never touch UserDefaults.
//    - `fingerprintBase64URL` isn't secret at all, but it is the trust
//      anchor. If it could be silently rewritten (as a plist in the app
//      container could be, on a jailbroken device), pinning would be
//      worthless. Keeping it next to the token also makes "unpair" a single
//      atomic delete.
//
//  Everything is `ThisDeviceOnly` so credentials are not carried into an
//  iCloud/iTunes backup and restored onto a different phone.
//

import Foundation
import Security

enum KeychainError: LocalizedError {
    case unexpectedStatus(OSStatus)
    case encodingFailed

    var errorDescription: String? {
        switch self {
        case .unexpectedStatus(let status):
            let message = SecCopyErrorMessageString(status, nil) as String?
            return "Keychain error \(status): \(message ?? "unknown")"
        case .encodingFailed:
            return "Could not encode the value for Keychain storage."
        }
    }
}

/// Everything needed to talk to one paired child device securely.
struct DeviceCredentials: Codable, Equatable {
    /// Bearer token returned by `POST /pair`, sent on every other route.
    let parentToken: String
    /// base64url(SHA-256(certificate DER)) from the pairing QR. Pinned forever.
    let fingerprintBase64URL: String
    /// Our own base64url parent id, needed to revoke ourselves via
    /// `DELETE /pair { parentId }` and to identify us to the child.
    let parentId: String

    /// The 32 raw fingerprint bytes the pinner compares against, or `nil` if
    /// the stored value is malformed (which must be treated as "cannot
    /// connect", never as "skip pinning").
    var pinnedFingerprintBytes: Data? {
        guard let data = Base64URL.decode(fingerprintBase64URL), data.count == 32 else { return nil }
        return data
    }
}

struct DeviceCredentialStore {
    private let service = "org.openlink.parent.device"

    func save(_ credentials: DeviceCredentials, for deviceId: String) throws {
        guard let data = try? JSONEncoder().encode(credentials) else {
            throw KeychainError.encodingFailed
        }

        // Delete-then-add, so this is always a clean insert with the
        // accessibility attribute we want (SecItemUpdate wouldn't change it).
        SecItemDelete(baseQuery(deviceId: deviceId) as CFDictionary)

        var query = baseQuery(deviceId: deviceId)
        query[kSecValueData as String] = data
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly

        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else { throw KeychainError.unexpectedStatus(status) }
    }

    func load(for deviceId: String) -> DeviceCredentials? {
        var query = baseQuery(deviceId: deviceId)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return try? JSONDecoder().decode(DeviceCredentials.self, from: data)
    }

    func delete(for deviceId: String) throws {
        let status = SecItemDelete(baseQuery(deviceId: deviceId) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainError.unexpectedStatus(status)
        }
    }

    private func baseQuery(deviceId: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: deviceId
        ]
    }
}
