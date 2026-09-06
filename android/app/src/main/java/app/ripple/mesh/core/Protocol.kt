package app.ripple.mesh.core

import java.nio.ByteBuffer
import java.security.MessageDigest

/** Constants and wire-format codec for Ripple Mesh Protocol v1 (see /PROTOCOL.md). */
object Protocol {
    const val VERSION = 1
    const val MAX_TTL = 7
    const val MAX_PAYLOAD = 4096
    const val HEADER_SIZE = 46
    const val SIGNATURE_SIZE = 64
    const val MAX_PACKET = HEADER_SIZE + MAX_PAYLOAD + SIGNATURE_SIZE
    const val MAX_NAME_BYTES = 64
    const val NODE_ID_SIZE = 8
    const val MESSAGE_ID_SIZE = 16
    val HKDF_INFO: ByteArray = "ripple/v1/msg".toByteArray(Charsets.UTF_8)

    const val SEEN_TTL_MS = 24L * 3600 * 1000
    // Store-and-forward guarantee: relayed packets are held for 72 h.
    const val RELAY_TTL_MS = 72L * 3600 * 1000
    const val REASSEMBLY_TTL_MS = 10_000L

    // SOS beacon payload (PROTOCOL.md §2.2).
    const val MAX_SOS_TEXT = 128
    const val SOS_FLAG_HAS_LOCATION = 0x01

    // BLE
    const val SERVICE_UUID = "7E2C4B10-4B7D-4E3A-9C1F-8A2E5D6F1A01"
    const val RX_UUID = "7E2C4B10-4B7D-4E3A-9C1F-8A2E5D6F1A02"
    const val TX_UUID = "7E2C4B10-4B7D-4E3A-9C1F-8A2E5D6F1A03"
    const val CCCD_UUID = "00002902-0000-1000-8000-00805F9B34FB"
}

enum class PacketType(val code: Int) {
    ANNOUNCE(1), MESSAGE(2), ACK(3), SOS(4);

    companion object {
        fun from(code: Int): PacketType? = entries.firstOrNull { it.code == code }
    }
}

object Flags {
    const val ENCRYPTED = 0x01
}

/** Battery-dependent relay policy (PROTOCOL.md §7). */
object BatteryProfile {
    const val PERFORMANCE = 0
    const val BALANCED = 1
    const val POWER_SAVER = 2

    const val MIN = PERFORMANCE
    const val MAX = POWER_SAVER

    fun isValid(code: Int) = code in MIN..MAX

    /** POWER_SAVER conserves battery by not relaying ordinary chat; SOS/ANNOUNCE always relay. */
    fun relaysOrdinary(code: Int): Boolean = code != POWER_SAVER
}

/** A decoded SOS beacon payload (PROTOCOL.md §2.2). `location` is null unless GPS was opted in. */
data class SosPayload(val flags: Int, val text: String, val location: SosLocation?)

data class SosLocation(val latE7: Int, val lngE7: Int, val accuracyMeters: Int)

object SosCodec {
    fun encode(text: String, location: SosLocation?): ByteArray {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        require(textBytes.size <= Protocol.MAX_SOS_TEXT) { "sos text too long" }
        val buf = java.nio.ByteBuffer.allocate(2 + textBytes.size + (if (location != null) 10 else 0))
        buf.put((if (location != null) Protocol.SOS_FLAG_HAS_LOCATION else 0).toByte())
        buf.put(textBytes.size.toByte())
        buf.put(textBytes)
        if (location != null) {
            buf.putInt(location.latE7)
            buf.putInt(location.lngE7)
            buf.putShort(location.accuracyMeters.toShort())
        }
        return buf.array()
    }

    fun decode(payload: ByteArray): SosPayload {
        if (payload.size < 2) throw ProtocolException("sos payload too short")
        val flags = payload[0].toInt() and 0xff
        val textLen = payload[1].toInt() and 0xff
        if (textLen > Protocol.MAX_SOS_TEXT || payload.size < 2 + textLen) throw ProtocolException("sos payload malformed")
        val text = String(payload, 2, textLen, Charsets.UTF_8)
        val hasLocation = flags and Protocol.SOS_FLAG_HAS_LOCATION != 0
        if (hasLocation) {
            if (payload.size != 2 + textLen + 10) throw ProtocolException("sos payload length mismatch")
            val b = java.nio.ByteBuffer.wrap(payload, 2 + textLen, 10)
            return SosPayload(flags, text, SosLocation(b.int, b.int, b.short.toInt() and 0xffff))
        }
        if (payload.size != 2 + textLen) throw ProtocolException("sos payload trailing bytes")
        return SosPayload(flags, text, null)
    }
}

/** 8-byte node identifier. Value type with proper equality so it can key maps. */
@JvmInline
value class NodeId(val bytes: ByteArray) {
    init { require(bytes.size == Protocol.NODE_ID_SIZE) { "NodeId must be 8 bytes" } }

    val hex: String get() = bytes.toHex()
    val isBroadcast: Boolean get() = bytes.all { it == 0.toByte() }

    /** `xxxx-xxxx-xxxx-xxxx` */
    val display: String get() = hex.chunked(4).joinToString("-")

    /** Short human label — last group of the display form. */
    val short: String get() = hex.takeLast(4)

    override fun toString(): String = display

    companion object {
        val BROADCAST = NodeId(ByteArray(Protocol.NODE_ID_SIZE))
        fun fromPublicKey(wire: ByteArray): NodeId =
            NodeId(MessageDigest.getInstance("SHA-256").digest(wire).copyOf(Protocol.NODE_ID_SIZE))
        fun fromHex(hex: String): NodeId = NodeId(hex.hexToBytes())
    }
}

// NodeId is a value class over ByteArray; ByteArray equality is by reference, so
// we key maps by hex string throughout instead of by NodeId directly.

data class Packet(
    val type: PacketType,
    val flags: Int,
    val ttl: Int,
    val messageId: ByteArray,
    val source: NodeId,
    val destination: NodeId,
    val timestamp: Long,
    val payload: ByteArray,
    val signature: ByteArray,
) {
    val version: Int get() = Protocol.VERSION
    val isEncrypted: Boolean get() = flags and Flags.ENCRYPTED != 0
    val messageIdHex: String get() = messageId.toHex()

    init {
        require(messageId.size == Protocol.MESSAGE_ID_SIZE)
        require(payload.size <= Protocol.MAX_PAYLOAD)
        require(ttl in 0..255)
    }

    fun withTtl(newTtl: Int): Packet = copy(ttl = newTtl)

    /** Header + payload, without signature. */
    fun encodeUnsigned(): ByteArray {
        val buf = ByteBuffer.allocate(Protocol.HEADER_SIZE + payload.size)
        buf.put(Protocol.VERSION.toByte())
        buf.put(type.code.toByte())
        buf.put(flags.toByte())
        buf.put(ttl.toByte())
        buf.put(messageId)
        buf.put(source.bytes)
        buf.put(destination.bytes)
        buf.putLong(timestamp)
        buf.putShort(payload.size.toShort())
        buf.put(payload)
        return buf.array()
    }

    fun encode(): ByteArray = encodeUnsigned() + signature

    /** SHA-256 over the unsigned bytes with ttl zeroed. */
    fun signingDigest(): ByteArray = signingDigest(encodeUnsigned())

    override fun equals(other: Any?): Boolean = other is Packet && other.encode().contentEquals(encode())
    override fun hashCode(): Int = messageId.contentHashCode()

    companion object {
        fun signingDigest(unsigned: ByteArray): ByteArray {
            val copy = unsigned.copyOf()
            copy[3] = 0
            return MessageDigest.getInstance("SHA-256").digest(copy)
        }

        fun decode(bytes: ByteArray): Packet {
            if (bytes.size < Protocol.HEADER_SIZE + Protocol.SIGNATURE_SIZE) throw ProtocolException("packet too short")
            val buf = ByteBuffer.wrap(bytes)
            val version = buf.get().toInt() and 0xff
            if (version != Protocol.VERSION) throw ProtocolException("unsupported version $version")
            val type = PacketType.from(buf.get().toInt() and 0xff) ?: throw ProtocolException("unknown type")
            val flags = buf.get().toInt() and 0xff
            val ttl = buf.get().toInt() and 0xff
            val messageId = ByteArray(Protocol.MESSAGE_ID_SIZE).also { buf.get(it) }
            val source = ByteArray(Protocol.NODE_ID_SIZE).also { buf.get(it) }
            val destination = ByteArray(Protocol.NODE_ID_SIZE).also { buf.get(it) }
            val timestamp = buf.getLong()
            val payloadLength = buf.getShort().toInt() and 0xffff
            if (payloadLength > Protocol.MAX_PAYLOAD) throw ProtocolException("payload too large")
            if (bytes.size != Protocol.HEADER_SIZE + payloadLength + Protocol.SIGNATURE_SIZE) throw ProtocolException("length mismatch")
            val payload = ByteArray(payloadLength).also { buf.get(it) }
            val signature = ByteArray(Protocol.SIGNATURE_SIZE).also { buf.get(it) }
            return Packet(type, flags, ttl, messageId, NodeId(source), NodeId(destination), timestamp, payload, signature)
        }
    }
}

data class Announce(val publicKeyWire: ByteArray, val name: String) {
    fun encode(): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        require(nameBytes.size <= Protocol.MAX_NAME_BYTES) { "name too long" }
        return publicKeyWire + byteArrayOf(nameBytes.size.toByte()) + nameBytes
    }

    companion object {
        fun decode(payload: ByteArray): Announce {
            if (payload.size < 66) throw ProtocolException("announce too short")
            val len = payload[65].toInt() and 0xff
            if (payload.size != 66 + len) throw ProtocolException("announce length mismatch")
            return Announce(payload.copyOfRange(0, 65), String(payload, 66, len, Charsets.UTF_8))
        }
    }
}

class ProtocolException(message: String) : Exception(message)

// ---- hex helpers --------------------------------------------------------------

private const val HEX = "0123456789abcdef"

fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
    }
    return sb.toString()
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { i -> ((Character.digit(this[2 * i], 16) shl 4) or Character.digit(this[2 * i + 1], 16)).toByte() }
}
