package app.ripple.mesh.core

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * All cryptography for the protocol, built only on the JCA primitives available on
 * Android API 26+ (P-256 ECDSA/ECDH, SHA-256, HMAC, AES-GCM). No third-party libs.
 */
object Crypto {
    private const val CURVE = "secp256r1"
    private val random = SecureRandom()

    val p256Params: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec(CURVE)) }
            .getParameterSpec(ECParameterSpec::class.java)
    }

    fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(CURVE), random) }.generateKeyPair()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    // ---- public key wire form (X9.63 uncompressed) --------------------------------

    fun publicKeyToWire(key: PublicKey): ByteArray {
        val w = (key as ECPublicKey).w
        return byteArrayOf(0x04) + w.affineX.toFixed(32) + w.affineY.toFixed(32)
    }

    fun publicKeyFromWire(wire: ByteArray): PublicKey {
        if (wire.size != 65 || wire[0] != 0x04.toByte()) throw ProtocolException("bad public key wire form")
        val x = BigInteger(1, wire.copyOfRange(1, 33))
        val y = BigInteger(1, wire.copyOfRange(33, 65))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), p256Params))
    }

    // ---- ECDSA (raw r‖s, 64 bytes) -------------------------------------------------

    fun sign(privateKey: PrivateKey, unsignedPacket: ByteArray): ByteArray {
        val input = unsignedPacket.copyOf().also { it[3] = 0 }
        val der = Signature.getInstance("SHA256withECDSA").run { initSign(privateKey); update(input); sign() }
        return derToRaw(der)
    }

    fun verify(publicKey: PublicKey, unsignedPacket: ByteArray, rawSignature: ByteArray): Boolean {
        if (rawSignature.size != Protocol.SIGNATURE_SIZE) return false
        val input = unsignedPacket.copyOf().also { it[3] = 0 }
        return try {
            Signature.getInstance("SHA256withECDSA").run { initVerify(publicKey); update(input); verify(rawToDer(rawSignature)) }
        } catch (_: Exception) { false }
    }

    // ---- ECIES: P-256 ECDH → HKDF-SHA256 → AES-256-GCM ----------------------------

    fun encrypt(recipientWire: ByteArray, messageId: ByteArray, source: NodeId, destination: NodeId, plaintext: ByteArray): ByteArray {
        val ephemeral = generateKeyPair()
        return encryptWith(ephemeral, recipientWire, messageId, source, destination, plaintext, randomBytes(12))
    }

    /** Deterministic variant (used by tests against the shared vectors). */
    fun encryptWith(ephemeral: KeyPair, recipientWire: ByteArray, messageId: ByteArray, source: NodeId, destination: NodeId, plaintext: ByteArray, nonce: ByteArray): ByteArray {
        val shared = ecdh(ephemeral.private, publicKeyFromWire(recipientWire))
        val key = hkdf(shared, messageId, Protocol.HKDF_INFO, 32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad(messageId, source, destination))
        val ctAndTag = cipher.doFinal(plaintext)
        return publicKeyToWire(ephemeral.public) + nonce + ctAndTag
    }

    fun decrypt(recipientPrivate: PrivateKey, messageId: ByteArray, source: NodeId, destination: NodeId, payload: ByteArray): ByteArray {
        if (payload.size < 65 + 12 + 16) throw ProtocolException("ecies payload too short")
        val ephemeralPub = publicKeyFromWire(payload.copyOfRange(0, 65))
        val nonce = payload.copyOfRange(65, 77)
        val ctAndTag = payload.copyOfRange(77, payload.size)
        val shared = ecdh(recipientPrivate, ephemeralPub)
        val key = hkdf(shared, messageId, Protocol.HKDF_INFO, 32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad(messageId, source, destination))
        return cipher.doFinal(ctAndTag)
    }

    private fun aad(messageId: ByteArray, source: NodeId, destination: NodeId) = messageId + source.bytes + destination.bytes

    fun ecdh(privateKey: PrivateKey, publicKey: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run { init(privateKey); doPhase(publicKey, true); generateSecret() }

    /** RFC 5869 HKDF with HMAC-SHA256. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            mac.update(t); mac.update(info); mac.update(counter.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n; counter++
        }
        return out
    }

    // ---- DER <-> raw signature conversion -----------------------------------------

    internal fun derToRaw(der: ByteArray): ByteArray {
        var i = 0
        require(der[i++] == 0x30.toByte()) { "not a DER sequence" }
        if (der[i].toInt() and 0x80 != 0) i += (der[i].toInt() and 0x7f) // long-form length
        i++
        fun readInt(): ByteArray {
            require(der[i++] == 0x02.toByte()) { "expected INTEGER" }
            val len = der[i++].toInt() and 0xff
            val v = der.copyOfRange(i, i + len); i += len
            return BigInteger(1, v).toFixed(32)
        }
        val r = readInt(); val s = readInt()
        return r + s
    }

    internal fun rawToDer(raw: ByteArray): ByteArray {
        fun derInt(b: ByteArray): ByteArray {
            var v = b.dropWhile { it == 0.toByte() }.toByteArray()
            if (v.isEmpty()) v = byteArrayOf(0)
            if (v[0].toInt() and 0x80 != 0) v = byteArrayOf(0) + v
            return byteArrayOf(0x02, v.size.toByte()) + v
        }
        val body = derInt(raw.copyOfRange(0, 32)) + derInt(raw.copyOfRange(32, 64))
        val lenBytes = if (body.size < 0x80) byteArrayOf(body.size.toByte()) else byteArrayOf(0x81.toByte(), body.size.toByte())
        return byteArrayOf(0x30) + lenBytes + body
    }

    private fun BigInteger.toFixed(size: Int): ByteArray {
        val raw = toByteArray()
        return when {
            raw.size == size -> raw
            raw.size > size -> raw.copyOfRange(raw.size - size, raw.size)
            else -> ByteArray(size - raw.size) + raw
        }
    }
}
