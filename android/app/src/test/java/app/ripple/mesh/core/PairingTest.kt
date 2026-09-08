package app.ripple.mesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-level conformance for docs/PAIRING.md. The literal expectations are shared
 * verbatim with ios/RippleTests/PairingTests.swift (and were derived with the Node
 * reference), so Android and iOS produce identical codes and safety numbers.
 */
class PairingTest {
    // Deterministic "keys": 0x04 || repeated byte. Not valid curve points — the
    // codecs treat public keys as opaque 65-byte values, so this is fine and stable.
    private val keyA: ByteArray = ("04" + "11".repeat(64)).hexToBytes()
    private val keyB: ByteArray = ("04" + "22".repeat(64)).hexToBytes()

    private val codeA = "RIPPLE-ID:v1:f0b9315a0459dab7:0411111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111"
    private val codeANamed = "$codeA:Asha"

    @Test fun `identity code encoding matches the canonical form`() {
        assertEquals(codeANamed, Pairing.encodeIdentityCode(keyA, "Asha"))
        // Blank/absent name omits the name segment.
        assertEquals(codeA, Pairing.encodeIdentityCode(keyA))
        assertEquals(codeA, Pairing.encodeIdentityCode(keyA, "  "))
    }

    @Test fun `identity code decodes and validates self-consistency`() {
        val decoded = Pairing.decodeIdentityCode(codeANamed)
        assertEquals("f0b9315a0459dab7", decoded?.nodeIdHex)
        assertEquals(codeA.substringAfterLast(':'), decoded?.publicKeyWireHex)
        assertEquals("Asha", decoded?.name)
        // Re-encoding a decoded code is lossless.
        assertEquals(codeANamed, decoded?.encode())
        // Name-less code parses with a null name.
        assertNull(Pairing.decodeIdentityCode(codeA)?.name)
    }

    @Test fun `identity code round-trips names with tricky characters`() {
        val name = "Zoë 🙂 / A:B 100%"
        val code = Pairing.encodeIdentityCode(keyA, name)
        // Reserved separator and control bytes are percent-encoded.
        assertTrue(":" !in code.substringAfterLast(codeA + ":"))
        assertEquals(name, Pairing.decodeIdentityCode(code)?.name)
    }

    @Test fun `percent encoding matches the reference vectors`() {
        assertEquals("Asha", Pairing.percentEncode("Asha"))
        assertEquals("Zo%C3%AB%20%F0%9F%99%82", Pairing.percentEncode("Zoë 🙂"))
        assertEquals("Zoë 🙂", Pairing.percentDecode("Zo%C3%AB%20%F0%9F%99%82"))
        // A stray '%' that is not followed by hex is kept literally.
        assertEquals("a%zzb", Pairing.percentDecode("a%zzb"))
        assertEquals("100%", Pairing.percentDecode("100%"))
    }

    @Test fun `malformed and inconsistent codes are rejected`() {
        // Wrong prefix / version.
        assertNull(Pairing.decodeIdentityCode(codeANamed.replace("RIPPLE-ID", "RIPPLE")))
        assertNull(Pairing.decodeIdentityCode(codeANamed.replace(":v1:", ":v2:")))
        // Truncated hex.
        assertNull(Pairing.decodeIdentityCode(codeA.dropLast(2)))
        // The node id does not match sha256(key)[0:8] (key B claimed under A's id).
        val keyBUnderAId = "RIPPLE-ID:v1:f0b9315a0459dab7:" + ("04" + "22".repeat(64)) + ":Bob"
        assertNull(Pairing.decodeIdentityCode(keyBUnderAId))
        // Upper-case hex input is normalised and still accepted.
        assertEquals("f0b9315a0459dab7", Pairing.decodeIdentityCode(codeANamed.uppercase())?.nodeIdHex)
        // Garbage.
        assertNull(Pairing.decodeIdentityCode("not a code"))
    }

    @Test fun `safety code matches the shared vector and is order independent`() {
        assertEquals("5046 5756 7335", Pairing.safetyCode(keyA, keyB))
        assertEquals(Pairing.safetyCode(keyA, keyB), Pairing.safetyCode(keyB, keyA))
        // Identical keys are handled without crashing and stay deterministic.
        assertEquals(Pairing.safetyCode(keyA, keyA), Pairing.safetyCode(keyA, keyA))
        // A different key pair produces a different code.
        val keyC: ByteArray = ("04" + "33".repeat(64)).hexToBytes()
        assertTrue(Pairing.safetyCode(keyA, keyC) != Pairing.safetyCode(keyA, keyB))
    }
}
