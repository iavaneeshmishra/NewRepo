package app.ripple.mesh.core

import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Identity backup & restore (ROADMAP Phase 0.3, app-layer — no wire changes).
 *
 * The exportable identity material is the 32-byte P-256 private scalar plus the
 * 65-byte uncompressed public key wire form (97 bytes). That payload is encrypted with
 * a passphrase-derived key (PBKDF2-HMAC-SHA256 → AES-256-GCM) and base64url'd into a
 * single text blob designed for offline transport: a QR screen on one phone, pasted
 * text over any channel, even a photo of the screen. Restoring on a fresh install
 * re-derives the *same* node id and key, so the device keeps decrypting its old
 * messages and peers keep verifying its signatures.
 *
 * **A leaked passphrase means a lost identity**: whoever holds blob + passphrase *is*
 * this node — Ripple v1 has no revocation (that lands with key rotation in Phase 2.2).
 * See docs/BACKUP.md for the format and the security notes.
 *
 * Pure JVM on purpose (like [Pairing]): the byte-level vectors are mirrored verbatim
 * in `BackupTest.kt` / `BackupTests.swift`, so a blob written on one platform decodes
 * on the other.
 */
object Backup {
    private const val PREFIX = "RIPPLE-BKP"
    private const val BLOB_VERSION = "v1"
    const val SALT_SIZE = 16
    const val NONCE_SIZE = 12
    const val TAG_SIZE = 16
    const val KEY_SIZE = 32
    const val SCALAR_SIZE = 32
    const val WIRE_SIZE = 65
    const val PAYLOAD_SIZE = SCALAR_SIZE + WIRE_SIZE           // 97
    const val RAW_SIZE = SALT_SIZE + NONCE_SIZE + PAYLOAD_SIZE + TAG_SIZE // 141

    /**
     * PBKDF2 work factor. Chosen so restore takes well under a second on low-end
     * phones while keeping online guessing of a decent passphrase infeasible; the
     * blob is offline, so this only guards the offline copy too — see docs/BACKUP.md §4.
     */
    const val PBKDF2_ITERATIONS = 150_000

    /** Domain separation: this key is for Ripple backup blobs and nothing else. */
    private val AAD: ByteArray = "ripple/backup/v1".toByteArray(Charsets.UTF_8)

    enum class Failure { FORMAT, BASE64, LENGTH, PASSPHRASE }

    /** [payload] is non-null exactly when [failure] is null. */
    class ReadResult(val payload: ByteArray?, val failure: Failure?) {
        val ok: Boolean get() = payload != null
    }

    /**
     * Produce the canonical text blob: `RIPPLE-BKP:v1:<base64url(salt‖nonce‖ct‖tag)>`.
     * Random salt/nonce; same inputs, different output each call.
     */
    fun createBlob(scalar: ByteArray, publicKeyWire: ByteArray, passphrase: String): String =
        createBlob(scalar, publicKeyWire, passphrase, Crypto.randomBytes(SALT_SIZE), Crypto.randomBytes(NONCE_SIZE))

    /** Deterministic variant (fixed salt+nonce) — used by the shared cross-platform test vectors. */
    fun createBlob(scalar: ByteArray, publicKeyWire: ByteArray, passphrase: String, salt: ByteArray, nonce: ByteArray): String {
        require(scalar.size == SCALAR_SIZE && publicKeyWire.size == WIRE_SIZE) { "bad identity material sizes" }
        require(salt.size == SALT_SIZE && nonce.size == NONCE_SIZE) { "bad salt/nonce sizes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(deriveKey(passphrase, salt), "AES"), GCMParameterSpec(TAG_SIZE * 8, nonce))
        cipher.updateAAD(AAD)
        val ctAndTag = cipher.doFinal(scalar + publicKeyWire)
        return PREFIX + ":" + BLOB_VERSION + ":" + encodeBase64Url(salt + nonce + ctAndTag)
    }

    /**
     * Parse and decrypt a blob. Whitespace/newlines anywhere in [blob] are tolerated
     * (paste from messages or emails), and a standard-base64 alphabet is accepted
     * even though canonical form is URL-safe. A GCM auth failure is reported as
     * [Failure.PASSPHRASE] — it is indistinguishable from (and the expected answer to)
     * a wrong passphrase; tampering never yields plaintext.
     */
    fun readBlob(blob: String, passphrase: String): ReadResult {
        val text = blob.filterNot { it.isWhitespace() }
        val parts = text.split(':', limit = 3)
        if (parts.size != 3 || !parts[0].equals(PREFIX, ignoreCase = true) || !parts[1].equals(BLOB_VERSION, ignoreCase = true)) {
            return ReadResult(null, Failure.FORMAT)
        }
        val raw = decodeBase64Url(parts[2]) ?: return ReadResult(null, Failure.BASE64)
        if (raw.size != RAW_SIZE) return ReadResult(null, Failure.LENGTH)
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(deriveKey(passphrase, raw.copyOfRange(0, SALT_SIZE)), "AES"),
                GCMParameterSpec(TAG_SIZE * 8, raw.copyOfRange(SALT_SIZE, SALT_SIZE + NONCE_SIZE)),
            )
            cipher.updateAAD(AAD)
            ReadResult(cipher.doFinal(raw.copyOfRange(SALT_SIZE + NONCE_SIZE, raw.size)), null)
        }.getOrDefault(ReadResult(null, Failure.PASSPHRASE))
    }

    // ---- payload accessors ----------------------------------------------------------

    fun scalarOf(payload: ByteArray): ByteArray = payload.copyOfRange(0, SCALAR_SIZE)
    fun publicKeyOf(payload: ByteArray): ByteArray = payload.copyOfRange(SCALAR_SIZE, PAYLOAD_SIZE)

    /** Node id (lowercase hex) the restored payload re-derives — for UI confirmation. */
    fun nodeIdOf(payload: ByteArray): String = NodeId.fromPublicKey(publicKeyOf(payload)).hex

    /**
     * PBKDF2-HMAC-SHA256 over the passphrase's UTF-8 bytes (the JVM applies the
     * password chars as-is; iOS mirrors this by hashing the raw UTF-8 bytes, so the
     * passphrase must be typed identically on both platforms — see docs/BACKUP.md §4).
     */
    internal fun deriveKey(passphrase: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_SIZE * 8)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    // ---- base64url (RFC 4648 §5), tolerant of the standard alphabet and padding ------

    private fun encodeBase64Url(bytes: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun decodeBase64Url(value: String): ByteArray? {
        val std = value.replace('-', '+').replace('_', '/').replace("=", "")
        if (std.length % 4 == 1) return null
        val padded = std + "=".repeat((4 - std.length % 4) % 4)
        return runCatching { java.util.Base64.getDecoder().decode(padded) }.getOrNull()
    }
}
