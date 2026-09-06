package app.ripple.mesh.core

import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey

/**
 * A node's long-term identity: a P-256 key pair plus the derived NodeId.
 * The private key may live inside the Android Keystore (production) or in memory (tests).
 */
class Identity(val privateKey: PrivateKey, val publicKey: PublicKey) {
    val publicKeyWire: ByteArray = Crypto.publicKeyToWire(publicKey)
    val nodeId: NodeId = NodeId.fromPublicKey(publicKeyWire)

    fun sign(unsignedPacket: ByteArray): ByteArray = Crypto.sign(privateKey, unsignedPacket)

    companion object {
        fun generate(): Identity = Crypto.generateKeyPair().let { Identity(it.private, it.public) }
        fun from(pair: KeyPair) = Identity(pair.private, pair.public)
    }
}

/** Convenience builders for signed packets originated by this identity. */
object PacketFactory {
    fun build(
        identity: Identity,
        type: PacketType,
        payload: ByteArray,
        destination: NodeId = NodeId.BROADCAST,
        flags: Int = 0,
        ttl: Int = Protocol.MAX_TTL,
        messageId: ByteArray = Crypto.randomBytes(Protocol.MESSAGE_ID_SIZE),
        timestamp: Long = System.currentTimeMillis(),
    ): Packet {
        val unsigned = Packet(type, flags, ttl, messageId, identity.nodeId, destination, timestamp, payload, ByteArray(Protocol.SIGNATURE_SIZE))
        return unsigned.copy(signature = identity.sign(unsigned.encodeUnsigned()))
    }

    fun announce(identity: Identity, displayName: String): Packet =
        build(identity, PacketType.ANNOUNCE, Announce(identity.publicKeyWire, displayName.trimToNameLimit()).encode())

    fun broadcastText(identity: Identity, text: String): Packet =
        build(identity, PacketType.MESSAGE, text.toByteArray(Charsets.UTF_8))

    fun directText(identity: Identity, destination: NodeId, recipientWire: ByteArray, text: String): Packet {
        val messageId = Crypto.randomBytes(Protocol.MESSAGE_ID_SIZE)
        val box = Crypto.encrypt(recipientWire, messageId, identity.nodeId, destination, text.toByteArray(Charsets.UTF_8))
        return build(identity, PacketType.MESSAGE, box, destination, Flags.ENCRYPTED, messageId = messageId)
    }

    fun ack(identity: Identity, destination: NodeId, acknowledgedMessageId: ByteArray): Packet =
        build(identity, PacketType.ACK, acknowledgedMessageId, destination)

    /** An SOS beacon, broadcast mesh-wide. `location` is only included when GPS was opted in. */
    fun sos(identity: Identity, text: String, location: SosLocation? = null): Packet =
        build(identity, PacketType.SOS, SosCodec.encode(text, location))

    private fun String.trimToNameLimit(): String {
        var s = this
        while (s.toByteArray(Charsets.UTF_8).size > Protocol.MAX_NAME_BYTES) s = s.dropLast(1)
        return s
    }
}
