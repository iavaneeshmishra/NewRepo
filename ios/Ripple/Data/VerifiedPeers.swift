import Foundation

/// A peer whose *full* public key this device pinned after out-of-band verification
/// (docs/PAIRING.md §3). The pin is the key; the 8-byte node id is only the index.
struct VerifiedPeer: Codable, Identifiable, Equatable {
    var nodeIdHex: String
    var publicKeyWireHex: String
    var name: String?
    var safetyCode: String
    var addedAtMs: Int64

    var id: String { nodeIdHex }
}

/// Persistent verified-peer store (ROADMAP Phase 0.2, app layer — nothing here touches
/// the wire). Pins live as JSON in `UserDefaults`: app-container storage, encrypted at
/// rest by the data protection class. Public keys are not secrets, but the store is
/// still never logged (docs/PAIRING.md §6).
///
/// The pinning *rule* is not duplicated here — it is the pure shared
/// `Pairing.verifyOutcome`, identical on Android, so both platforms refuse a
/// same-id/different-key pin the same way.
enum VerifiedPeers {
    private static let defaultsKey = "ripple.verified_peers_v1"

    static func all() -> [VerifiedPeer] {
        guard let data = UserDefaults.standard.data(forKey: defaultsKey) else { return [] }
        let decoded = (try? JSONDecoder().decode([VerifiedPeer].self, from: data)) ?? []
        return decoded.filter { $0.nodeIdHex.count == 16 && $0.publicKeyWireHex.count == 130 }
    }

    static func find(_ nodeIdHex: String) -> VerifiedPeer? {
        let id = nodeIdHex.lowercased()
        all().first { $0.nodeIdHex == id }
    }

    /// Pin (or re-confirm) a peer key. On `.conflict` nothing is written — the existing
    /// pin stays and the UI must surface the conflict. Returns the outcome plus the
    /// resulting (or kept) record.
    static func pin(nodeIdHex: String, publicKeyWireHex: String, name: String?, safetyCode: String) -> (Pairing.VerifyOutcome, VerifiedPeer) {
        let id = nodeIdHex.lowercased()
        let key = publicKeyWireHex.lowercased()
        let peers = all()
        let existing = peers.first { $0.nodeIdHex == id }
        let outcome = Pairing.verifyOutcome(existingPublicKeyWireHex: existing?.publicKeyWireHex, importedPublicKeyWireHex: key)
        // A conflict can only exist when there is a pin, so returning `existing` here is exhaustive.
        if case .conflict = outcome, let existing { return (.conflict, existing) }

        let record = VerifiedPeer(nodeIdHex: id, publicKeyWireHex: key, name: name ?? existing?.name,
                                  safetyCode: safetyCode, addedAtMs: Int64(Date().timeIntervalSince1970 * 1000))
        save(peers.filter { $0.nodeIdHex != id } + [record])
        return (outcome, record)
    }

    /// Un-pin a peer (e.g. contact says their phone was wiped and re-paired).
    static func unpin(nodeIdHex: String) {
        let id = nodeIdHex.lowercased()
        save(all().filter { $0.nodeIdHex != id })
    }

    private static func save(_ peers: [VerifiedPeer]) {
        if let data = try? JSONEncoder().encode(peers) {
            UserDefaults.standard.set(data, forKey: defaultsKey)
        }
    }
}
