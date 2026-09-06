# Protocol versioning

Ripple is a mesh: you cannot assume every phone you meet runs the same build as you.
A relief worker's phone from last month must still relay for a volunteer who
installed today. This policy keeps that true.

## The version byte

Every packet starts with a version byte (currently `0x01`). A node **drops** packets
whose version it does not understand — silently, without relaying. That means a
version bump partitions the mesh into nodes that can and cannot talk to each other,
which in a disaster context can be dangerous. We therefore bump it as rarely as
possible.

## What is a compatible change (no bump)

- New `PacketType` values. Unknown types **must be relayed** unchanged (the header
  and signature are type-agnostic) but not delivered. *(Implementations currently
  drop unknown types at decode — fixing this is tracked; until then, adding a type is
  a bump.)*
- New `flags` bits, provided a node that ignores the bit still behaves safely.
- Larger TTL/store limits, different scan/advertise timings, MTU handling.
- Anything in the app layer: UI, persistence, notifications.

## What is a breaking change (bump required)

- Changing the header layout, sizes, or field order.
- Changing the signature input, curve, or hash.
- Changing the ECIES construction (KDF, AEAD, AAD).
- Changing the node-ID derivation.
- Changing fragmentation framing.

## How a bump is done

1. Open an issue titled `protocol: vN+1 — <reason>` and get agreement; this is the
   one place where we prefer slow consensus over speed.
2. Land the spec change in `PROTOCOL.md` under a new section, keeping the old one.
3. Implement **dual-stack** support: nodes speak the new version but continue to
   accept and relay the old one for a deprecation window of at least **6 months**
   from the first release containing the new version.
4. Update the reference, the vectors (new file `protocol/test-vectors-vN.json`), and
   both apps in one PR.
5. After the window, remove the old version in a separate PR.

## Extending payloads instead of the header

Prefer expressing new features *inside* a payload of a new or existing type rather
than by touching the header. For example, an SOS beacon is a new `PacketType` with
its own payload layout, not a new header field.

## Identity and key rotation

Node IDs are derived from public keys. There is currently no key-rotation or
revocation mechanism; a compromised key means a new identity. This is a known v1
limitation and any future design must be a compatible addition (a signed
"successor key" announcement), not a header change.
