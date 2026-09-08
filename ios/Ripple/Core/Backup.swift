import Foundation
import CryptoKit

/// Identity backup & restore (ROADMAP Phase 0.3, app-layer — no wire changes).
///
/// The exportable identity material is the 32-byte P-256 private scalar plus the
/// 65-byte uncompressed public key wire form (97 bytes). That payload is encrypted with
/// a passphrase-derived key (PBKDF2-HMAC-SHA256 → AES-256-GCM) and base64url'd into a
/// single text blob designed for offline transport: a QR screen on one phone, pasted
/// text over any channel, even a photo of a screen. Restoring on a fresh install
/// re-derives the *same* node id and key, so the device keeps decrypting its old
/// messages and peers keep verifying its signatures.
///
/// **A leaked passphrase means a lost identity**: whoever holds blob + passphrase *is*
/// this node — Ripple v1 has no revocation (that lands with key rotation in Phase 2.2).
/// See docs/BACKUP.md for the format and the security notes.
///
/// Pure Foundation/CryptoKit on purpose (like `Pairing`): the byte-level vectors are
/// mirrored verbatim in `BackupTests.swift` / `BackupTest.kt`, so a blob written on
/// either platform decodes on the other.
enum Backup {
    static let prefix = "RIPPLE-BKP"
    static let blobVersion = "v1"
    static let saltSize = 16
    static let nonceSize = 12
    static let tagSize = 16
    static let keySize = 32
    static let scalarSize = 32
    static let wireSize = 65
    static let payloadSize = scalarSize + wireSize                    // 97
    static let rawSize = saltSize + nonceSize + payloadSize + tagSize // 141

    /// PBKDF2 work factor. Chosen so restore takes well under a second on low-end
    /// phones while keeping online guessing of a decent passphrase infeasible; the
    /// blob is offline, so this guards the offline copy too — see docs/BACKUP.md §4.
    static let pbkdf2Iterations = 150_000

    /// Domain separation: this key is for Ripple backup blobs and nothing else.
    static let aad = Data("ripple/backup/v1".utf8)

    enum Failure { case format, base64, length, passphrase }

    struct ReadResult {
        let payload: Data?
        let failure: Failure?
        var ok: Bool { payload != nil }
    }

    /// Produce the canonical text blob: `RIPPLE-BKP:v1:<base64url(salt‖nonce‖ct‖tag)>`.
    /// Random salt/nonce — the same inputs give a different output each call.
    static func createBlob(scalar: Data, publicKeyWire: Data, passphrase: String) -> String {
        createBlob(scalar: scalar, publicKeyWire: publicKeyWire, passphrase: passphrase,
                   salt: Crypto.randomBytes(saltSize), nonce: Crypto.randomBytes(nonceSize))
    }

    /// Deterministic variant (fixed salt+nonce) — used by the shared cross-platform test vectors.
    static func createBlob(scalar: Data, publicKeyWire: Data, passphrase: String, salt: Data, nonce: Data) -> String {
        precondition(scalar.count == scalarSize && publicKeyWire.count == wireSize, "bad identity material sizes")
        precondition(salt.count == saltSize && nonce.count == nonceSize, "bad salt/nonce sizes")
        let key = SymmetricKey(data: deriveKey(passphrase: passphrase, salt: salt))
        // The only throw here is an invalid nonce size, which the precondition above rules out
        // (programmer-error invariant).
        let sealed = try! AES.GCM.seal(scalar + publicKeyWire, using: key,
                                       nonce: AES.GCM.Nonce(data: nonce), authenticating: aad)
        return "\(prefix):\(blobVersion):" + encodeBase64Url(salt + nonce + sealed.ciphertext + sealed.tag)
    }

    /// Parse and decrypt a blob. Whitespace/newlines anywhere in `blob` are tolerated
    /// (paste from messages or emails), and a standard base64 alphabet is accepted even
    /// though canonical form is URL-safe. A GCM auth failure is reported as `.passphrase`
    /// — it is indistinguishable from (and the expected answer to) a wrong passphrase;
    /// tampering never yields plaintext.
    static func readBlob(_ blob: String, passphrase: String) -> ReadResult {
        let scalars = blob.unicodeScalars.filter { !CharacterSet.whitespacesAndNewlines.contains($0) }
        let text = String(String.UnicodeScalarView(scalars))
        let parts = text.split(separator: ":", maxSplits: 2, omittingEmptySubsequences: false)
        guard parts.count == 3,
              parts[0].caseInsensitiveCompare(prefix) == .orderedSame,
              parts[1].caseInsensitiveCompare(blobVersion) == .orderedSame else { return ReadResult(payload: nil, failure: .format) }
        guard let raw = decodeBase64Url(String(parts[2])) else { return ReadResult(payload: nil, failure: .base64) }
        guard raw.count == rawSize else { return ReadResult(payload: nil, failure: .length) }
        let salt = raw.subdata(in: 0..<saltSize)
        let combined = raw.subdata(in: saltSize..<raw.count) // nonce‖ct‖tag
        let key = SymmetricKey(data: deriveKey(passphrase: passphrase, salt: salt))
        guard let box = try? AES.GCM.SealedBox(combined: combined),
              let payload = try? AES.GCM.open(box, using: key, authenticating: aad) else {
            return ReadResult(payload: nil, failure: .passphrase)
        }
        return ReadResult(payload: payload, failure: nil)
    }

    // MARK: payload accessors

    static func scalar(of payload: Data) -> Data { payload.subdata(in: 0..<scalarSize) }
    static func publicKey(of payload: Data) -> Data { payload.subdata(in: scalarSize..<payloadSize) }

    /// Node id (lowercase hex) the restored payload re-derives — for UI confirmation.
    static func nodeIdHex(of payload: Data) -> String { NodeId.fromPublicKey(publicKey(of: payload)).hex }

    /// PBKDF2-HMAC-SHA256 over the passphrase's raw UTF-8 bytes (Android's
    /// `PBEKeySpec` hashes the same bytes; so type the passphrase identically on both
    /// platforms — docs/BACKUP.md §4). CryptoKit has no PBKDF2, so this is the standard
    /// loop; dkLen (32) fits one HMAC block. 150k iterations ≈ tens of milliseconds,
    /// matching the JVM's SecretKeyFactory.
    static func deriveKey(passphrase: String, salt: Data) -> Data {
        let password = SymmetricKey(data: Data(passphrase.utf8))
        let block = salt + Data([0, 0, 0, 1])
        var u = Array(HMAC<SHA256>.authenticationCode(for: block, using: password))
        var t = u
        for _ in 1..<pbkdf2Iterations {
            u = Array(HMAC<SHA256>.authenticationCode(for: Data(u), using: password))
            for i in 0..<32 { t[i] ^= u[i] }
        }
        return Data(t)
    }

    // MARK: base64url (RFC 4648 §5), tolerant of the standard alphabet and padding

    static func encodeBase64Url(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func decodeBase64Url(_ value: String) -> Data? {
        let std = value
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
            .replacingOccurrences(of: "=", with: "")
        if std.count % 4 == 1 { return nil }
        let padded = std + String(repeating: "=", count: (4 - std.count % 4) % 4)
        return Data(base64Encoded: padded)
    }
}
