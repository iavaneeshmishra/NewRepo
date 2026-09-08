import XCTest
@testable import Ripple

/// Byte-level conformance for docs/PAIRING.md. The literal expectations are shared
/// verbatim with android/app/src/test/.../PairingTest.kt (and were derived with the
/// Node reference), so iOS and Android produce identical codes and safety numbers.
final class PairingTests: XCTestCase {
    // Deterministic "keys": 0x04 || repeated byte. Not valid curve points — the
    // codecs treat public keys as opaque 65-byte values, so this is fine and stable.
    private let keyA = Data(hex: "04" + String(repeating: "11", count: 64))!
    private let keyB = Data(hex: "04" + String(repeating: "22", count: 64))!
    private let keyC = Data(hex: "04" + String(repeating: "33", count: 64))!

    private let codeA = "RIPPLE-ID:v1:f0b9315a0459dab7:0411111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111"
    private var codeANamed: String { "\(codeA):Asha" }

    func testIdentityCodeEncodingMatchesTheCanonicalForm() {
        XCTAssertEqual(codeANamed, Pairing.encodeIdentityCode(publicKeyWire: keyA, name: "Asha"))
        // Blank/absent name omits the name segment.
        XCTAssertEqual(codeA, Pairing.encodeIdentityCode(publicKeyWire: keyA))
        XCTAssertEqual(codeA, Pairing.encodeIdentityCode(publicKeyWire: keyA, name: "  "))
    }

    func testIdentityCodeDecodesAndValidatesSelfConsistency() {
        let decoded = Pairing.decodeIdentityCode(codeANamed)
        XCTAssertEqual("f0b9315a0459dab7", decoded?.nodeIdHex)
        XCTAssertEqual(codeA.components(separatedBy: ":").last, decoded?.publicKeyWireHex)
        XCTAssertEqual("Asha", decoded?.name)
        // Re-encoding a decoded code is lossless.
        XCTAssertEqual(codeANamed, decoded?.encode())
        // Name-less code parses with a nil name.
        XCTAssertNil(Pairing.decodeIdentityCode(codeA)?.name)
    }

    func testIdentityCodeRoundTripsNamesWithTrickyCharacters() {
        let name = "Zoë 🙂 / A:B 100%"
        let code = Pairing.encodeIdentityCode(publicKeyWire: keyA, name: name)
        // Reserved separator and control bytes are percent-encoded.
        XCTAssertFalse(code.dropFirst(codeA.count + 1).contains(":"))
        XCTAssertEqual(name, Pairing.decodeIdentityCode(code)?.name)
    }

    func testPercentEncodingMatchesTheReferenceVectors() {
        XCTAssertEqual("Asha", Pairing.percentEncode("Asha"))
        XCTAssertEqual("Zo%C3%AB%20%F0%9F%99%82", Pairing.percentEncode("Zoë 🙂"))
        XCTAssertEqual("Zoë 🙂", Pairing.percentDecode("Zo%C3%AB%20%F0%9F%99%82"))
        // Stray '%' is tolerated.
        XCTAssertEqual("a%b", Pairing.percentDecode("a%zzb"))
    }

    func testMalformedAndInconsistentCodesAreRejected() {
        // Wrong prefix / version.
        XCTAssertNil(Pairing.decodeIdentityCode(codeANamed.replacingOccurrences(of: "RIPPLE-ID", with: "RIPPLE")))
        XCTAssertNil(Pairing.decodeIdentityCode(codeANamed.replacingOccurrences(of: ":v1:", with: ":v2:")))
        // Truncated hex.
        XCTAssertNil(Pairing.decodeIdentityCode(String(codeA.dropLast(2))))
        // The node id does not match sha256(key)[0:8] (key B claimed under A's id).
        let keyBUnderAId = "RIPPLE-ID:v1:f0b9315a0459dab7:" + "04" + String(repeating: "22", count: 64) + ":Bob"
        XCTAssertNil(Pairing.decodeIdentityCode(keyBUnderAId))
        // Upper-case hex input is normalised and still accepted.
        XCTAssertEqual("f0b9315a0459dab7", Pairing.decodeIdentityCode(codeANamed.uppercased())?.nodeIdHex)
        // Garbage.
        XCTAssertNil(Pairing.decodeIdentityCode("not a code"))
    }

    func testSafetyCodeMatchesTheSharedVectorAndIsOrderIndependent() {
        XCTAssertEqual("5046 5756 7335", Pairing.safetyCode(publicKeyWireA: keyA, publicKeyWireB: keyB))
        XCTAssertEqual(
            Pairing.safetyCode(publicKeyWireA: keyA, publicKeyWireB: keyB),
            Pairing.safetyCode(publicKeyWireA: keyB, publicKeyWireB: keyA)
        )
        // Identical keys are handled without crashing and stay deterministic.
        XCTAssertEqual(
            Pairing.safetyCode(publicKeyWireA: keyA, publicKeyWireB: keyA),
            Pairing.safetyCode(publicKeyWireA: keyA, publicKeyWireB: keyA)
        )
        // A different key pair produces a different code.
        XCTAssertNotEqual(
            Pairing.safetyCode(publicKeyWireA: keyA, publicKeyWireB: keyC),
            Pairing.safetyCode(publicKeyWireA: keyA, publicKeyWireB: keyB)
        )
    }
}
