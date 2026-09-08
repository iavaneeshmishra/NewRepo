package app.ripple.mesh.core

import java.security.MessageDigest
import java.util.Locale

/**
 * Out-of-band pairing (ROADMAP Phase 0.2, app-layer — no wire changes).
 *
 * Two phones that can see each other can compare full 65-byte public keys instead
 * of only the 8-byte node id, so identity verification is not limited by the id's
 * truncation. Pure JVM (no Android APIs) so the exact same bytes are produced on
 * Android and iOS; see docs/PAIRING.md for the byte-level format.
 */
object Pairing {
    private const val PREFIX = "RIPPLE-ID"
    private const val CODE_VERSION = "v1"
    private const val NODE_ID_HEX_LEN = Protocol.NODE_ID_SIZE * 2      // 16
    private const val PUBLIC_KEY_HEX_LEN = 65 * 2                        // 130
    private const val HEX_CHARS = "0123456789abcdef"
    private const val HEX_UPPER = "0123456789ABCDEF"

    /** A parsed identity code: an 8-byte node id, its full P-256 public key, and an optional display name. */
    data class IdentityCode(
        val nodeIdHex: String,
        val publicKeyWireHex: String,
        val name: String?,
    ) {
        /** Canonical text form: `RIPPLE-ID:v1:<id16>:<key130>[:<percent-encoded name>]`. */
        fun encode(): String = buildString {
            append(PREFIX).append(':').append(CODE_VERSION).append(':')
            append(nodeIdHex).append(':').append(publicKeyWireHex)
            if (!name.isNullOrBlank()) append(':').append(percentEncode(name))
        }
    }

    /** Encode this device's identity as a canonical text code (shown as a QR / share sheet). */
    fun encodeIdentityCode(publicKeyWire: ByteArray, name: String? = null): String {
        val nodeId = NodeId.fromPublicKey(publicKeyWire)
        return IdentityCode(nodeId.hex, publicKeyWire.toHex(), name).encode()
    }

    /**
     * Parse and validate an identity code. Returns null when the code is malformed
     * (bad prefix/version/hex) or self-inconsistent (the node id is not the
     * truncated SHA-256 of the claimed public key). A name is only kept when it
     * survives percent-decoding as non-empty UTF-8.
     */
    fun decodeIdentityCode(code: String): IdentityCode? {
        val parts = code.split(':')
        if (parts.size !in 4..5) return null
        // Prefix/version are ASCII; accept either case. Hex segments are normalised below.
        if (!parts[0].equals(PREFIX, ignoreCase = true) || !parts[1].equals(CODE_VERSION, ignoreCase = true)) return null
        val nodeIdHex = parts[2].lowercase(Locale.ROOT)
        val keyHex = parts[3].lowercase(Locale.ROOT)
        if (nodeIdHex.length != NODE_ID_HEX_LEN || keyHex.length != PUBLIC_KEY_HEX_LEN) return null
        if (!nodeIdHex.all { it in HEX_CHARS } || !keyHex.all { it in HEX_CHARS }) return null
        val wire = keyHex.hexToBytes()
        if (NodeId.fromPublicKey(wire).hex != nodeIdHex) return null
        val name = parts.getOrNull(4)?.let { percentDecode(it) }?.takeIf { it.isNotEmpty() }
        return IdentityCode(nodeIdHex, keyHex, name)
    }

    /**
     * 12-digit decimal safety code (grouped 4-4-4, e.g. "5046 5756 7335") derived
     * from the two devices' full public keys. Order-independent: both phones show
     * the same digits, so the pair compares them out-of-band to confirm they are
     * each holding the other's real key. Only the first 40 bits of SHA-256 over the
     * two keys (in canonical byte order) are used; the tiny modulo bias of the
     * decimal projection is irrelevant at 10^12 scale.
     */
    fun safetyCode(publicKeyWireA: ByteArray, publicKeyWireB: ByteArray): String {
        val ordered = if (compareUnsigned(publicKeyWireA, publicKeyWireB) <= 0) {
            publicKeyWireA + publicKeyWireB
        } else {
            publicKeyWireB + publicKeyWireA
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(ordered)
        var value = 0L
        for (i in 0 until 5) value = (value shl 8) or (digest[i].toLong() and 0xff)
        val digits = (value % 1_000_000_000_000L).toString().padStart(12, '0')
        return digits.chunked(4).joinToString(" ")
    }

    /**
     * The outcome of pinning an imported peer key against the verified-peer store.
     * One id may pin exactly one key; re-pinning the same key is a refresh, while the
     * same id arriving with a *different* key is always a [CONFLICT] that must be
     * refused loudly and never silently overwrite the stored pin (docs/PAIRING.md §5).
     */
    enum class VerifyOutcome { VERIFIED, ALREADY_VERIFIED, CONFLICT }

    /**
     * The shared pinning rule (Android and iOS UIs must not diverge from it). Hex keys
     * are compared case-insensitively; canonical form is lowercase.
     */
    fun verifyOutcome(existingPublicKeyWireHex: String?, importedPublicKeyWireHex: String): VerifyOutcome {
        val incoming = importedPublicKeyWireHex.lowercase(Locale.ROOT)
        val pinned = existingPublicKeyWireHex?.lowercase(Locale.ROOT)
        return when {
            pinned == null -> VerifyOutcome.VERIFIED
            pinned == incoming -> VerifyOutcome.ALREADY_VERIFIED
            else -> VerifyOutcome.CONFLICT
        }
    }

    /** Unsigned lexicographic byte order (must match Data comparison on iOS). */
    fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a[i].toInt() and 0xff
            val y = b[i].toInt() and 0xff
            if (x != y) return x - y
        }
        return a.size - b.size
    }

    /** RFC 3986 unreserved bytes pass through; everything else becomes %XX (uppercase hex). */
    fun percentEncode(value: String): String = buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xff
            val ok = c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
                c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
            if (ok) append(c.toChar())
            else append('%').append(HEX_UPPER[c ushr 4]).append(HEX_UPPER[c and 0x0f])
        }
    }

    /** Inverse of [percentEncode]; tolerant of stray '%' (left as-is when not followed by hex). */
    fun percentDecode(value: String): String {
        val bytes = ArrayList<Byte>(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%') {
                val h = value.getOrNull(i + 1)?.let { Character.digit(it, 16) } ?: -1
                val l = value.getOrNull(i + 2)?.let { Character.digit(it, 16) } ?: -1
                if (h in 0..15 && l in 0..15) {
                    bytes.add(((h shl 4) or l).toByte())
                    i += 3
                    continue
                }
            }
            // Append the UTF-8 bytes of the raw character so non-ASCII survives.
            c.toString().toByteArray(Charsets.UTF_8).forEach { bytes.add(it) }
            i += 1
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }
}
