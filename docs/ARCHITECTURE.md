# Ripple — Architecture

Ripple is an internet-free messenger for Android and iOS. Phones form an ad-hoc
Bluetooth LE mesh; messages flood hop-by-hop with a TTL, are held for peers that
are temporarily out of range, and direct messages are end-to-end encrypted.

```
┌──────────────────────────────────────────────────────────────────────┐
│ UI (Jetpack Compose / SwiftUI)                                       │
│   Home (chats + peers) · Chat · Settings                             │
├──────────────────────────────────────────────────────────────────────┤
│ MeshService  (Android foreground service / iOS @MainActor object)    │
│   owns router + BLE roles, persists to Room / SwiftData, notifies    │
├────────────────────────┬─────────────────────────────────────────────┤
│ core/  MeshRouter      │ ble/  BleCentral   BlePeripheral            │
│        Packet, Crypto  │       (scan+connect) (advertise+GATT server)│
│        Fragmenter      │       BleLink = fragment/reassemble + queue │
└────────────────────────┴─────────────────────────────────────────────┘
```

The `core/` layer is **pure** — no Android/iOS APIs — and is written twice
(Kotlin, Swift) from the same spec. A third, Node implementation in
`tools/protocol/` is the reference that generates `protocol/test-vectors.json`;
both mobile ports have unit tests that must reproduce those vectors byte-for-byte,
so the platforms cannot drift apart.

## Repository layout

| Path | What |
|------|------|
| `PROTOCOL.md` | Wire format, crypto, routing rules, BLE GATT layout. Source of truth. |
| `protocol/test-vectors.json` | Generated conformance vectors (do not hand-edit). |
| `tools/protocol/` | Node reference implementation + mesh simulator + vector generator. |
| `tools/conformance/` | Runs the Kotlin core tests with a bare `kotlinc` (no Gradle/SDK needed). |
| `android/` | Gradle project, Kotlin, Compose, Room, Android Keystore. minSdk 26. |
| `ios/` | XcodeGen spec (`project.yml`), Swift, SwiftUI, SwiftData, CryptoKit, Keychain. iOS 17+. |

## Data flow

**Sending a direct message**

1. UI → `MeshService.send(conversation, text)`.
2. `MeshRouter.sendDirect` looks up the recipient's public key in the peer table,
   builds an ECIES box (`Crypto.encrypt`), wraps it in a signed `Packet`, adds it to
   the seen-cache and relay store, and calls `send` on every live `Link`.
3. Each `BleLink` fragments the packet into MTU-sized frames and writes them to
   the radio through a single worker (Android thread / iOS serial queue with flow
   control from `didWriteValueFor` / `peripheralManagerIsReady`).
4. The message is stored locally with status `SENT` (or `PENDING` if there are
   no links yet). When an `ACK` comes back from the destination it flips to `DELIVERED`.

**Receiving**

1. Radio frame → `BleLink.onFrame` → `Reassembler` → full packet → `MeshRouter.onReceive`.
2. Router drops duplicates (seen cache), verifies the signature against the
   peer's known key, decrypts if it is for us, delivers to `MeshService`, then
   relays with `ttl - 1` to every other link.
3. `MeshService` persists it, posts a notification if the conversation is not on
   screen, and the UI updates via Room `Flow` / SwiftData `@Query`.

## Mesh behaviour

* **Flooding + seen cache** gives loop freedom without routing tables.
* **TTL = 7** bounds the flood radius.
* **Store-and-forward**: every non-announce packet is kept for 24 h. When a new
  neighbour is identified, anything it has not yet been given (addressed to it,
  broadcast, or to a destination we do not know) is replayed. Persisted to Room /
  SwiftData so it survives process death.
* **Dual role**: every phone is both a central and a peripheral, so any two phones
  in range link up regardless of which one discovered the other. If both sides
  connect to each other, the node with the larger id closes the newer link.
* **Hop count** for the peer list comes from `MAX_TTL − ttl + 1` on the ANNOUNCE.

## Security

* Identity is a P-256 key generated on first launch and never leaves the device
  *unasked*: at rest it is Keystore-wrapped (Android: the private scalar is stored
  encrypted under a non-exportable Keystore AES key; legacy pre-0.3 installs keep
  the hardware-held key and cannot export) or Keychain-held
  (`AfterFirstUnlockThisDeviceOnly` on iOS). The user can opt into an *encrypted*
  export for backup — passphrase → PBKDF2 → AES-GCM; see `docs/BACKUP.md`.
* Every packet is **ECDSA-signed** by its originator; relays cannot alter content
  (only `ttl` is outside the signature).
* Direct messages are **ECIES** (ephemeral ECDH → HKDF-SHA256 → AES-256-GCM) with
  the packet id, source and destination bound as AAD. Relays see only ciphertext.
* Broadcasts are signed but not encrypted (they're public by definition).
* Node IDs are truncated hashes of public keys. An 8-byte id is fine for a local
  mesh but is *not* collision-proof against a determined attacker; the Settings
  screen exposes the full id so users can compare it out-of-band.
* Known trade-offs (v1): the same key is used for signing and ECDH; no forward
  secrecy across messages beyond per-message ephemeral keys; no replay protection
  beyond the 24 h seen cache; metadata (who talks to whom) is visible to relays.

## Diagnostics and the simulated neighbourhood

`core/EventLog` is a bounded ring buffer that the BLE layers and router write to
(never message plaintext). The Diagnostics screen shows it live along with a
per-link table (role, peer, RSSI, frame size, bytes/packets in/out, age) and can
export everything as text via the platform share sheet — that export is what bug
reports should contain.

`core/Loopback` builds an in-process neighbourhood: two extra `MeshRouter`s ("Asha",
"Ravi") joined to the local router by latency-adding in-memory `Link`s in a line
`you — Asha — Ravi`. Because they are *real* routers, everything is exercised —
announces, hop counting, ECIES, ACKs, TTL decrement, and store-and-forward when Ravi
periodically "walks out of range". It runs alongside real BLE links, so a phone with
loopback enabled will also relay for simulated peers; that is intentional for demos
but the toggle should stay off in real deployments.

## Platform notes

**Android**
* `MeshService` is a `connectedDevice` foreground service — required to keep BLE
  scanning alive in the background on Android 8+.
* Uses `BLUETOOTH_SCAN` with `neverForLocation`, so no location permission is
  needed on Android 12+.
* MTU 517 is requested; frame size = `min(mtu − 3, 512)`.
* The app declares **no** `INTERNET` permission.

**iOS**
* `bluetooth-central` and `bluetooth-peripheral` background modes, with state
  restoration identifiers, so the mesh keeps running while backgrounded.
* Two backgrounded iPhones can only discover each other's *overflow* service
  UUID, which is an iOS limitation; discovery is fastest when at least one side is
  foregrounded.
* SwiftData `@Query` drives the UI; `MeshService` writes on the main context.

## Building

```bash
# Reference tests + regenerate vectors
node tools/protocol/test.js
node tools/protocol/gen-vectors.js

# Android
cd android && ./gradlew testDebugUnitTest assembleDebug

# Android core only, without the SDK (needs kotlinc + JDK 17+)
tools/conformance/run-kotlin.sh

# iOS (macOS)
cd ios && xcodegen generate && xcodebuild -scheme Ripple -destination 'platform=iOS Simulator,name=iPhone 15' test
```

Real Bluetooth cannot be exercised on a simulator/emulator; test the mesh with two
or more physical devices. A useful smoke test is three phones in a line with the
outer two out of each other's range — a direct message between them must arrive
via the middle phone, and the middle phone must not be able to read it.
