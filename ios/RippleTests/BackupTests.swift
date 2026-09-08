import XCTest
@testable import Ripple

/// Byte-level conformance for docs/BACKUP.md. The literal expectations are shared
/// verbatim with android/app/src/test/.../BackupTest.kt (both derived with the Node
/// snippet documented in docs/BACKUP.md §5), so a backup blob produced on one platform
/// always decodes on the other. App-layer format — no wire-protocol vectors involved.
final class BackupTests: XCTestCase {
    // Deterministic "keys" like PairingTests: opaque bytes, not checked as curve points
    // by the codec itself (only the app validates when installing a restored identity).
    private let scalarHex = String(repeating: "11", count: 32)
    private let wireHex = "04" + String(repeating: "11", count: 64)
    private lazy var scalar = Data(hex: scalarHex)!
    private lazy var wire = Data(hex: wireHex)!
    private let passphrase = "correct horse battery staple"

    private let salt = Data("0123456789abcdef".utf8)
    private lazy var nonce = Data(hex: "0102030405060708090a0b0c")!

    // Fixed salt/nonce ⇒ this exact blob on every platform (150 000 PBKDF2 rounds,
    // AES-256-GCM over scalar‖wire, base64url).
    private let blobVector =
        "RIPPLE-BKP:v1:MDEyMzQ1Njc4OWFiY2RlZgECAwQFBgcICQoLDDf3CPZRnwBxSdzjrkpHGBDL1ZKzXbqn6vWVL3WD2" +
        "YuDSJpHKayNsHwy4vSIeOBr2-c3_TrVhgjvxcB3XqX25sZogCVj90NTMnSyrAB786MzlppfdWR_HdhD-E1ay3my" +
        "xwzI-7e1Wx3JyWTh-QAL1e0_"

    func testBlobMatchesTheSharedVector() {
        let blob = Backup.createBlob(scalar: scalar, publicKeyWire: wire, passphrase: passphrase, salt: salt, nonce: nonce)
        XCTAssertEqual(blobVector, blob)
        XCTAssertEqual(202, blob.count)
    }

    func testRoundTripReturnsTheSameIdentityMaterial() {
        let result = Backup.readBlob(blobVector, passphrase: passphrase)
        XCTAssertNotNil(result.payload)
        XCTAssertEqual(scalar, result.payload.map { Backup.scalar(of: $0) })
        XCTAssertEqual(wire, result.payload.map { Backup.publicKey(of: $0) })
        // The node id re-derived from the restored key — the pairing test's key A:
        XCTAssertEqual("f0b9315a0459dab7", result.payload.map { Backup.nodeIdHex(of: $0) })
        // Fresh random salt/nonce per call, both still decode:
        let a = Backup.createBlob(scalar: scalar, publicKeyWire: wire, passphrase: passphrase)
        let b = Backup.createBlob(scalar: scalar, publicKeyWire: wire, passphrase: passphrase)
        XCTAssertNotEqual(a, b)
        XCTAssertTrue(Backup.readBlob(a, passphrase: passphrase).ok)
    }

    func testWrongPassphraseAndTamperingNeverYieldPlaintext() {
        let wrong = Backup.readBlob(blobVector, passphrase: "wrong horse battery staple")
        XCTAssertNil(wrong.payload)
        XCTAssertEqual(.passphrase, wrong.failure)
        // Same length, altered tag ⇒ auth failure, not a decode error.
        let tampered = String(blobVector.dropLast(2)) + "AB"
        XCTAssertEqual(.passphrase, Backup.readBlob(tampered, passphrase: passphrase).failure)
    }

    func testFormatBase64AndLengthFailuresAreDistinguished() {
        XCTAssertEqual(.format, Backup.readBlob("not a code", passphrase: passphrase).failure)
        XCTAssertEqual(.format, Backup.readBlob(blobVector.replacingOccurrences(of: "RIPPLE-BKP:v1:", with: "RIPPLE-BKP:v2:"), passphrase: passphrase).failure)
        XCTAssertEqual(.base64, Backup.readBlob("RIPPLE-BKP:v1:!!!", passphrase: passphrase).failure)
        // 100 chars of valid base64 = 75 bytes ≠ 141.
        XCTAssertEqual(.length, Backup.readBlob("RIPPLE-BKP:v1:" + String(repeating: "A", count: 100), passphrase: passphrase).failure)
    }

    func testPastedBlobsSurviveNewlinesAndEitherBase64Alphabet() {
        let body = blobVector.components(separatedBy: "v1:").last ?? ""
        let wrapped = "RIPPLE-BKP:v1:\n" + stride(from: 0, to: body.count, by: 64).map { i in
            let s = body.index(body.startIndex, offsetBy: i)
            let e = body.index(s, offsetBy: 64, limitedBy: body.endIndex) ?? body.endIndex
            return String(body[s..<e])
        }.joined(separator: "\n ")
        XCTAssertTrue(Backup.readBlob(wrapped, passphrase: passphrase).ok)
        // Standard alphabet with padding (what other tools might produce) is accepted too.
        let stdBody = Data(base64URLEncoded: body)?.base64EncodedString() ?? ""
        XCTAssertFalse(stdBody.isEmpty)
        XCTAssertTrue(Backup.readBlob("RIPPLE-BKP:v1:" + stdBody, passphrase: passphrase).ok)
    }

    func testUnicodePassphraseRoundTripsThroughUTF8() {
        let pw = "ünïcödé pàss🔐 — 密码"
        let blob = Backup.createBlob(scalar: scalar, publicKeyWire: wire, passphrase: pw)
        XCTAssertTrue(Backup.readBlob(blob, passphrase: pw).ok)
        XCTAssertEqual(.passphrase, Backup.readBlob(blob, passphrase: "ünïcödé pàss🔐 — 密馬").failure)
    }
}

/// Test-only helper: re-encode a base64url body as padded standard base64.
private extension Data {
    init?(base64URLEncoded body: String) {
        let std = body.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        let padded = std + String(repeating: "=", count: (4 - std.count % 4) % 4)
        self.init(base64Encoded: padded)
    }
}
