# Changelog

All notable changes to Ripple are documented here.  Format follows
[Keep a Changelog](https://keepachangelog.com/).

## [1.0.0] — 2026-09-07

First stable release.  Internet-free, phone-to-phone encrypted messaging over
Bluetooth LE mesh.

### Added

- **Multi-hop mesh** — flooding with seen-cache and TTL (up to 7 hops), works
  with any topology.
- **Store-and-forward** — messages for offline peers held for 72 h, persisted
  across restarts, delivered when the mesh reconnects.
- **Public channel** — broadcast messages reach every node in the mesh.
- **Direct messages** — ECIES end-to-end encrypted (P-256 ECDH → HKDF-SHA256 →
  AES-256-GCM); relays see only ciphertext.
- **Signed packets** — every packet ECDSA-signed by originator; forged or
  tampered packets dropped.
- **Delivery receipts** — ✓ sent → ✓✓ delivered, routed back through the mesh.
- **SOS beacons** — emergency broadcast with optional GPS, always relayed even
  by power-saver nodes, 72 h store-and-forward.
- **Battery profiles** — PERFORMANCE / BALANCED / POWER_SAVER; power-saver
  nodes relay only SOS and ANNOUNCE traffic.
- **Rate limiting** — per-source flood cap on broadcast/SOS traffic.
- **Background operation** — Android foreground service; iOS Bluetooth
  background modes with state restoration.
- **Hardware-backed identity** — Android Keystore (API 31+) / iOS Keychain;
  node ID derived from P-256 public key.
- **Simulated neighbourhood** — built-in loopback mesh (Asha + Ravi) for
  single-phone testing.
- **Diagnostics** — per-link table (role, RSSI, throughput, age), rolling event
  log, one-tap export.
- **One protocol, three implementations** — Kotlin, Swift, and Node reference
  with shared conformance vectors.
- **Field-test infrastructure** — checklist (`docs/FIELD_TESTING.md`), beta
  program (`docs/BETA_PROGRAM.md`), issue templates for field reports and beta
  feedback.
- **CI** — protocol conformance, Android unit + debug build, iOS unit tests;
  beta/release workflow builds APK and iOS archive on tag push.

### Known limitations (v1)

- Same P-256 key used for signing and ECDH (no key separation).
- No forward secrecy beyond per-message ephemeral keys.
- No replay protection beyond 24 h seen cache.
- Metadata (who talks to whom) visible to relays.
- 8-byte node IDs are not collision-proof against a determined attacker.
- iOS overflow-area discovery is slow when both sides are backgrounded.

### Protocol

- Version 1 (`0x01`).
- Spec: [`PROTOCOL.md`](PROTOCOL.md).
- Test vectors: [`protocol/test-vectors.json`](protocol/test-vectors.json).

---

*For the full release gate history see [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md).*
