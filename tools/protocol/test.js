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

console.log(`\n${passed} tests passed`);
