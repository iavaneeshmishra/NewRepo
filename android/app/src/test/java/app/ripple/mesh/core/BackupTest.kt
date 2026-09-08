package app.ripple.mesh.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-level conformance for docs/BACKUP.md. The literal expectations are shared
 * verbatim with ios/RippleTests/BackupTests.swift (both were derived with the Node
 * snippet documented in docs/BACKUP.md §5), so a backup blob produced on one platform
 * always decodes on the other. App-layer format — no wire-protocol vectors involved.
 */
class BackupTest {
    // Deterministic "keys" like PairingTest: opaque bytes, not checked as curve points
    // by the codec itself (only the app validates when installing a restored identity).
    private val scalarHex = "11".repeat(32)
    private val wireHex = "04" + "11".repeat(64)
    private val scalar = scalarHex.hexToBytes()
    private val wire = wireHex.hexToBytes()
    private val passphrase = "correct horse battery staple"

    private val salt = "0123456789abcdef".toByteArray(Charsets.UTF_8)
    private val nonce = "0102030405060708090a0b0c".hexToBytes()

    // Fixed salt/nonce ⇒ this exact blob on every platform (150 000 PBKDF2 rounds,
    // AES-256-GCM over scalar‖wire, base64url).
    private val blobVector =
        "RIPPLE-BKP:v1:MDEyMzQ1Njc4OWFiY2RlZgECAwQFBgcICQoLDDf3CPZRnwBxSdzjrkpHGBDL1ZKzXbqn6vWVL3WD2" +
            "YuDSJpHKayNsHwy4vSIeOBr2-c3_TrVhgjvxcB3XqX25sZogCVj90NTMnSyrAB786MzlppfdWR_HdhD-E1ay3my" +
            "xwzI-7e1Wx3JyWTh-QAL1e0_"

    @Test fun `blob matches the shared vector`() {
        val blob = Backup.createBlob(scalar, wire, passphrase, salt, nonce)
        assertEquals(blobVector, blob)
        assertEquals(202, blob.length)
    }

    @Test fun `round-trip returns the same identity material`() {
        val result = Backup.readBlob(blobVector, passphrase)
        assertTrue(result.ok)
        val payload = result.payload!!
        assertArrayEquals(scalar, Backup.scalarOf(payload))
        assertArrayEquals(wire, Backup.publicKeyOf(payload))
        // The node id re-derived from the restored key — the pairing test's key A:
        assertEquals("f0b9315a0459dab7", Backup.nodeIdOf(payload))
        // Fresh random salt/nonce per call, both still decode:
        val a = Backup.createBlob(scalar, wire, passphrase)
        val b = Backup.createBlob(scalar, wire, passphrase)
        assertTrue(a != b)
        assertTrue(Backup.readBlob(a, passphrase).ok)
    }

    @Test fun `wrong passphrase and tampering never yield plaintext`() {
        val wrong = Backup.readBlob(blobVector, "wrong horse battery staple")
        assertNull(wrong.payload)
        assertEquals(Backup.Failure.PASSPHRASE, wrong.failure)
        // Same length, altered tag ⇒ auth failure, not a decode error.
        val tampered = blobVector.dropLast(2) + "AB"
        assertEquals(Backup.Failure.PASSPHRASE, Backup.readBlob(tampered, passphrase).failure)
    }

    @Test fun `format base64 and length failures are distinguished`() {
        assertEquals(Backup.Failure.FORMAT, Backup.readBlob("not a code", passphrase).failure)
        assertEquals(Backup.Failure.FORMAT, Backup.readBlob(blobVector.replace("RIPPLE-BKP:v1:", "RIPPLE-BKP:v2:"), passphrase).failure)
        assertEquals(Backup.Failure.BASE64, Backup.readBlob("RIPPLE-BKP:v1:!!!", passphrase).failure)
        // 100 chars of valid base64 = 75 bytes ≠ 141.
        assertEquals(Backup.Failure.LENGTH, Backup.readBlob("RIPPLE-BKP:v1:" + "A".repeat(100), passphrase).failure)
    }

    @Test fun `pasted blobs survive newlines and either base64 alphabet`() {
        val body = blobVector.substringAfter("v1:")
        val wrapped = "RIPPLE-BKP:v1:\n" + body.chunked(64).joinToString("\n ")
        assertTrue(Backup.readBlob(wrapped, passphrase).ok)
        // Standard alphabet with padding (what other tools might produce) is accepted too.
        val standard = "RIPPLE-BKP:v1:" + java.util.Base64.getEncoder().encodeToString(
            java.util.Base64.getUrlDecoder().decode(body),
        )
        assertTrue(Backup.readBlob(standard, passphrase).ok)
    }

    @Test fun `unicode passphrase round-trips through UTF-8`() {
        val pw = "ünïcödé pàss🔐 — 密码"
        val blob = Backup.createBlob(scalar, wire, pw)
        assertTrue(Backup.readBlob(blob, pw).ok)
        assertEquals(Backup.Failure.PASSPHRASE, Backup.readBlob(blob, "ünïcödé pàss🔐 — 密馬").failure)
    }
}
