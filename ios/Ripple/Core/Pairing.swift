import Foundation
import CryptoKit

/// Out-of-band pairing (ROADMAP Phase 0.2, app-layer — no wire changes).
///
/// Two phones that can see each other can compare full 65-byte public keys instead
/// of only the 8-byte node id, so identity verification is not limited by the id's
/// truncation. Pure Foundation/CryptoKit (no SwiftUI/UIKit) so the exact same bytes
/// are produced on iOS and Android; see docs/PAIRING.md for the byte-level format.
enum Pairing {
    static let prefix = "RIPPLE-ID"
    static let codeVersion = "v1"

    /// A parsed identity code: an 8-byte node id, its full P-256 public key, and an optional display name.
    struct IdentityCode: Equatable {
        let nodeIdHex: String
        let publicKeyWireHex: String
        let name: String?

        /// Canonical text form: `RIPPLE-ID:v1:<id16>:<key130>[:<percent-encoded name>]`.
        func encode() -> String {
            var out = "\(Pairing.prefix):\(Pairing.codeVersion):\(nodeIdHex):\(publicKeyWireHex)"
            if let name, !name.trimmingCharacters(in: .whitespaces).isEmpty {
                out += ":\(Pairing.percentEncode(name))"
            }
            return out
        }
    }

    /// Encode this device's identity as a canonical text code (shown as a QR / share sheet).
    static func encodeIdentityCode(publicKeyWire: Data, name: String? = nil) -> String {
        let nodeId = NodeId.fromPublicKey(publicKeyWire)
        return IdentityCode(nodeIdHex: nodeId.hex, publicKeyWireHex: publicKeyWire.hex, name: name).encode()
    }

    /// Parse and validate an identity code. Returns nil when the code is malformed
    /// (bad prefix/version/hex) or self-inconsistent (the node id is not the
    /// truncated SHA-256 of the claimed public key).
    static func decodeIdentityCode(_ code: String) -> IdentityCode? {
        let parts = code.split(separator: ":")
        guard parts.count == 4 || parts.count == 5 else { return nil }
        // Prefix/version are ASCII; accept either case. Hex segments are normalised below.
        guard parts[0].caseInsensitiveCompare(prefix) == .orderedSame,
              parts[1].caseInsensitiveCompare(codeVersion) == .orderedSame else { return nil }
        let nodeIdHex = parts[2].lowercased()
        let keyHex = parts[3].lowercased()
        guard nodeIdHex.count == 16, keyHex.count == 130 else { return nil }
        guard nodeIdHex.allSatisfy(\.isHexDigit), keyHex.allSatisfy(\.isHexDigit) else { return nil }
        guard let wire = Data(hex: keyHex) else { return nil }
        guard NodeId.fromPublicKey(wire).hex == nodeIdHex else { return nil }
        let name: String?
        if parts.count == 5 {
            let decoded = percentDecode(String(parts[4]))
            name = decoded.isEmpty ? nil : decoded
        } else {
            name = nil
        }
        return IdentityCode(nodeIdHex: nodeIdHex, publicKeyWireHex: keyHex, name: name)
    }

    /// 12-digit decimal safety code (grouped 4-4-4, e.g. "5046 5756 7335") derived
    /// from the two devices' full public keys. Order-independent: both phones show
    /// the same digits, so the pair compares them out-of-band to confirm they are
    /// each holding the other's real key. Only the first 40 bits of SHA-256 over the
    /// two keys (in canonical byte order) are used; the tiny modulo bias of the
    /// decimal projection is irrelevant at 10^12 scale.
    static func safetyCode(publicKeyWireA: Data, publicKeyWireB: Data) -> String {
        var ordered = Data()
        if publicKeyWireA.lexicographicallyPrecedes(publicKeyWireB) || publicKeyWireA == publicKeyWireB {
            ordered.append(publicKeyWireA)
            ordered.append(publicKeyWireB)
        } else {
            ordered.append(publicKeyWireB)
            ordered.append(publicKeyWireA)
        }
        let digest = SHA256.hash(data: ordered)
        var value: UInt64 = 0
        let bytes = Array(digest.prefix(5))
        for byte in bytes {
            value = (value << 8) | UInt64(byte)
        }
        let digits = String(value % 1_000_000_000_000).leftPadding(toLength: 12, withPad: "0")
        var groups: [String] = []
        var idx = digits.startIndex
        while idx < digits.endIndex {
            let end = digits.index(idx, offsetBy: 4, limitedBy: digits.endIndex) ?? digits.endIndex
            groups.append(String(digits[idx..<end]))
            idx = end
        }
        return groups.joined(separator: " ")
    }

    /// RFC 3986 unreserved bytes pass through; everything else becomes %XX (uppercase hex).
    static func percentEncode(_ value: String) -> String {
        var out = ""
        for byte in value.utf8 {
            let c = Int(byte)
            let unreserved = (c >= 0x41 && c <= 0x5A) || (c >= 0x61 && c <= 0x7A) || (c >= 0x30 && c <= 0x39)
                || c == 0x2D || c == 0x2E || c == 0x5F || c == 0x7E
            if unreserved {
                out.append(Character(UnicodeScalar(c)!))
            } else {
                out += String(format: "%%%02X", c)
            }
        }
        return out
    }

    /// Inverse of `percentEncode`; tolerant of stray '%' (left as-is when not followed by hex).
    static func percentDecode(_ value: String) -> String {
        var bytes: [UInt8] = []
        let chars = Array(value)
        var i = 0
        while i < chars.count {
            if chars[i] == "%" {
                let hi = i + 1 < chars.count ? chars[i + 1].hexDigitValue : nil
                let lo = i + 2 < chars.count ? chars[i + 2].hexDigitValue : nil
                if let hi, let lo {
                    bytes.append(UInt8((hi << 4) | lo))
                    i += 3
                    continue
                }
            }
            // Append the UTF-8 bytes of the raw character so non-ASCII survives.
            for b in String(chars[i]).utf8 { bytes.append(b) }
            i += 1
        }
        return String(decoding: bytes, as: UTF8.self)
    }
}

private extension String {
    func leftPadding(toLength length: Int, withPad pad: String) -> String {
        guard count < length else { return self }
        return String(repeating: pad, count: length - count) + self
    }
}
