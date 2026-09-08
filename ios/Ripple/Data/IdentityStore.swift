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
    private static let powerProfileKey = "powerProfile"

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

    /// Battery profile persists across restarts (UserDefaults).
    static var powerProfile: Int? {
        get { UserDefaults.standard.object(forKey: powerProfileKey) as? Int }
        set { UserDefaults.standard.set(newValue, forKey: powerProfileKey) }
    }

    /// Backup & restore (Phase 0.3, docs/BACKUP.md): the identity is exportable by
    /// design — CryptoKit hands back the raw scalar that already lives in the Keychain.
    static func exportMaterial() -> (scalar: Data, wire: Data) {
        let id = load()
        return (id.signing.rawRepresentation, id.publicKeyWire)
    }

    /// Installs a restored identity after checking it is self-consistent (the scalar's
    /// public key must equal the public key embedded in the payload). Returns false when
    /// the payload is inconsistent; on success the app must restart to activate (docs/BACKUP.md §3).
    @discardableResult
    static func prepareRestore(scalar: Data, wire: Data) -> Bool {
        guard scalar.count == 32, wire.count == 65, let id = try? Identity(rawScalar: scalar), id.publicKeyWire == wire else { return false }
        writeKeychain(id.signing.rawRepresentation)
        // Log at most the 4-hex suffix — full ids are never logged.
        EventLog.global.i("identity", "identity restored from backup (node \(id.nodeId.short)); restart to activate")
        return true
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
