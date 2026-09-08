# Ripple — Out-of-band pairing (identity codes & safety numbers)

App-layer feature (ROADMAP Phase 0.2). **No wire changes**: nothing here is sent
over the mesh. Two phones exchange these codes through a side channel — a QR code,
a share sheet, a photo of a screen — and use them to verify each other's identity
beyond what the 8-byte node id alone can guarantee.

Node ids are truncated SHA-256 prefixes (8 bytes, `PROTOCOL.md §1`). Two
conversations can only be confused if their full public keys collide on that
prefix (~2^64 targeted work), but the *whole point* of pairing is to not rely on
that bound: comparing full keys out-of-band is cheap for humans and immune to
truncation.

## 1. Identity code

Canonical UTF-8 text form:

```
RIPPLE-ID:v1:<nodeIdHex>:<publicKeyWireHex>[:<name>]
```

| Segment             | Definition                                                        |
|---------------------|-------------------------------------------------------------------|
| `RIPPLE-ID:v1`      | Fixed prefix + format version.                                     |
| `nodeIdHex`         | 16 lowercase hex chars (the 8-byte node id).                       |
| `publicKeyWireHex`  | 130 lowercase hex chars (65-byte uncompressed X9.63 public key).   |
| `name`              | Optional display name, percent-encoded UTF-8 (RFC 3986 unreserved). |

Encoding rules:

* The node id is *not* redundant: decoders must verify
  `nodeIdHex == SHA-256(publicKeyWire)[0:8]` and reject the code otherwise.
  This makes a mistyped or tampered code fail loudly instead of pairing the
  wrong key.
* Percent-encoding keeps the alphabet to `A–Z a–z 0–9 - . _ ~`; every other
  UTF-8 byte becomes `%XX` with uppercase hex. `:` (the field separator),
  spaces and non-ASCII characters are therefore always encoded.
* The optional name segment is omitted entirely when the name is blank.
* Decoders are case-insensitive on hex (canonical form is lowercase) and must
  reject codes with the wrong prefix, version, lengths, or hex characters.

## 2. Safety code

A 12-digit decimal number, grouped `xxxx xxxx xxxx`, shown on **both** phones
after pairing so the two people can compare them aloud or by sight:

```
digest    = SHA-256(canonicalKeyPair)
canonicalKeyPair = keyA ‖ keyB     when keyA ≤ keyB (unsigned byte order)
                    keyB ‖ keyA    otherwise
value     = big-endian uint40(digest[0..5))          // first 5 bytes
digits    = (value mod 10^12) as decimal, left-padded to 12 digits
```

* **Order-independent**: both phones sort the two keys the same way, so both
  display the identical number. Sorting is unsigned lexicographic byte order of
  the 65-byte wire forms.
* Only 40 bits are used; the tiny modulo bias of the decimal projection is not
  meaningful at 10^12 scale (matching other messengers' short safety numbers).
* The safety code is a *pairing* check, not a secret: it confirms each phone
  holds the other's real public key. Combined with signature verification on
  the mesh, matching codes mean messages you encrypt are readable only by the
  device whose code you saw.

## 3. Verification flow

1. A shows its identity code as a QR (Settings → Pair). B scans/imports it.
2. B now holds A's full public key; B can message A immediately and can show
   `safetyCode(B.key, A.key)`.
3. A imports B's code the same way and shows its own `safetyCode(A.key, B.key)`.
4. Both compare the numbers. Equal → each pins the other as **verified**; the
   app records the verified key so it can detect later discrepancies.

Nothing about this flow requires the two phones to be connected to the mesh yet;
importing a code may even pre-load a peer before it is ever in radio range
(store-and-forward will deliver later).

## 4. Platform consistency

The codecs live in pure, duplicated code so the bytes are identical on Android
and iOS:

* Android: `app.ripple.mesh.core.Pairing`
* iOS: `Ripple/Core/Pairing.swift`

Unit tests on both platforms assert the **same literal vectors**
(`PairingTest.kt` / `PairingTests.swift`), including the canonical encoding of a
fixed key and the safety code `5046 5756 7335` for a fixed key pair, so a code
produced on one platform always decodes on the other.

## 5. Versioning

This is an application-layer format. It can evolve independently of the mesh
protocol version byte; the `v1` in the prefix is this format's own version.
Breaking changes to the format simply stop being decoded by older builds, which
is safe because a failed decode is a failed pairing — never a mis-pairing.
