'use strict';
// Generates protocol/test-vectors.json from the reference implementation.
// Usage: node tools/protocol/gen-vectors.js
const fs = require('fs');
const path = require('path');
const R = require('./ripple');

const hex = (b) => Buffer.from(b).toString('hex');

// Fixed scalars so the vectors are reproducible.
const alice = R.identityFromPrivateScalar('1111111111111111111111111111111111111111111111111111111111111111');
const bob = R.identityFromPrivateScalar('2222222222222222222222222222222222222222222222222222222222222222');
const EPH = '3333333333333333333333333333333333333333333333333333333333333333';

const vectors = { spec: 'ripple-mesh-v1', notes: 'ECDSA signatures are randomized; verify them rather than comparing bytes.' };

vectors.identities = {
  alice: { privateScalar: '11'.repeat(32), publicKeyWire: hex(alice.publicKeyWire), nodeId: hex(alice.nodeId), display: R.formatNodeId(alice.nodeId) },
  bob: { privateScalar: '22'.repeat(32), publicKeyWire: hex(bob.publicKeyWire), nodeId: hex(bob.nodeId), display: R.formatNodeId(bob.nodeId) },
};

// --- announce
const annPayload = R.encodeAnnounce(alice, 'Alice 📱');
const ann = R.buildPacket(alice, {
  type: R.PacketType.ANNOUNCE, ttl: 7,
  messageId: Buffer.from('000102030405060708090a0b0c0d0e0f', 'hex'),
  timestamp: 1_757_000_000_000n, payload: annPayload,
});
vectors.announce = {
  name: 'Alice 📱',
  payload: hex(annPayload),
  unsignedBytes: hex(R.encodeUnsigned(ann)),
  signingDigest: hex(R.signingDigest(R.encodeUnsigned(ann))),
  fullPacket: hex(R.encode(ann)),
  decoded: { type: 1, flags: 0, ttl: 7, messageId: hex(ann.messageId), source: hex(ann.source), destination: hex(ann.destination), timestamp: ann.timestamp.toString(), payloadLength: annPayload.length },
};

// --- broadcast message
const bcastPayload = Buffer.from('Hello, mesh! नमस्ते', 'utf8');
const bcast = R.buildPacket(alice, {
  type: R.PacketType.MESSAGE, ttl: 5,
  messageId: Buffer.from('a0a1a2a3a4a5a6a7a8a9aaabacadaeaf', 'hex'),
  timestamp: 1_757_000_001_000n, payload: bcastPayload,
});
vectors.broadcastMessage = {
  text: 'Hello, mesh! नमस्ते',
  unsignedBytes: hex(R.encodeUnsigned(bcast)),
  signingDigest: hex(R.signingDigest(R.encodeUnsigned(bcast))),
  fullPacket: hex(R.encode(bcast)),
  // Relays decrement ttl; digest must be identical.
  signingDigestAfterTtlDecrement: hex(R.signingDigest(R.encodeUnsigned({ ...bcast, ttl: 4 }))),
};

// --- direct encrypted message alice -> bob (deterministic ephemeral + nonce)
const msgId = Buffer.from('b0b1b2b3b4b5b6b7b8b9babbbcbdbebf', 'hex');
const nonce = Buffer.from('c0c1c2c3c4c5c6c7c8c9cacb', 'hex');
const plaintext = Buffer.from('secret: meet at the north gate at 18:30', 'utf8');
const box = R.eciesEncrypt({ recipientWire: bob.publicKeyWire, messageId: msgId, source: alice.nodeId, destination: bob.nodeId, plaintext, ephemeralScalarHex: EPH, nonce });
const roundTrip = R.eciesDecrypt({ recipientIdentity: bob, messageId: msgId, source: alice.nodeId, destination: bob.nodeId, payload: box });
if (!roundTrip.equals(plaintext)) throw new Error('ECIES self-check failed');
const direct = R.buildPacket(alice, {
  type: R.PacketType.MESSAGE, flags: R.Flags.ENCRYPTED, ttl: 7, messageId: msgId, destination: bob.nodeId,
  timestamp: 1_757_000_002_000n, payload: box,
});
// Expose the intermediate values so ports can pinpoint where they diverge.
const crypto = require('crypto');
const eph = crypto.createECDH('prime256v1'); eph.setPrivateKey(Buffer.from(EPH, 'hex'));
const sharedX = eph.computeSecret(bob.publicKeyWire);
vectors.directMessage = {
  plaintext: plaintext.toString('utf8'),
  ephemeralScalar: EPH,
  ephemeralPublicKeyWire: hex(eph.getPublicKey()),
  ecdhSharedX: hex(sharedX),
  hkdfKey: hex(R.deriveKey(sharedX, msgId)),
  nonce: hex(nonce),
  aad: hex(Buffer.concat([msgId, alice.nodeId, bob.nodeId])),
  eciesPayload: hex(box),
  unsignedBytes: hex(R.encodeUnsigned(direct)),
  fullPacket: hex(R.encode(direct)),
};

// --- ack bob -> alice
const ack = R.buildPacket(bob, {
  type: R.PacketType.ACK, ttl: 7, messageId: Buffer.from('d0d1d2d3d4d5d6d7d8d9dadbdcdddedf', 'hex'),
  destination: alice.nodeId, timestamp: 1_757_000_003_000n, payload: msgId,
});
vectors.ack = { unsignedBytes: hex(R.encodeUnsigned(ack)), fullPacket: hex(R.encode(ack)) };

// --- fragmentation
const big = Buffer.alloc(1000); for (let i = 0; i < big.length; i++) big[i] = i & 0xff;
vectors.fragmentation = {
  frameSize: 100, streamId: 0xbeef, input: hex(big),
  frames: R.fragment(big, 100, 0xbeef).map(hex),
  small: { frameSize: 20, streamId: 1, input: '0102', frames: R.fragment(Buffer.from('0102', 'hex'), 20, 1).map(hex) },
};

// --- negative cases
vectors.invalid = {
  wrongVersion: hex(Buffer.concat([Buffer.from([2]), R.encode(ann).subarray(1)])),
  truncated: hex(R.encode(ann).subarray(0, 60)),
  tamperedPayload: (() => { const b = Buffer.from(R.encode(bcast)); b[R.HEADER_SIZE] ^= 0x01; return hex(b); })(),
};

const out = path.join(__dirname, '..', '..', 'protocol', 'test-vectors.json');
fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, JSON.stringify(vectors, null, 2) + '\n');
console.log('wrote', path.relative(process.cwd(), out));
