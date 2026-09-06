'use strict';
/**
 * In-memory simulation of the routing algorithm from PROTOCOL.md §4.
 * MeshRouter is transport-agnostic; MeshNode wires routers together with fake
 * links whose delivery is queued so we can step the network deterministically.
 */
const crypto = require('crypto');
const R = require('./ripple');

class MeshRouter {
  /**
   * @param {object} identity      from R.generateIdentity()
   * @param {string} displayName
   * @param {object} delegate      { deliver(msg), ack(messageId), send(link, bytes), links(): Link[] }
   */
  constructor(identity, displayName, delegate) {
    this.identity = identity;
    this.selfId = identity.nodeId;
    this.displayName = displayName;
    this.delegate = delegate;
    this.peers = new Map();       // hex(nodeId) -> { publicKey, publicKeyWire, name, lastSeen, hops }
    this.seen = new Map();        // hex(messageId) -> firstSeenAt
    this.relayStore = new Map();  // hex(messageId) -> { packet, expiresAt, deliveredTo:Set<hexNodeId> }
    this.maxSeen = 5000; this.maxRelay = 500; this.ttlMs = 24 * 3600 * 1000;
  }

  // ---- outbound -----------------------------------------------------------

  announcePacket() {
    return R.buildPacket(this.identity, { type: R.PacketType.ANNOUNCE, payload: R.encodeAnnounce(this.identity, this.displayName) });
  }

  originate(packet) {
    this._markSeen(packet);
    this._store(packet, null);
    this._broadcast(packet, null);
    return packet.messageId;
  }

  sendBroadcastText(text) {
    return this.originate(R.buildPacket(this.identity, { type: R.PacketType.MESSAGE, payload: Buffer.from(text, 'utf8') }));
  }

  sendDirectText(destination, text) {
    const peer = this.peers.get(destination.toString('hex'));
    if (!peer) throw new Error('unknown peer; no public key');
    const messageId = crypto.randomBytes(16);
    const payload = R.eciesEncrypt({ recipientWire: peer.publicKeyWire, messageId, source: this.selfId, destination, plaintext: Buffer.from(text, 'utf8') });
    return this.originate(R.buildPacket(this.identity, { type: R.PacketType.MESSAGE, flags: R.Flags.ENCRYPTED, messageId, destination, payload }));
  }

  // ---- link lifecycle -----------------------------------------------------

  onLinkReady(link) {
    this.delegate.send(link, R.encode(this.announcePacket()));
  }

  /** Called once we know which node is on the other end of a link. */
  _onLinkIdentified(link, peerHex) {
    // Duplicate-link suppression: keep the older link, larger id closes the newer.
    for (const other of this.delegate.links()) {
      if (other !== link && other.peerHex === peerHex) {
        if (Buffer.compare(this.selfId, Buffer.from(peerHex, 'hex')) > 0) { link.close(); return; }
      }
    }
    // Store-and-forward replay.
    const peerId = Buffer.from(peerHex, 'hex');
    for (const entry of this.relayStore.values()) {
      if (entry.deliveredTo.has(peerHex)) continue;
      const dest = entry.packet.destination;
      const isForPeer = dest.equals(peerId), isBroadcast = dest.equals(R.BROADCAST_ID);
      const unknownDest = !isBroadcast && !this.peers.has(dest.toString('hex'));
      if (isForPeer || isBroadcast || unknownDest) {
        entry.deliveredTo.add(peerHex);
        this.delegate.send(link, R.encode(entry.packet));
      }
    }
  }

  // ---- inbound ------------------------------------------------------------

  onReceive(link, bytes, now = Date.now()) {
    let p; try { p = R.decode(bytes); } catch { return; }
    if (Number(p.timestamp) > now + this.ttlMs) return;
    const idHex = p.messageId.toString('hex');
    if (this.seen.has(idHex)) return;
    this._markSeen(p, now);

    if (p.type === R.PacketType.ANNOUNCE) {
      let ann; try { ann = R.decodeAnnounce(p.payload); } catch { return; }
      if (!R.nodeIdFromPublicKey(ann.publicKeyWire).equals(p.source)) return;
      const publicKey = R.publicKeyFromWire(ann.publicKeyWire);
      if (!R.verify(publicKey, R.encodeUnsigned(p), p.signature)) return;
      const srcHex = p.source.toString('hex');
      const hops = R.MAX_TTL - p.ttl + 1;
      const prev = this.peers.get(srcHex);
      this.peers.set(srcHex, { publicKey, publicKeyWire: ann.publicKeyWire, name: ann.name, lastSeen: now, hops: prev ? Math.min(prev.hops, hops) : hops });
      if (!link.peerHex && hops === 1) { link.peerHex = srcHex; this._onLinkIdentified(link, srcHex); }
      this._relay(p, link);
      return;
    }

    const forMe = p.destination.equals(this.selfId);
    const broadcast = p.destination.equals(R.BROADCAST_ID);
    if (forMe || broadcast) {
      const peer = this.peers.get(p.source.toString('hex'));
      const verified = !!peer && R.verify(peer.publicKey, R.encodeUnsigned(p), p.signature);
      if (peer && !verified) return;            // known peer, bad signature: forged
      if (forMe && !peer) { this._relay(p, link); return; } // can't verify/decrypt yet
      if (p.type === R.PacketType.MESSAGE) {
        let text;
        if (p.flags & R.Flags.ENCRYPTED) {
          try { text = R.eciesDecrypt({ recipientIdentity: this.identity, messageId: p.messageId, source: p.source, destination: p.destination, payload: p.payload }).toString('utf8'); }
          catch { return; }
        } else text = p.payload.toString('utf8');
        this.delegate.deliver({ messageId: p.messageId, from: p.source, fromName: peer?.name, text, verified, broadcast, timestamp: p.timestamp });
        if (forMe) this.originate(R.buildPacket(this.identity, { type: R.PacketType.ACK, destination: p.source, payload: p.messageId }));
      } else if (p.type === R.PacketType.ACK && forMe && p.payload.length === 16) {
        this.delegate.ack(p.payload);
      }
    }
    if (!forMe) this._relay(p, link);
  }

  // ---- internals ----------------------------------------------------------

  _relay(p, fromLink) {
    if (p.ttl <= 1) return;
    const relayed = { ...p, ttl: p.ttl - 1 };
    this._store(relayed, fromLink);
    this._broadcast(relayed, fromLink);
  }

  _broadcast(p, exceptLink) {
    const bytes = R.encode(p);
    const idHex = p.messageId.toString('hex');
    for (const link of this.delegate.links()) {
      if (link === exceptLink) continue;
      if (link.peerHex) this.relayStore.get(idHex)?.deliveredTo.add(link.peerHex);
      this.delegate.send(link, bytes);
    }
  }

  _store(p, fromLink) {
    if (p.type === R.PacketType.ANNOUNCE) return; // announces are refreshed per link, never replayed
    const deliveredTo = new Set();
    if (fromLink?.peerHex) deliveredTo.add(fromLink.peerHex);
    this.relayStore.set(p.messageId.toString('hex'), { packet: p, expiresAt: Date.now() + this.ttlMs, deliveredTo });
    while (this.relayStore.size > this.maxRelay) this.relayStore.delete(this.relayStore.keys().next().value);
  }

  _markSeen(p, now = Date.now()) {
    this.seen.set(p.messageId.toString('hex'), now);
    while (this.seen.size > this.maxSeen) this.seen.delete(this.seen.keys().next().value);
  }
}

// ---------------------------------------------------------------------------
// Fake network

class FakeLink {
  constructor(owner, remoteNode) { this.owner = owner; this.remote = remoteNode; this.peerHex = null; this.open = true; }
  close() { MeshNode.unlink(this.owner, this.remote); }
}

class MeshNode {
  static queue = [];
  constructor(name) {
    this.name = name; this.inbox = []; this.acks = []; this._links = [];
    this.router = new MeshRouter(R.generateIdentity(), name, {
      deliver: (m) => this.inbox.push(m),
      ack: (id) => this.acks.push(id),
      send: (link, bytes) => { if (link.open) MeshNode.queue.push({ link, bytes }); },
      links: () => this._links,
    });
  }
  sendBroadcast(t) { return this.router.sendBroadcastText(t); }
  sendDirect(dest, t) { return this.router.sendDirectText(dest, t); }

  static link(a, b) {
    const la = new FakeLink(a, b), lb = new FakeLink(b, a);
    la.twin = lb; lb.twin = la;
    a._links.push(la); b._links.push(lb);
    a.router.onLinkReady(la); b.router.onLinkReady(lb);
  }
  static unlink(a, b) {
    for (const n of [a, b]) for (const l of n._links.filter((l) => l.remote === (n === a ? b : a))) { l.open = false; n._links.splice(n._links.indexOf(l), 1); }
  }
  /** Deliver queued frames until the network is quiet. */
  static settle() {
    let guard = 100000;
    while (MeshNode.queue.length && guard--) {
      const { link, bytes } = MeshNode.queue.shift();
      if (!link.open || !link.twin.open) continue;
      link.remote.router.onReceive(link.twin, bytes);
    }
    if (guard <= 0) throw new Error('network did not settle');
  }
}

module.exports = { MeshRouter, MeshNode };
