'use strict';
// Self-test of the reference implementation + a simulated 4-node mesh.
// Usage: node tools/protocol/test.js
const assert = require('assert');
const R = require('./ripple');
const { MeshNode } = require('./mesh-sim');

let passed = 0;
function test(name, fn) { fn(); passed++; console.log('  ✓', name); }

console.log('protocol');
test('node id = sha256(pubkey)[0:8]', () => {
  const id = R.generateIdentity();
  assert.strictEqual(id.nodeId.length, 8);
  assert.deepStrictEqual(id.nodeId, R.nodeIdFromPublicKey(id.publicKeyWire));
});

test('encode/decode round trip + signature verifies', () => {
  const a = R.generateIdentity();
  const p = R.buildPacket(a, { type: R.PacketType.MESSAGE, payload: Buffer.from('hi') });
  const d = R.decode(R.encode(p));
  assert.strictEqual(d.ttl, 7);
  assert.deepStrictEqual(d.payload, Buffer.from('hi'));
  assert.ok(R.verify(a.publicKey, R.encodeUnsigned(d), d.signature));
});

test('ttl decrement keeps signature valid; payload tamper breaks it', () => {
  const a = R.generateIdentity();
  const p = R.buildPacket(a, { type: R.PacketType.MESSAGE, payload: Buffer.from('hi') });
  const relayed = { ...p, ttl: 3 };
  assert.ok(R.verify(a.publicKey, R.encodeUnsigned(relayed), p.signature));
  const bad = { ...p, payload: Buffer.from('ho') };
  assert.ok(!R.verify(a.publicKey, R.encodeUnsigned(bad), p.signature));
});

test('announce payload round trip (unicode name)', () => {
  const a = R.generateIdentity();
  const d = R.decodeAnnounce(R.encodeAnnounce(a, 'Zoë 🚀'));
  assert.strictEqual(d.name, 'Zoë 🚀');
  assert.deepStrictEqual(d.publicKeyWire, a.publicKeyWire);
});

test('ECIES round trip and AAD binding', () => {
  const a = R.generateIdentity(), b = R.generateIdentity();
  const messageId = Buffer.alloc(16, 7);
  const box = R.eciesEncrypt({ recipientWire: b.publicKeyWire, messageId, source: a.nodeId, destination: b.nodeId, plaintext: Buffer.from('x') });
  assert.strictEqual(R.eciesDecrypt({ recipientIdentity: b, messageId, source: a.nodeId, destination: b.nodeId, payload: box }).toString(), 'x');
  assert.throws(() => R.eciesDecrypt({ recipientIdentity: b, messageId, source: b.nodeId, destination: b.nodeId, payload: box }));
  assert.throws(() => R.eciesDecrypt({ recipientIdentity: a, messageId, source: a.nodeId, destination: b.nodeId, payload: box }));
});

test('fragment/reassemble in and out of order', () => {
  const data = Buffer.from(Array.from({ length: 777 }, (_, i) => i % 251));
  const frames = R.fragment(data, 100, 42);
  assert.strictEqual(frames.length, 9);
  const r = new R.Reassembler();
  const shuffled = [...frames].reverse();
  let out = null;
  for (const f of shuffled) { const res = r.push(f); if (res) out = res; }
  assert.deepStrictEqual(out, data);
});

test('decode rejects bad version / truncated', () => {
  const a = R.generateIdentity();
  const bytes = R.encode(R.buildPacket(a, { type: R.PacketType.MESSAGE, payload: Buffer.from('hi') }));
  assert.throws(() => R.decode(Buffer.concat([Buffer.from([9]), bytes.subarray(1)])));
  assert.throws(() => R.decode(bytes.subarray(0, 50)));
});

console.log('mesh simulation');
test('4-node line: broadcast reaches everyone, direct message is E2E + acked', () => {
  // A - B - C - D  (each only in range of its neighbours)
  const nodes = ['A', 'B', 'C', 'D'].map((n) => new MeshNode(n));
  const [A, B, C, D] = nodes;
  MeshNode.link(A, B); MeshNode.link(B, C); MeshNode.link(C, D);
  MeshNode.settle();

  // Everyone learned everyone via flooded announces.
  for (const n of nodes) assert.strictEqual(n.router.peers.size, 3, `${n.name} peers`);

  A.sendBroadcast('hello all');
  MeshNode.settle();
  for (const n of [B, C, D]) assert.strictEqual(n.inbox.at(-1).text, 'hello all');

  const id = A.sendDirect(D.router.selfId, 'only for D');
  MeshNode.settle();
  assert.strictEqual(D.inbox.at(-1).text, 'only for D');
  // Relays never saw plaintext (they only got the ECIES box).
  assert.ok(!B.inbox.some((m) => m.text === 'only for D'));
  assert.ok(!C.inbox.some((m) => m.text === 'only for D'));
  // A got the ACK from D.
  assert.ok(A.acks.some((a) => a.equals(id)));
  // Hop count: D sees A at 3 hops.
  assert.strictEqual(D.router.peers.get(A.router.selfId.toString('hex')).hops, 3);
});

test('store-and-forward: message queued while offline is delivered when link appears', () => {
  const A = new MeshNode('A'), B = new MeshNode('B'), C = new MeshNode('C');
  MeshNode.link(A, B); MeshNode.link(B, C); MeshNode.settle();
  MeshNode.unlink(B, C);            // C walks out of range
  A.sendDirect(C.router.selfId, 'catch up later');
  MeshNode.settle();
  assert.strictEqual(C.inbox.length, 0);
  MeshNode.link(B, C);              // C comes back
  MeshNode.settle();
  assert.strictEqual(C.inbox.at(-1).text, 'catch up later');
});

test('loops do not amplify: triangle delivers exactly once', () => {
  const A = new MeshNode('A'), B = new MeshNode('B'), C = new MeshNode('C');
  MeshNode.link(A, B); MeshNode.link(B, C); MeshNode.link(A, C); MeshNode.settle();
  A.sendBroadcast('once');
  MeshNode.settle();
  assert.strictEqual(B.inbox.filter((m) => m.text === 'once').length, 1);
  assert.strictEqual(C.inbox.filter((m) => m.text === 'once').length, 1);
});

test('ttl bound: 9-node line, node 9 is out of reach with MAX_TTL=7', () => {
  const nodes = Array.from({ length: 9 }, (_, i) => new MeshNode(String(i)));
  for (let i = 0; i < 8; i++) MeshNode.link(nodes[i], nodes[i + 1]);
  MeshNode.settle();
  nodes[0].sendBroadcast('far');
  MeshNode.settle();
  assert.strictEqual(nodes[7].inbox.at(-1)?.text, 'far');   // 7 hops: ttl 7→1, delivered
  assert.strictEqual(nodes[8].inbox.length, 0);              // would need an 8th hop
});

console.log('sos & policy');
test('SOS payload round trips (opt-in GPS) and omits location when not shared', () => {
  const loc = { latE7: 285430001, lngE7: 7709002, accuracyMeters: 15 };
  const p1 = R.decodeSos(R.encodeSos({ text: 'need help 📍', location: loc }));
  assert.strictEqual(p1.text, 'need help 📍');
  assert.deepStrictEqual(p1.location, loc);
  const p2 = R.decodeSos(R.encodeSos({ text: 'can you hear me?' }));
  assert.strictEqual(p2.location, null);
  assert.strictEqual(p2.text, 'can you hear me?');
  assert.strictEqual(R.encodeSos({}).length, 2); // bare beacon = 2-byte header only
});

test('rate limiter caps bursts within a window and refills after it', () => {
  const lim = new R.RateLimiter(3, 1000);
  const t0 = 1_000_000;
  assert.ok([0, 1, 2].every(() => lim.allow(t0)));
  assert.ok(!lim.allow(t0));          // budget spent
  assert.ok(lim.allow(t0 + 1001));    // window slid forward, a slot freed
});

test('SOS beacon floods the whole mesh and carries the opted-in GPS fix', () => {
  const nodes = ['A', 'B', 'C', 'D'].map((n) => new MeshNode(n));
  const [A, B, C, D] = nodes;
  MeshNode.link(A, B); MeshNode.link(B, C); MeshNode.link(C, D);
  MeshNode.settle();
  const loc = { latE7: 1234567, lngE7: -7654321, accuracyMeters: 20 };
  A.sendSos('trapped in valley', loc);
  MeshNode.settle();
  for (const n of [B, C, D]) {
    assert.strictEqual(n.sosInbox.length, 1, `${n.name} sos count`);
    assert.strictEqual(n.sosInbox.at(-1).text, 'trapped in valley');
    assert.deepStrictEqual(n.sosInbox.at(-1).location, loc);
  }
  // Beacons are not plain chat messages.
  assert.ok(!B.inbox.some((m) => m.text === 'trapped in valley'));
});

test('power-saver relays SOS but stops relaying ordinary broadcasts', () => {
  const A = new MeshNode('A'), B = new MeshNode('B'), C = new MeshNode('C');
  MeshNode.link(A, B); MeshNode.link(B, C); MeshNode.settle();
  B.router.setBatteryProfile(R.BatteryProfile.POWER_SAVER);
  A.sendBroadcast('anyone around?');      // ordinary chat must NOT be forwarded
  MeshNode.settle();
  assert.strictEqual(C.inbox.length, 0);
  A.sendSos('in trouble');                // …but the SOS beacon still floods
  MeshNode.settle();
  assert.strictEqual(C.sosInbox.length, 1);
});

test('72 h store-and-forward retention and battery relay policy', () => {
  assert.strictEqual(R.RELAY_RETENTION_MS, 72 * 3600 * 1000);
  assert.ok(R.relaysOrdinary(R.BatteryProfile.BALANCED));
  assert.ok(R.relaysOrdinary(R.BatteryProfile.PERFORMANCE));
  assert.ok(!R.relaysOrdinary(R.BatteryProfile.POWER_SAVER));
  const n = new MeshNode('x');
  assert.strictEqual(n.router.relayRetentionMs, R.RELAY_RETENTION_MS);
});

console.log(`\n${passed} tests passed`);
