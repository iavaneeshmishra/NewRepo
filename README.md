# Ripple

**Messaging that doesn't need the internet.**

Ripple is a native Android (Kotlin) and iOS (Swift) messenger that talks
phone-to-phone over Bluetooth LE. Phones in range form a mesh; messages hop across
it (up to 7 hops), are held for peers that are temporarily out of range, and
direct messages are end-to-end encrypted so relays can't read them.

No servers. No accounts. No SIM. The Android app doesn't even request the
`INTERNET` permission.

## Features

- **Multi-hop mesh** — flooding with a seen-cache and TTL, works with any topology.
- **Store-and-forward** — messages for offline peers are kept for 24 h and delivered when the mesh reconnects (persisted across restarts).
- **Public channel + direct chats** — broadcasts reach everyone; direct messages are ECIES-encrypted (P-256 ECDH → HKDF → AES-256-GCM).
- **Signed everything** — every packet is ECDSA-signed by its originator; forged or tampered packets are dropped.
- **Delivery receipts** — direct messages get an ACK routed back through the mesh (✓ sent → ✓✓ delivered).
- **Background operation** — Android foreground service; iOS Bluetooth background modes with state restoration.
- **Hardware-backed identity** — Android Keystore / iOS Keychain; your node ID is derived from your public key.
- **One protocol, three implementations** — Kotlin, Swift, and a Node reference that generates shared test vectors both apps must pass.

## Repository

| Path | |
|------|-|
| [`PROTOCOL.md`](PROTOCOL.md) | Wire format, crypto and routing spec |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | How the apps are put together |
| `android/` | Android app (Compose, Room, minSdk 26) |
| `ios/` | iOS app (SwiftUI, SwiftData, iOS 17+) — `xcodegen generate` to create the project |
| `tools/protocol/` | Reference implementation, mesh simulator, vector generator |
| `protocol/test-vectors.json` | Cross-platform conformance vectors |

## Quick start

```bash
# Verify the protocol + a simulated 9-node mesh (Node ≥ 18)
node tools/protocol/test.js

# Android (first time: generate the wrapper, or just open android/ in Android Studio)
cd android && gradle wrapper --gradle-version 8.9 && ./gradlew testDebugUnitTest installDebug

# iOS (macOS with Xcode 15.4+)
brew install xcodegen && cd ios && xcodegen generate && open Ripple.xcodeproj
```

Install on two or more physical phones (Bluetooth can't be emulated), grant the
Bluetooth permission, and they'll find each other within a few seconds. Set a
display name in Settings; your node ID is shown there too.

## Status

Functionally complete v1. Tested by simulation and conformance vectors; needs
field testing on real hardware. See the *Security* and *Platform notes* sections
in `docs/ARCHITECTURE.md` for known limitations.

## License

Apache 2.0 — see [LICENSE.txt](LICENSE.txt).
