# Ripple Mesh Protocol — v1

Ripple is an internet-free messenger. Every phone is simultaneously a BLE
**peripheral** (advertising + GATT server) and a BLE **central** (scanning +
GATT client), so any two phones in radio range form a *link*. Messages are
flooded hop-by-hop across links with a TTL and a seen-cache, and are held in a
store-and-forward queue so they can cross gaps in the mesh when a new peer
appears later (epidemic / delay-tolerant routing).

This document is the single source of truth for the byte-level format. The
Android (Kotlin) and iOS (Swift) implementations, plus the Node reference
implementation in `tools/protocol/`, all encode and decode exactly this.
Shared test vectors live in `protocol/test-vectors.json`.

All multi-byte integers are **big-endian**.

---

## 1. Identity

| Item              | Definition                                                                  |
|-------------------|-----------------------------------------------------------------------------|
| Identity key      | NIST P-256 (secp256r1) key pair, generated once per install, kept in the OS keystore/keychain. |
| Public key wire form | 65 bytes, uncompressed X9.63: `0x04 ‖ X(32) ‖ Y(32)`.                   |
| Node ID           | First **8 bytes** of `SHA-256(publicKeyWireForm)`.                          |
| Broadcast Node ID | `00 00 00 00 00 00 00 00`                                                   |
| Display form      | Node ID as 16 lowercase hex chars, grouped `xxxx-xxxx-xxxx-xxxx`.           |

The same P-256 key is used for ECDSA signing and ECDH key agreement (ECIES).
This is a deliberate simplicity trade-off; see `docs/ARCHITECTURE.md § Security`.

---

## 2. Packet

```
offset  size  field
     0     1  version        = 0x01
     1     1  type           1=ANNOUNCE  2=MESSAGE  3=ACK  4=SOS
     2     1  flags          bit0 ENCRYPTED (payload is an ECIES box)
     3     1  ttl            remaining hops, 0..MAX_TTL(7). Mutable in flight.
     4    16  messageId      128 random bits, globally unique
    20     8  source         node id of the originator
    28     8  destination    node id, or Broadcast Node ID
    36     8  timestamp      originator's unix time in milliseconds
    44     2  payloadLength  N, 0..4096
    46     N  payload
  46+N    64  signature      ECDSA P-256, raw r‖s (each 32 bytes, zero padded)
```

* `HEADER_SIZE = 46`, `SIGNATURE_SIZE = 64`, `MAX_PAYLOAD = 4096`,
  `MAX_PACKET = 46 + 4096 + 64 = 4206`.
* **Signature**: standard ECDSA-with-SHA-256 (Android `SHA256withECDSA`,
  CryptoKit `P256.Signing`, Node `crypto.sign('sha256', …)`) over bytes
  `[0, 46+N)` with the `ttl` byte **replaced by 0x00**. This lets relays
  decrement `ttl` without invalidating the signature. Everything else is
  immutable in flight. Signatures are randomized, so implementations are
  compared by *verifying* the vectors, not by byte equality.
* The signature is made with the **source's** identity key. Receivers verify with
  the public key from the peer table (learned via ANNOUNCE); ANNOUNCE packets
  carry their own key and are self-verifying.

### 2.1 Payloads

**ANNOUNCE (type 1)** — unencrypted, broadcast, `ttl` ≤ MAX_TTL.
```
0    65  publicKey (wire form; SHA-256 prefix must equal `source`)
65    1  nameLength L (0..64)
66    L  displayName, UTF-8
```

**MESSAGE (type 2)**
* Broadcast (destination = Broadcast ID, ENCRYPTED clear): payload is UTF-8 text.
* Direct (destination = a node id, ENCRYPTED set): payload is an ECIES box, §3.

**ACK (type 3)** — sent by the final recipient of a *direct* MESSAGE back to its
source, flooded like any other packet.
```
0   16  acknowledgedMessageId
```

**SOS (type 4)** — an emergency beacon, broadcast mesh-wide (destination = Broadcast
ID, unencrypted, signed). Unlike ordinary traffic it is always relayed by every node
(including power-saver relays) and is held in the relay store for the full 72 h
store-and-forward window. Location is **only** present when the operator opted in to
sharing GPS — nothing leaves the device otherwise.
```
0    1  flags         bit0 (0x01) HAS_LOCATION
1    1  textLength L  (0..128) UTF-8 message length
2    L  text          optional help/context text (may be empty)
2+L  4  latE7         signed int32, degrees × 10^7      (present iff HAS_LOCATION)
6+L  4  lngE7         signed int32, degrees × 10^7
10+L 2  accuracyMeters uint16 (best-effort, 0 = unknown)
```
Decoders must accept both forms and must not reject a beacon because it has no
location. `SOS_FLAG_HAS_LOCATION = 0x01`, `MAX_SOS_TEXT = 128`.

---

## 3. Direct-message encryption (ECIES)

Given recipient static public key `R`, sender generates an ephemeral P-256 key
pair `(e, E)` per message.

```
shared   = ECDH(e, R).x                      // 32-byte x-coordinate
key      = HKDF-SHA256(ikm = shared,
                       salt = messageId (16 bytes),
                       info = "ripple/v1/msg",
                       L = 32)
nonce    = 12 random bytes
aad      = messageId(16) ‖ source(8) ‖ destination(8)      // 32 bytes
ct‖tag   = AES-256-GCM(key, nonce, plaintext, aad)          // tag = 16 bytes
payload  = E (65, wire form) ‖ nonce (12) ‖ ct ‖ tag
```

The recipient recomputes `shared = ECDH(r, E).x` with its static private key.
Receivers **must** verify the outer ECDSA signature before trusting sender
identity; GCM alone authenticates only that the sender knew `R`.

---

## 4. Routing

Every node keeps:

* `selfId`, identity key.
* **Peer table**: `nodeId → (publicKey, name, lastSeen, lastHopCount)`.
* **Seen cache**: `messageId → firstSeenAt`, bounded (default 5 000 entries / 24 h).
* **Relay store**: recently forwarded/originated packets `messageId → (bytes,
  expiresAt, deliveredToPeers)`, bounded (default 500 entries / **72 h**).
* **Links**: currently connected BLE links, each tagged with the peer's `nodeId`
  once it has been learned.

Algorithm on receiving `packet` from `link`:

1. Decode; drop if malformed, version ≠ 1, payload > 4096, or timestamp
   more than 24 h in the future.
2. If `messageId` ∈ seen cache → drop. Else add.
3. If type = ANNOUNCE → verify self-signature, update peer table with
   `hops = MAX_TTL − ttl + 1`, tag `link.peerNodeId` if this is the first
   packet on the link.
4. If `destination ∈ {selfId, Broadcast}` → verify signature against peer table
   (unknown source ⇒ mark *unverified*; direct messages from unknown sources are
   dropped), decrypt if needed, deliver to the app, and if it was a direct
   MESSAGE emit an ACK to the source.
5. If `destination ≠ selfId` **or** destination is Broadcast, and `ttl > 1`:
   decrement `ttl`, store in relay store, forward on **all links except `link`**.

On a **new link** becoming ready:

1. Send our ANNOUNCE (ttl = MAX_TTL).
2. Once the peer's ANNOUNCE arrives (so we know its `nodeId`), replay every
   relay-store packet not yet delivered to that peer whose destination is the
   peer, Broadcast, or unknown to us — this is the store-and-forward step.
3. If we already hold a live link to the same `nodeId`, the node with the
   **lexicographically larger** `nodeId` closes the *newer* duplicate.

Originating: build packet with `ttl = MAX_TTL`, sign, add to seen cache and
relay store, send on all links.

### 4.1 SOS beacons

SOS (type 4) packets are treated as broadcast traffic with two exceptions:

* they are **always relayed**, even by power-saver relays (see §7);
* any received beacon is surfaced to the app (and to the operator's responder
  notification) at every hop, exactly once per node via the seen cache.

### 4.2 Store-and-forward retention

The relay store keeps packets for **72 h** (`RELAY_TTL`), so a beacon or message
can cross a gap in the mesh that opens and closes days later. Relayed packets are
replayed to a peer when it (re)connects (step 2 of the new-link procedure above).

---

## 5. BLE transport

### 5.1 GATT

| Item                | UUID                                     | Properties                 |
|---------------------|------------------------------------------|----------------------------|
| Mesh service        | `7E2C4B10-4B7D-4E3A-9C1F-8A2E5D6F1A01`   | primary, advertised        |
| RX characteristic   | `7E2C4B10-4B7D-4E3A-9C1F-8A2E5D6F1A02`   | write, write-no-response   |
| TX characteristic   | `7E2C4B10-4B7D-4E3A-9C1F-8A2E5D6F1A03`   | notify (+ CCCD `0x2902`)   |

Central → peripheral traffic is written to **RX**; peripheral → central traffic
is delivered as notifications on **TX**. The link is *ready* when the central
has subscribed to TX. The central requests the largest MTU it can (517 on
Android; iOS negotiates automatically) and both sides use
`min(mtu − 3, 512)` as the frame size.

### 5.2 Framing / fragmentation

A packet larger than one frame is split into fragments:

```
0   2  streamId    random per (packet, link)
2   1  index       0-based fragment index
3   1  total       number of fragments (1..255)
4   …  chunk
```

`chunkSize = frameSize − 4`. The receiver reassembles by `streamId`; incomplete
streams are discarded after 10 s. A single-fragment packet still carries the
4-byte header with `index = 0, total = 1`.

---

## 6. Constants

| Name              | Value                |
|-------------------|----------------------|
| `VERSION`         | 1                    |
| `MAX_TTL`         | 7                    |
| `MAX_PAYLOAD`     | 4096                 |
| `MAX_NAME_BYTES`  | 64                   |
| `HKDF_INFO`       | `"ripple/v1/msg"`    |
| `SEEN_TTL`        | 24 h                 |
| `RELAY_TTL`       | 72 h                 |   (store-and-forward window)
| `REASSEMBLY_TTL`  | 10 s                 |
| `MAX_SOS_TEXT`    | 128 bytes            |
| `SOS_FLAG_HAS_LOCATION` | 0x01         |

---

## 7. Rate limiting & battery profiles

### 7.1 Per-source flood cap

To stop a single node from flooding the mesh, a receiver may enforce a sliding-window
budget per source on **broadcast** MESSAGE and SOS traffic (direct, end-to-end
messages are never gated). When the budget is spent the receiver drops the packet —
it is neither delivered nor relayed. Configurable; disabled by default so the
behaviour in §4 is unchanged unless an operator opts in.

### 7.2 Battery profiles

Each node runs one of three battery profiles, which controls how aggressively it
forwards other nodes' traffic:

| Profile        | code | Relays ordinary chat | Relays SOS/ANNOUNCE |
|----------------|------|----------------------|---------------------|
| `PERFORMANCE`  | 0    | yes                  | yes                 |
| `BALANCED`     | 1    | yes                  | yes                 |
| `POWER_SAVER`  | 2    | no                   | yes                 |

A power-saver node is a *leaf* for chat (it still receives and delivers traffic
addressed to it) but continues to propagate liveness (ANNOUNCE) and safety (SOS)
traffic. This is a local policy; it changes no bytes on the wire. On the BLE
transport the profile can also widen the advertise/scan duty cycle to save battery.
The profile is orthogonal to the 72 h retention guarantee: a node still relays what
it has already received for up to 72 h, it simply chooses not to receive-and-forward
new ordinary chat while in power-saver.
