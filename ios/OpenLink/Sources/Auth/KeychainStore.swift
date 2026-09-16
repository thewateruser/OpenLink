//
//  KeychainStore.swift
//  OpenLink (parent app)
//
//  Stores the parent JWT in the iOS Keychain (never UserDefaults). The
//  server base URL is not a secret and is kept in UserDefaults instead
//  (see AppState).
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

struct KeychainStore {
    private let service = "org.openlink.parent.auth"
    private let account = "jwt"

    /// Saves (overwriting any existing value) the parent session JWT.
    func save(token: String) throws {
        guard let data = token.data(using: .utf8) else { throw KeychainError.encodingFailed }

        // Clear any existing item first so this always ends in a clean insert.
        SecItemDelete(baseQuery() as CFDictionary)

        var query = baseQuery()
        query[kSecValueData as String] = data
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock

        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else { throw KeychainError.unexpectedStatus(status) }
    }

    /// Returns the stored JWT, or `nil` if none is stored (or it couldn't be read).
    func readToken() -> String? {
        var query = baseQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func delete() throws {
        let status = SecItemDelete(baseQuery() as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainError.unexpectedStatus(status)
        }
    }

    private func baseQuery() -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
    }
}
