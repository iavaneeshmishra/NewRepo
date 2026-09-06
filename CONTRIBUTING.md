# Contributing to Ripple

Thanks for helping build messaging that works when nothing else does.

## Ways to help

| You have… | You can… |
|---|---|
| **Just one phone** | Run the app with the *simulated neighbourhood* (Settings → Diagnostics), file UI bugs, improve copy/accessibility, translate strings. |
| **Two or more phones** | Run the [field-test checklist](docs/FIELD_TESTING.md) and report results — this is the most valuable thing you can do right now. |
| **A Mac** | Build and test the iOS app; we have far less iOS field data than Android. |
| **Node.js only** | Work on the protocol reference (`tools/protocol/`), the simulator, and the spec. |
| **Nothing to run** | Review `PROTOCOL.md` and `docs/ARCHITECTURE.md` for security or design problems and open an issue. |

## Getting set up

```bash
git clone https://github.com/iavaneeshmishra/NewRepo ripple && cd ripple

# Protocol reference (no toolchain beyond Node 18+)
node tools/protocol/test.js

# Android — open android/ in Android Studio (Hedgehog or newer), or:
cd android && gradle wrapper --gradle-version 8.9 && ./gradlew testDebugUnitTest installDebug

# iOS — Xcode 15.4+
brew install xcodegen && cd ios && xcodegen generate && open Ripple.xcodeproj
```

Bluetooth does not work in emulators/simulators. Use the simulated neighbourhood to
exercise the UI, and physical devices to test the radio.

## The rules that keep the three implementations in sync

Ripple has one protocol and three implementations (Kotlin, Swift, Node). This is
what keeps them from drifting:

1. **`PROTOCOL.md` is the source of truth.** Behaviour that isn't in the spec is a
   bug in the spec, not a feature of one implementation.
2. **Change the reference first.** Any wire-format or routing change lands in
   `tools/protocol/ripple.js` / `mesh-sim.js` with a test, then in Kotlin, then in
   Swift, in the *same* PR.
3. **Regenerate vectors.** `node tools/protocol/gen-vectors.js` rewrites
   `protocol/test-vectors.json` and the copies in both apps. CI fails if they're stale.
4. **Mirror the tests.** `MeshRouterTest.kt`, `MeshRouterTests.swift` and
   `tools/protocol/test.js` contain the same scenarios. Add yours to all three.
5. **The core stays pure.** Nothing in `core/` may import Android or UIKit/CoreBluetooth.
   That's what lets us test it without hardware.

See [docs/PROTOCOL_VERSIONING.md](docs/PROTOCOL_VERSIONING.md) for how breaking changes are handled.

## Pull requests

- Keep PRs focused; a PR that touches the protocol should not also restyle the chat screen.
- CI must be green on all three jobs. It posts compiler errors as a PR comment.
- Describe *how you tested it*: simulation only? one phone with loopback? N real devices?
  There is no shame in "simulation only" — just say so.
- Never log message plaintext, keys, or full node IDs at INFO level. The event log is
  meant to be shared in bug reports.

## Code style

- Kotlin: official style, 4-space indent, no wildcard imports. Compose screens live in `ui/screens`.
- Swift: 4-space indent, `final class` by default, no force-unwraps outside tests except
  for programmer-error invariants with a comment.
- Node: plain CommonJS, no dependencies. It's a reference, not a product.

## Reporting bugs

Use the issue templates. For anything involving the radio, attach the diagnostics
export (Diagnostics → share icon) from **every device involved** — one side's log is
rarely enough to understand a BLE problem.

## Security issues

If you find a vulnerability in the protocol or crypto, please open a **draft security
advisory** on GitHub rather than a public issue, or email the maintainer listed in the
repository profile. We'll credit you in the fix.

## License

By contributing you agree your work is licensed under Apache 2.0, the same as the project.
