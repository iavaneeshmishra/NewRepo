# Ripple — Roadmap

Living plan for Ripple **1.x** (ship the current engine) and the journey to
**2.0**. The v1 engine is functionally complete; the bottleneck today is
real-world validation and the feature surface that a *usable* offline messenger
needs. This document exists so every PR and every field-test run can be checked
against a shared sense of where we are going. Update it as decisions land.

Status of each item uses three labels:

| Label | Meaning |
|-------|---------|
| app-only | No wire change. UI, persistence, notifications, tooling. |
| v1-compatible | New packet *type*, flag bit, or payload — safe to add under the current `0x01` version byte **once** unknown types are relayed, not dropped (see `PROTOCOL_VERSIONING.md`). |
| v2-bump | Changes header, crypto, or IDs — requires the dual-stack version-bump process in `PROTOCOL_VERSIONING.md`. |

Effort is ●○○ (days) → ●●● (weeks of careful work across Kotlin + Swift +
reference + vectors). Everything protocol-touching lands in **all three
implementations plus shared vectors in one PR** — that is non-negotiable
(see `CONTRIBUTING.md`).

---

## Principles

1. **No-internet core stays pure.** The apps keep declaring no
   `INTERNET` permission. Anything that talks to a network is opt-in, separate
   software, or an explicit user choice.
2. **The mesh must not partition.** Compatible changes ride on the current
   version byte; breaking changes follow the bump policy and keep a ≥ 6-month
   dual-stack window.
3. **Field testing is the gate.** Features that change radio behaviour don't
   count as done until they pass the relevant scenarios in
   `docs/FIELD_TESTING.md` on physical hardware and the results are attached.
4. **Three implementations, one truth.** Spec (`PROTOCOL.md`) → reference
   (`tools/protocol/`) → vectors (`protocol/test-vectors.json`) → Kotlin and
   Swift ports, in that order, in one PR.
5. **Deliver in shippable phases.** Every phase below is independently useful
   and independently releasable.

---

## Current status (v1)

Functionally complete: BLE mesh flooding (TTL 7), 72 h store-and-forward,
public channel + ECIES direct messages, signed packets, delivery ACKs, SOS with
opt-in GPS, battery profiles, diagnostics, simulated neighbourhood, reference +
vectors, Android & iOS builds green in CI.

Known v1 trade-offs (from `docs/ARCHITECTURE.md § Security`) that 2.0 must
retire: shared signing/ECDH key, no forward secrecy, replay protection limited
to the 24 h seen cache, metadata visible to relays, 8-byte node IDs, no key
rotation/revocation. Field testing on real hardware has not happened yet.

---

## Phase 0 — Ship 1.x (do this first) · app-only

Goal: get v1 into people's hands and make field testing 10× easier, so every
later phase is validated against reality instead of simulation.

### 0.1 In-app field-test mode  · ●●○ · app-only
- Walk the tester through the scenarios in `docs/FIELD_TESTING.md` (Tier 1–3)
  with on-screen prompts, per-scenario timers, and automatic capture of the
  facts each row asks to record (discovery time, latency to ✓✓, RSSI, frame
  size, link role).
- Produces a single structured result file (the existing Diagnostics export +
  a scenario-result table) ready to attach to a Field test report issue.
- Files: new `FieldTestScreen` beside the existing screens on both platforms;
  drives `EventLog` and per-link stats already exposed by Diagnostics.
- Acceptance: a tester with two phones can run Tier 1 in ~10 minutes without
  reading the docs, and the export contains every field the issue template asks
  for.

### 0.2 QR pairing + key-change alerts  · ●●○ · app-only
- **Identity codes & safety numbers (landed):** out-of-band pairing format and
  12-digit safety-code derivation in pure Kotlin/Swift with shared byte-level test
  vectors (`docs/PAIRING.md`, `core/Pairing` on both platforms). No wire changes.
- **Pairing UI + verified-peer store (landed):** Settings → *Pair & verify* shows your
  identity code as a QR (ZXing / Core Image rendering — no camera; scan with any QR app
  and paste), copies/shares the code, imports a peer's code with full §1 validation and
  the 12-digit safety code displayed for out-of-band compare, and pins verified peers in
  a persistent store (DataStore / UserDefaults JSON). Same-id-different-key imports are
  refused through the shared `Pairing.verifyOutcome` rule (mirrored literal tests), and a
  pin whose key disagrees with the live peer table is banner-flagged on screen.
- Verified-key bookkeeping: the verified-peer store (above) is the pin, and the Pair
  screen flags any pin whose key disagrees with the key currently in the peer table.
  *Scope note:* under v1's
  8-byte ids an ANNOUNCE for an existing id carries a new key only on a ~2^64
  SHA-256 prefix collision (the router already requires
  `id == SHA-256(key)[0:8]`), so this is defence-in-depth today and becomes
  load-bearing when key rotation lands in Phase 2.2 — a core/router change is
  deliberately deferred until then rather than shipping untestable code.
- Acceptance: two phones pair by QR in < 60 s; substituting a different key
  between scans produces a different safety code; a verified peer whose key
  changes (2.2) raises an unmissable alert.

### 0.3 Identity backup & restore  · ●●○ · app-only
- **Landed (`docs/BACKUP.md`):** the identity key exports as a passphrase-encrypted
  blob (PBKDF2-HMAC-SHA256 150k → AES-256-GCM, AAD-bound) shown as a QR payload and a
  copy/share text blob for offline transport — `Backup` core on both platforms with
  mirrored literal vectors; Settings → *Backup & restore* on both apps.
- **Landed:** restore on a fresh install re-derives the same node ID and key. Android
  moves to a backupable storage model (private scalar wrapped by a non-exportable
  Keystore AES key); legacy Keystore-only installs stay functional but cannot export
  (hardware, by design) and the UI says so plainly.
- Documented loudly (`docs/BACKUP.md` §0 + on-screen): a leaked passphrase = lost
  identity — no revocation until Phase 2.2.
- Pending for exit: the acceptance run itself — backup on phone A, wipe/reinstall,
  restore, A still decrypts old DMs and signs packets peers verify (field-test issue).

### 0.4 Release readiness  · ●●○ · app-only
- **Landed:** tag/manual Android release CI builds an AAB, signs only when the
  complete GitHub secret set is present, verifies the merged manifest still has
  no `INTERNET`, and otherwise publishes an unsigned artifact with signing
  instructions. Play internal-track and F-Droid metadata are documented/tracked.
- **Landed:** a TestFlight-friendly Release archive scheme and export options;
  `docs/RELEASING.md` covers Android keystores/secrets, Play, XcodeGen
  archive/export, TestFlight, and the conservative encryption-export decision.
- **Landed:** privacy-bounded previous-crash records fold into the existing
  Diagnostics export (never message plaintext, keys, or full node IDs).
- Acceptance run: a maintainer produces and publishes both apps from one tagged
  commit using only `docs/RELEASING.md`; retain the store links with the release.

### 0.5 Small app-layer polish  · ●○○ · app-only
- **Landed:** i18n scaffolding — Android resources plus deterministic iOS
  literal-as-key extraction and translation guidance in `docs/I18N.md`.
- **Landed:** notification deep-links straight into the right chat (and Android
  SOS screen), per-conversation notification clearing, and unread badge counts.
- **Landed in code:** screen-reader labels and merged message/badge semantics on
  chat, peers, diagnostics, pairing/backup, and field-test screens. The physical
  TalkBack/VoiceOver acceptance run remains pending.
- **Scope decision:** QR codes remain render-only. In-app camera scanning is out
  of scope; use any QR scanner and paste the decoded identity or backup code.

**Phase 0 exit criteria:** Phase 0.1–0.3 in the hands of at least three
two-phone testers; Field test issues filed with the new structured export; 0.4
builds published to an internal track.

---

## Phase 1 — Feel like a real messenger  · mostly v1-compatible

Goal: close the biggest gaps between Ripple and a messenger people *choose* in
an emergency — groups, liveness, ordering, check-ins.

### 1.1 Encrypted group chats  · ●●● · v1-compatible (after the unknown-type fix)
- A group has an id, an owner, a member roster, and a symmetric group key
  delivered to each member over existing ECIES DMs. Group messages are
  broadcast-addressed but encrypted with the group key; relays carry ciphertext
  they cannot read.
- Requires the tracked fix in `PROTOCOL_VERSIONING.md`: unknown packet types
  must be **relayed, not dropped** — land that first as its own change.
- Members join/leave by re-key; roster updates are signed by the owner.
- Acceptance: 3 phones, C outside A's range: A→group reaches C via B, and a
  non-member relay sees only ciphertext. Replayed on the simulator with the
  Loopback neighbourhood before field testing.

### 1.2 Presence + read receipts + typing  · ●●○ · v1-compatible
- Read receipts: extend the ACK flow so the recipient emits a "READ" marker
  (new small packet type) that flips ✓✓ → blue in the sender's UI.
- Presence: replace the 5-minute ANNOUNCE-recency green dot with periodic,
  low-cost signed presence; show last-seen-via-mesh and hop distance.
- Typing indicator for DMs, throttled, expires in a few seconds.
- Acceptance: ✓✓→read flips within a hop-time on both platforms; typing appears
 /disappears without flooding (max N indicators/minute/node).

### 1.3 Ordering & thread integrity  · ●●○ · v1-compatible
- Per-(conversation, sender) sequence numbers with gap detection and a
  "message(s) lost in the mesh" placeholder, so floods that interleave don't
  scramble threads.
- Foundation for later edit/delete tombstones.
- Acceptance: out-of-order delivery in the simulator renders in order with an
  explicit gap marker; no duplicates.

### 1.4 Roll-call / check-in  · ●●○ · v1-compatible
- "Are you OK?" request floods to a list of people (default: all direct peers);
  one-tap "OK / need help / with details" replies aggregate at the requester.
- Tiny payloads, floods cheaply, works store-and-forward — the post-disaster
  accountability feature.
- Acceptance: 4-phone field test, one phone offline then returning, replies
  arrive and aggregate correctly; result exportable with the Phase 0 format.

### 1.5 Media: voice notes, images, files  · ●●● · v2-bump or app-chunked
- Realistic transfer sessions, not blind floods: sender chunks a blob into
  many packets addressed hop-by-hop with per-hop ACK and resume across
  reconnect; priority classes so a big transfer never starves SOS/check-ins.
- Voice notes (Opus ~12 kbps) first — most valuable, small; images after.
- **Open decision:** express chunking at the app layer over current 4 KiB
  packets (v1-compatible, more overhead) vs. a larger payload/transfer type
  (v2-bump). Decide in the spec PR; until then keep plaintext ≤ 4096 B.

**Phase 1 exit criteria:** groups and roll-call pass Tier 2/3 field scenarios;
read receipts and ordering verified on 3 phones in a line (A—B—C); media
transfer does not delay an SOS beacon in the simulator.

---

## Phase 2 — Trust, the v2.0 protocol bump  · v2-bump

Goal: retire the documented v1 security trade-offs. This is the one deliberate
breaking change; do it *once*, early in 2.0, and make everything else ride on
it. Follow `PROTOCOL_VERSIONING.md` step-for-step (issue → spec → dual-stack →
vectors → apps → deprecation window).

### 2.1 Forward secrecy + key separation  · ●●● · v2-bump
- X3DH + double ratchet per conversation (Signal-style, DTN-friendly
  variant), with separate signing and ECDH keys.
- New header/version, new crypto section in `PROTOCOL.md`, new vector file
  (`protocol/test-vectors-v2.json`), dual-stack decode of v1 for ≥ 6 months.

### 2.2 Key rotation & revocation  · ●●○ · v1-compatible design, lands with 2.1
- Signed "successor key" announcements so a lost/compromised phone can rotate
  identity without exile; peers re-verify safety codes after rotation.
- Compromised-key reporting: a signed revocation that the mesh carries in the
  relay store.

### 2.3 Replay & abuse hardening  · ●●○ · app-only + v1-compatible
- Enable the per-source flood budget (exists, disabled) with adaptive,
  operator-configurable limits; mute/block abusive peers locally.
- Tighter replay window: authenticated timestamps + bounded clock skew instead
  of seen-cache-only.

### 2.4 Stronger node IDs  · ●●○ · v2-bump
- Replace the 8-byte ID (truncated hash) with the full hash (or ≥ 16 bytes) to
  retire the collision caveat; keeps display grouping.

**Phase 2 exit criteria:** new vectors pass on all three implementations in
dual-stack mode; a v1-only phone and a v2 phone still relay for each other;
MITM/key-change scenarios from Phase 0.2 pass against the ratcheted stack.

---

## Phase 3 — Reach: where "no internet" stops meaning "no distance"  · mixed

Goal: widen the network — bridging clusters, longer radio range, and flooding
that scales past a roomful of phones.

### 3.1 Optional internet gateway nodes  · ●●● · app-only + new tooling
- A separate, opt-in gateway binary (the Node reference implementation is
  already a headless node) tunnels Ripple packets between distant clusters over
  TCP/WebSocket. Same protocol, same E2E; phones gain nothing, declare nothing.
- Android/iOS UI stays untouched except an optional "route via gateway" hint.

### 3.2 LoRa "mesh peanut" bridge  · ●●● · hardware + v1-compatible
- A small node that speaks BLE to phones and LoRa (km-scale) to other peanuts,
  extending store-and-forward across a village. Spec: peanut is just another
  peer; no app protocol change required.

### 3.3 Long-range BLE + scale-aware flooding  · ●●○ · v1-compatible
- Advertise on LE Coded PHY where supported (range multiplier), and move from
  pure flooding to probabilistic/utility forwarding with backpressure so dense
  meshes don't degrade O(peers²).
- The `tools/protocol/mesh-sim.js` simulator gets congestion scenarios and a
  battery model for these tests before any device work.

### 3.4 Energy-aware mesh operation  · ●●○ · app-only + v1-compatible
- Duty-cycle scanning/advertising per the existing battery profiles; surface
  projected battery cost per profile in the Power screen.

**Phase 3 exit criteria:** gateway demo across two LAN-separated clusters;
peanut range test outdoors (goal ≥ 500 m link); 8-node simulator run shows
broadcast completion without radio collapse.

---

## Backlog (small, anytime)

- Watch/Wear OS companion: SOS trigger + quick reply.
- Structured field reports (water/food/medical/road + GPS) as compact forms.
- Offline map tiles predownloaded and shareable over the mesh.
- Desktop/Pi always-on store-and-forward "mesh peanut" (non-LoRa version of
  3.2, reusing the Node reference).
- Multi-device (same identity on phone + tablet) after 2.2 lands.

---

## How to use this document

- **New idea?** Add it to the backlog with labels; argue for a phase only when
  it has an acceptance criterion attached.
- **Protocol-touching work?** Keep `PROTOCOL_VERSIONING.md` and this file in
  the same PR.
- **Field-test evidence** for an item goes in the issue or PR body, using the
  Phase 0 export format, citing scenario numbers from `FIELD_TESTING.md`.

Status of a phase flips to **done** only when its exit criteria are met —
including the field-testing ones.
