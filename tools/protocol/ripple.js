'use strict';
/**
 * Reference implementation of the Ripple Mesh Protocol v1 (see PROTOCOL.md).
 * Pure Node.js (crypto module only). Used to generate protocol/test-vectors.json
 * and to cross-check the Kotlin and Swift implementations.
 */
const crypto = require('crypto');

const VERSION = 1;
const MAX_TTL = 7;
const MAX_PAYLOAD = 4096;
const HEADER_SIZE = 46;
const SIGNATURE_SIZE = 64;
const HKDF_INFO = Buffer.from('ripple/v1/msg', 'utf8');
const BROADCAST_ID = Buffer.alloc(8, 0);

const PacketType = Object.freeze({ ANNOUNCE: 1, MESSAGE: 2, ACK: 3, SOS: 4 });
const Flags = Object.freeze({ ENCRYPTED: 0x01 });

// SOS beacon payload limits (PROTOCOL.md §2.2).
const MAX_SOS_TEXT = 128;
const SOS_FLAG_HAS_LOCATION = 0x01;

// Battery-dependent relay policies (PROTOCOL.md §7).
const BatteryProfile = Object.freeze({ PERFORMANCE: 0, BALANCED: 1, POWER_SAVER: 2 });

/** Store-and-forward guarantee: relayed packets are held for 72 h (PROTOCOL.md §6). */
const RELAY_RETENTION_MS = 72 * 3600 * 1000;

/**
 * Whether a node running on the given battery profile relays ordinary (non-SOS,
 * non-ANNOUNCE) packets it is merely passing on. POWER_SAVER conserves battery by
 * acting as a leaf: it still relays ANNOUNCEs and SOS beacons, but not chat traffic.
 */
function relaysOrdinary(profile) {
  return profile !== BatteryProfile.POWER_SAVER;
}

// ---------------------------------------------------------------- identity --

function generateIdentity() {
  const { privateKey, publicKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  return identityFromKeys(privateKey, publicKey);
}

function identityFromKeys(privateKey, publicKey) {
  const pubWire = publicKeyWire(publicKey);
  return { privateKey, publicKey, publicKeyWire: pubWire, nodeId: nodeIdFromPublicKey(pubWire) };
}

/** Deterministic identity from a 32-byte scalar (test vectors only). */
function identityFromPrivateScalar(scalarHex) {
  const d = Buffer.from(scalarHex, 'hex');
  if (d.length !== 32) throw new Error('scalar must be 32 bytes');
  // Build PKCS#8 via JWK: need public point. Derive it with ECDH helper.
  const ecdh = crypto.createECDH('prime256v1');
  ecdh.setPrivateKey(d);
  const pub = ecdh.getPublicKey(); // uncompressed 65 bytes
  const jwk = {
    kty: 'EC', crv: 'P-256',
    x: pub.subarray(1, 33).toString('base64url'),
    y: pub.subarray(33, 65).toString('base64url'),
    d: d.toString('base64url'),
  };
  const privateKey = crypto.createPrivateKey({ key: jwk, format: 'jwk' });
  const publicKey = crypto.createPublicKey({ key: { ...jwk, d: undefined }, format: 'jwk' });
  return identityFromKeys(privateKey, publicKey);
}

function publicKeyWire(publicKey) {
  const jwk = publicKey.export({ format: 'jwk' });
  return Buffer.concat([Buffer.from([0x04]), Buffer.from(jwk.x, 'base64url'), Buffer.from(jwk.y, 'base64url')]);
}

function publicKeyFromWire(wire) {
  if (wire.length !== 65 || wire[0] !== 0x04) throw new Error('bad public key wire form');
  return crypto.createPublicKey({
    key: { kty: 'EC', crv: 'P-256', x: wire.subarray(1, 33).toString('base64url'), y: wire.subarray(33, 65).toString('base64url') },
    format: 'jwk',
  });
}

function nodeIdFromPublicKey(wire) {
  return crypto.createHash('sha256').update(wire).digest().subarray(0, 8);
}

function formatNodeId(id) {
  return id.toString('hex').match(/.{4}/g).join('-');
}

// ---------------------------------------------------------------- packets ---

/**
 * @typedef Packet
 * @property {number} version
 * @property {number} type
 * @property {number} flags
 * @property {number} ttl
 * @property {Buffer} messageId   16 bytes
 * @property {Buffer} source      8 bytes
 * @property {Buffer} destination 8 bytes
 * @property {bigint} timestamp   ms
 * @property {Buffer} payload
 * @property {Buffer} signature   64 bytes
 */

function encodeUnsigned(p) {
  if (p.payload.length > MAX_PAYLOAD) throw new Error('payload too large');
  const buf = Buffer.alloc(HEADER_SIZE + p.payload.length);
  buf[0] = p.version;
  buf[1] = p.type;
  buf[2] = p.flags;
  buf[3] = p.ttl;
  p.messageId.copy(buf, 4);
  p.source.copy(buf, 20);
  p.destination.copy(buf, 28);
  buf.writeBigUInt64BE(BigInt(p.timestamp), 36);
  buf.writeUInt16BE(p.payload.length, 44);
  p.payload.copy(buf, HEADER_SIZE);
  return buf;
}

/** SHA-256 over header+payload with ttl zeroed (the digest ECDSA signs; exposed for test vectors). */
function signingDigest(unsignedBytes) {
  const copy = Buffer.from(unsignedBytes);
  copy[3] = 0;
  return crypto.createHash('sha256').update(copy).digest();
}

/** Bytes that are actually fed to ECDSA-with-SHA256: the unsigned packet with ttl zeroed. */
function signingInput(unsignedBytes) {
  const copy = Buffer.from(unsignedBytes);
  copy[3] = 0;
  return copy;
}

function sign(identity, unsignedBytes) {
  // ECDSA-SHA256 (= SHA256withECDSA on Android, P256.Signing on CryptoKit).
  // ieee-p1363 gives raw r||s (64 bytes) — exactly the wire format we want.
  return crypto.sign('sha256', signingInput(unsignedBytes), { key: identity.privateKey, dsaEncoding: 'ieee-p1363' });
}

function verify(publicKey, unsignedBytes, signature) {
  if (signature.length !== SIGNATURE_SIZE) return false;
  try {
    return crypto.verify('sha256', signingInput(unsignedBytes), { key: publicKey, dsaEncoding: 'ieee-p1363' }, signature);
  } catch { return false; }
}

function encode(p) {
  return Buffer.concat([encodeUnsigned(p), p.signature]);
}

function decode(buf) {
  if (buf.length < HEADER_SIZE + SIGNATURE_SIZE) throw new Error('packet too short');
  if (buf[0] !== VERSION) throw new Error('unsupported version');
  const payloadLength = buf.readUInt16BE(44);
  if (payloadLength > MAX_PAYLOAD) throw new Error('payload too large');
  if (buf.length !== HEADER_SIZE + payloadLength + SIGNATURE_SIZE) throw new Error('length mismatch');
  return {
    version: buf[0], type: buf[1], flags: buf[2], ttl: buf[3],
    messageId: Buffer.from(buf.subarray(4, 20)),
    source: Buffer.from(buf.subarray(20, 28)),
    destination: Buffer.from(buf.subarray(28, 36)),
    timestamp: buf.readBigUInt64BE(36),
    payload: Buffer.from(buf.subarray(HEADER_SIZE, HEADER_SIZE + payloadLength)),
    signature: Buffer.from(buf.subarray(HEADER_SIZE + payloadLength)),
  };
}

/** Build + sign a packet from the given identity. */
function buildPacket(identity, { type, flags = 0, ttl = MAX_TTL, messageId, destination = BROADCAST_ID, timestamp, payload }) {
  const p = {
    version: VERSION, type, flags, ttl,
    messageId: messageId || crypto.randomBytes(16),
    source: identity.nodeId,
    destination,
    timestamp: timestamp ?? BigInt(Date.now()),
    payload,
  };
  p.signature = sign(identity, encodeUnsigned(p));
  return p;
}

// ---------------------------------------------------------------- payloads --

function encodeAnnounce(identity, name) {
  const nameBytes = Buffer.from(name, 'utf8');
  if (nameBytes.length > 64) throw new Error('name too long');
  return Buffer.concat([identity.publicKeyWire, Buffer.from([nameBytes.length]), nameBytes]);
}

function decodeAnnounce(payload) {
  if (payload.length < 66) throw new Error('announce too short');
  const publicKeyWire = Buffer.from(payload.subarray(0, 65));
  const len = payload[65];
  if (payload.length !== 66 + len) throw new Error('announce length mismatch');
  return { publicKeyWire, name: payload.subarray(66).toString('utf8') };
}

// ---------------------------------------------------------------- ECIES -----

function deriveKey(sharedX, messageId) {
  return Buffer.from(crypto.hkdfSync('sha256', sharedX, messageId, HKDF_INFO, 32));
}

function aad(messageId, source, destination) {
  return Buffer.concat([messageId, source, destination]);
}

/** Encrypt plaintext for recipientWire. Returns payload bytes (E ‖ nonce ‖ ct ‖ tag). */
function eciesEncrypt({ recipientWire, messageId, source, destination, plaintext, ephemeralScalarHex, nonce }) {
  const eph = crypto.createECDH('prime256v1');
  if (ephemeralScalarHex) eph.setPrivateKey(Buffer.from(ephemeralScalarHex, 'hex')); else eph.generateKeys();
  const E = eph.getPublicKey(); // 65-byte uncompressed
  const sharedX = eph.computeSecret(recipientWire); // Node returns the x coordinate
  const key = deriveKey(sharedX, messageId);
  const n = nonce || crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', key, n);
  cipher.setAAD(aad(messageId, source, destination));
  const ct = Buffer.concat([cipher.update(plaintext), cipher.final()]);
  return Buffer.concat([E, n, ct, cipher.getAuthTag()]);
}

function eciesDecrypt({ recipientIdentity, messageId, source, destination, payload }) {
  if (payload.length < 65 + 12 + 16) throw new Error('ecies payload too short');
  const E = payload.subarray(0, 65);
  const nonce = payload.subarray(65, 77);
  const ct = payload.subarray(77, payload.length - 16);
  const tag = payload.subarray(payload.length - 16);
  const ecdh = crypto.createECDH('prime256v1');
  ecdh.setPrivateKey(Buffer.from(recipientIdentity.privateKey.export({ format: 'jwk' }).d, 'base64url'));
  const sharedX = ecdh.computeSecret(E);
  const key = deriveKey(sharedX, messageId);
  const decipher = crypto.createDecipheriv('aes-256-gcm', key, nonce);
  decipher.setAAD(aad(messageId, source, destination));
  decipher.setAuthTag(tag);
  return Buffer.concat([decipher.update(ct), decipher.final()]);
}

// ---------------------------------------------------------------- sos -------

/**
 * SOS beacon payload (PROTOCOL.md §2.2). `location` is optional and only present
 * when the sender has opted in to sharing GPS; nothing about it leaves the mesh.
 *
 * @param {{ text?: string, location?: {latE7:number, lngE7:number, accuracyMeters:number} }} opts
 * @returns {Buffer}
 */
function encodeSos(opts = {}) {
  const textBytes = Buffer.from(String(opts.text ?? ''), 'utf8');
  if (textBytes.length > MAX_SOS_TEXT) throw new Error('sos text too long');
  const hasLocation = opts.location != null;
  const body = Buffer.alloc(2 + textBytes.length + (hasLocation ? 10 : 0));
  body[0] = hasLocation ? SOS_FLAG_HAS_LOCATION : 0;
  body[1] = textBytes.length;
  textBytes.copy(body, 2);
  if (hasLocation) {
    const o = 2 + textBytes.length;
    body.writeInt32BE(opts.location.latE7 | 0, o);
    body.writeInt32BE(opts.location.lngE7 | 0, o + 4);
    body.writeUInt16BE(opts.location.accuracyMeters | 0, o + 8);
  }
  return body;
}

/**
 * @param {Buffer} payload
 * @returns {{ flags:number, text:string, location:null | {latE7:number, lngE7:number, accuracyMeters:number} }}
 */
function decodeSos(payload) {
  if (payload.length < 2) throw new Error('sos payload too short');
  const flags = payload[0];
  const textLen = payload[1];
  if (textLen > MAX_SOS_TEXT || payload.length < 2 + textLen) throw new Error('sos payload malformed');
  const text = payload.subarray(2, 2 + textLen).toString('utf8');
  const hasLocation = (flags & SOS_FLAG_HAS_LOCATION) !== 0;
  if (hasLocation && payload.length !== 2 + textLen + 10) throw new Error('sos payload length mismatch');
  if (!hasLocation && payload.length !== 2 + textLen) throw new Error('sos payload trailing bytes');
  if (!hasLocation) return { flags, text, location: null };
  const o = 2 + textLen;
  return {
    flags, text,
    location: { latE7: payload.readInt32BE(o), lngE7: payload.readInt32BE(o + 4), accuracyMeters: payload.readUInt16BE(o + 8) },
  };
}

// ---------------------------------------------------------------- rate limit -

/**
 * A sliding-window counter keyed independently per caller (the router keeps one
 * instance per message source). Allows at most `max` events in any `windowMs`
 * window; once the budget is spent the caller must drop the event.
 */
class RateLimiter {
  constructor(max, windowMs) {
    if (!(max > 0) || !(windowMs > 0)) throw new Error('rate limiter needs max>0, windowMs>0');
    this.max = max;
    this.windowMs = windowMs;
    this.stamps = [];
  }

  /** @returns {boolean} true if an event is within budget and was recorded. */
  allow(now = Date.now()) {
    const cutoff = now - this.windowMs;
    let i = 0;
    while (i < this.stamps.length && this.stamps[i] <= cutoff) i++;
    if (i > 0) this.stamps.splice(0, i);
    if (this.stamps.length >= this.max) return false;
    this.stamps.push(now);
    return true;
  }
}

// ---------------------------------------------------------------- fragments -

function fragment(packetBytes, frameSize, streamId) {
  const chunkSize = frameSize - 4;
  if (chunkSize < 1) throw new Error('frame too small');
  const total = Math.ceil(packetBytes.length / chunkSize) || 1;
  if (total > 255) throw new Error('packet needs too many fragments');
  const frames = [];
  for (let i = 0; i < total; i++) {
    const chunk = packetBytes.subarray(i * chunkSize, (i + 1) * chunkSize);
    const h = Buffer.alloc(4);
    h.writeUInt16BE(streamId, 0); h[2] = i; h[3] = total;
    frames.push(Buffer.concat([h, chunk]));
  }
  return frames;
}

class Reassembler {
  constructor(ttlMs = 10_000) { this.streams = new Map(); this.ttlMs = ttlMs; }
  /** @returns {Buffer|null} full packet when complete */
  push(frame, now = Date.now()) {
    if (frame.length < 4) return null;
    const streamId = frame.readUInt16BE(0), index = frame[2], total = frame[3];
    if (total === 0 || index >= total) return null;
    for (const [k, s] of this.streams) if (now - s.startedAt > this.ttlMs) this.streams.delete(k);
    let s = this.streams.get(streamId);
    if (!s || s.total !== total) { s = { total, parts: new Array(total), have: 0, startedAt: now }; this.streams.set(streamId, s); }
    if (!s.parts[index]) { s.parts[index] = Buffer.from(frame.subarray(4)); s.have++; }
    if (s.have === total) { this.streams.delete(streamId); return Buffer.concat(s.parts); }
    return null;
  }
}

module.exports = {
  VERSION, MAX_TTL, MAX_PAYLOAD, HEADER_SIZE, SIGNATURE_SIZE, BROADCAST_ID, PacketType, Flags,
  MAX_SOS_TEXT, SOS_FLAG_HAS_LOCATION, BatteryProfile, RELAY_RETENTION_MS, relaysOrdinary,
  generateIdentity, identityFromPrivateScalar, publicKeyWire, publicKeyFromWire, nodeIdFromPublicKey, formatNodeId,
  encodeUnsigned, signingDigest, signingInput, sign, verify, encode, decode, buildPacket,
  encodeAnnounce, decodeAnnounce, eciesEncrypt, eciesDecrypt, deriveKey,
  encodeSos, decodeSos, RateLimiter,
  fragment, Reassembler,
};
