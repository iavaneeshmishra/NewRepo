import Foundation
import CryptoKit
import Security

/// Loads (or creates on first launch) the node identity from the Keychain.
///
/// We need the same P-256 scalar for both ECDSA and ECDH, and we need it usable
/// while the device is locked (background mesh). The raw scalar is stored as a
/// generic-password item with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`.
enum IdentityStore {
    private static let service = "app.ripple.mesh"
    private static let account = "identity-v1"
    private static let nameKey = "displayName"

    static func load() -> Identity {
        if let raw = readKeychain(), let id = try? Identity(rawScalar: raw) { return id }
        let id = Identity.generate()
        writeKeychain(id.signing.rawRepresentation)
        return id
    }

    static var displayName: String? {
        get { UserDefaults.standard.string(forKey: nameKey) }
        set { UserDefaults.standard.set(newValue, forKey: nameKey) }
    }

    private static func query() -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
    }

    private static func readKeychain() -> Data? {
        var q = query()
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess else { return nil }
        return out as? Data
    }

    private static func writeKeychain(_ data: Data) {
        var q = query()
        q[kSecValueData as String] = data
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemDelete(query() as CFDictionary)
        SecItemAdd(q as CFDictionary, nil)
    }
}
