import Foundation
import CryptoKit

/// All cryptography for the protocol on top of CryptoKit
/// (P-256 ECDSA/ECDH, SHA-256, HKDF, AES-GCM). Byte-compatible with the Android port.
enum Crypto {
    // MARK: Keys

    static func randomBytes(_ n: Int) -> Data {
        var d = Data(count: n)
        _ = d.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, n, $0.baseAddress!) }
        return d
    }

    /// X9.63 uncompressed: 0x04 ‖ X ‖ Y
    static func wire(_ key: P256.Signing.PublicKey) -> Data { key.x963Representation }

    static func signingKey(fromWire wire: Data) throws -> P256.Signing.PublicKey {
        guard wire.count == 65, wire.first == 0x04 else { throw ProtocolError.badPublicKey }
        return try P256.Signing.PublicKey(x963Representation: wire)
    }

    static func agreementKey(fromWire wire: Data) throws -> P256.KeyAgreement.PublicKey {
        guard wire.count == 65, wire.first == 0x04 else { throw ProtocolError.badPublicKey }
        return try P256.KeyAgreement.PublicKey(x963Representation: wire)
    }

    // MARK: ECDSA (raw r‖s)

    static func sign(_ key: P256.Signing.PrivateKey, unsignedPacket: Data) throws -> Data {
        var input = unsignedPacket; input[input.startIndex + 3] = 0
        return try key.signature(for: input).rawRepresentation
    }

    static func verify(_ key: P256.Signing.PublicKey, unsignedPacket: Data, rawSignature: Data) -> Bool {
        guard rawSignature.count == MeshProtocol.signatureSize,
              let sig = try? P256.Signing.ECDSASignature(rawRepresentation: rawSignature) else { return false }
        var input = unsignedPacket; input[input.startIndex + 3] = 0
        return key.isValidSignature(sig, for: input)
    }

    // MARK: ECIES: P-256 ECDH → HKDF-SHA256 → AES-256-GCM

    static func encrypt(recipientWire: Data, messageId: Data, source: NodeId, destination: NodeId, plaintext: Data) throws -> Data {
        try encrypt(ephemeral: P256.KeyAgreement.PrivateKey(), recipientWire: recipientWire, messageId: messageId,
                    source: source, destination: destination, plaintext: plaintext, nonce: AES.GCM.Nonce())
    }

    /// Deterministic variant (used by tests against the shared vectors).
    static func encrypt(ephemeral: P256.KeyAgreement.PrivateKey, recipientWire: Data, messageId: Data, source: NodeId,
                        destination: NodeId, plaintext: Data, nonce: AES.GCM.Nonce) throws -> Data {
        let recipient = try agreementKey(fromWire: recipientWire)
        let shared = try ephemeral.sharedSecretFromKeyAgreement(with: recipient)
        let key = deriveKey(shared, messageId: messageId)
        let box = try AES.GCM.seal(plaintext, using: key, nonce: nonce, authenticating: aad(messageId, source, destination))
        return ephemeral.publicKey.x963Representation + Data(nonce) + box.ciphertext + box.tag
    }

    static func decrypt(recipient: P256.KeyAgreement.PrivateKey, messageId: Data, source: NodeId, destination: NodeId, payload: Data) throws -> Data {
        let p = Data(payload)
        guard p.count >= 65 + 12 + 16 else { throw ProtocolError.eciesMalformed }
        let ephemeralPub = try agreementKey(fromWire: p.subdata(in: 0..<65))
        let nonce = try AES.GCM.Nonce(data: p.subdata(in: 65..<77))
        let ct = p.subdata(in: 77..<(p.count - 16))
        let tag = p.subdata(in: (p.count - 16)..<p.count)
        let shared = try recipient.sharedSecretFromKeyAgreement(with: ephemeralPub)
        let key = deriveKey(shared, messageId: messageId)
        let box = try AES.GCM.SealedBox(nonce: nonce, ciphertext: ct, tag: tag)
        return try AES.GCM.open(box, using: key, authenticating: aad(messageId, source, destination))
    }

    static func deriveKey(_ shared: SharedSecret, messageId: Data) -> SymmetricKey {
        shared.hkdfDerivedSymmetricKey(using: SHA256.self, salt: messageId, sharedInfo: MeshProtocol.hkdfInfo, outputByteCount: 32)
    }

    private static func aad(_ messageId: Data, _ source: NodeId, _ destination: NodeId) -> Data {
        messageId + source.bytes + destination.bytes
    }
}

/// A node's long-term identity. The same P-256 scalar is used for signing and key agreement.
struct Identity {
    let signing: P256.Signing.PrivateKey
    let agreement: P256.KeyAgreement.PrivateKey
    let publicKeyWire: Data
    let nodeId: NodeId

    init(signing: P256.Signing.PrivateKey) {
        self.signing = signing
        // Same scalar, different CryptoKit type.
        self.agreement = try! P256.KeyAgreement.PrivateKey(rawRepresentation: signing.rawRepresentation)
        self.publicKeyWire = signing.publicKey.x963Representation
        self.nodeId = NodeId.fromPublicKey(publicKeyWire)
    }

    static func generate() -> Identity { Identity(signing: P256.Signing.PrivateKey()) }

    init(rawScalar: Data) throws { self.init(signing: try P256.Signing.PrivateKey(rawRepresentation: rawScalar)) }

    func sign(_ unsigned: Data) throws -> Data { try Crypto.sign(signing, unsignedPacket: unsigned) }
}

/// Builders for signed packets originated by this identity.
enum PacketFactory {
    static func build(_ id: Identity, type: PacketType, payload: Data, destination: NodeId = .broadcast, flags: UInt8 = 0,
                      ttl: UInt8 = MeshProtocol.maxTTL, messageId: Data? = nil, timestamp: UInt64? = nil) throws -> Packet {
        var p = Packet(type: type, flags: flags, ttl: ttl,
                       messageId: messageId ?? Crypto.randomBytes(MeshProtocol.messageIdSize),
                       source: id.nodeId, destination: destination,
                       timestamp: timestamp ?? UInt64(Date().timeIntervalSince1970 * 1000),
                       payload: payload, signature: Data(count: MeshProtocol.signatureSize))
        p.signature = try id.sign(p.encodeUnsigned())
        return p
    }

    static func announce(_ id: Identity, name: String) throws -> Packet {
        try build(id, type: .announce, payload: Announce(publicKeyWire: id.publicKeyWire, name: trimName(name)).encode())
    }

    static func broadcastText(_ id: Identity, _ text: String) throws -> Packet {
        try build(id, type: .message, payload: Data(text.utf8))
    }

    static func directText(_ id: Identity, to destination: NodeId, recipientWire: Data, text: String) throws -> Packet {
        let messageId = Crypto.randomBytes(MeshProtocol.messageIdSize)
        let box = try Crypto.encrypt(recipientWire: recipientWire, messageId: messageId, source: id.nodeId, destination: destination, plaintext: Data(text.utf8))
        return try build(id, type: .message, payload: box, destination: destination, flags: Flags.encrypted, messageId: messageId)
    }

    static func ack(_ id: Identity, to destination: NodeId, acknowledged messageId: Data) throws -> Packet {
        try build(id, type: .ack, payload: messageId, destination: destination)
    }

    private static func trimName(_ name: String) -> String {
        var s = name
        while s.utf8.count > MeshProtocol.maxNameBytes { s.removeLast() }
        return s
    }
}
