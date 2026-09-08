# Ripple — identity backup & restore

App-layer feature (ROADMAP Phase 0.3). **No wire changes**: the blob never goes
over the mesh. It exists so a wiped or replaced phone can *become the same node
again*: restore re-derives the identical P-256 key and therefore the identical
node id, so the device still decrypts its old direct messages and other nodes
keep verifying its signatures.

> ⚠️ **A leaked passphrase = a lost identity.** Whoever holds the blob *and* the
> passphrase *is* this node — they sign as you and read your DMs. Ripple v1 has
> **no revocation and no rotation** (that arrives with Phase 2.2); there is no way
> to invalidate an exported blob. Treat both halves like house keys and store them
> offline, separately from the phone.

## 1. Blob format

Canonical text form (what the QR encodes and what copy/share produces):

```
RIPPLE-BKP:v1:<base64url(salt ‖ nonce ‖ ciphertext ‖ tag)>
```

| Field | Definition |
|---|---|
| `RIPPLE-BKP:v1` | Fixed prefix + format version (`:`, ASCII case-insensitive on decode). |
| `salt` | 16 random bytes. Fresh per call — two backups of the same key differ. |
| `nonce` | 12 random bytes (AES-GCM). |
| `ciphertext ‖ tag` | AES-256-GCM of the payload, 16-byte tag appended. |

The **payload** (97 bytes, the encrypted core):

```
payload = privateScalar(32) ‖ publicKeyWire(65)
```

* `privateScalar` — the P-256 private scalar, big-endian, exactly 32 bytes.
* `publicKeyWire` — the uncompressed X9.63 public key (`0x04 ‖ X ‖ Y`, the same
  65-byte form the wire protocol and `docs/PAIRING.md` use). The Android port
  stores and rebuilds the public key from this field; iOS derives it from the
  scalar and *verifies* it matches. Node ids always re-derive as
  `SHA-256(publicKeyWire)[0:8]` (`PROTOCOL.md` §1), so the id comes back identical.

base64url is RFC 4648 §5 without padding. Decoders additionally accept the
standard alphabet and stray whitespace/newlines anywhere in the text (paste from
messengers, wrapped e-mail text), but always *emit* the canonical form.

## 2. Key derivation and AEAD

```
key  = PBKDF2-HMAC-SHA256(utf8(passphrase), salt, iterations = 150 000, dkLen = 32)
ct   = AES-256-GCM_Encrypt(key, nonce, payload, AAD = "ripple/backup/v1")
```

* 150 000 iterations is a deliberate *mobile* budget: tens of milliseconds per
  attempt on a phone CPU on both platforms. It slows down offline guessing of a
  decent passphrase but is not a hardened KDF — **the passphrase is the whole
  security of the blob**, so pick a long, memorable one. (Phase 0.3 trade-off;
  bumping `iterations` is a `v2` blob format, not a wire change.)
* The AAD string is fixed ASCII and binds the key to *this* format — a blob
  cannot be confused with any other encrypted artifact the apps produce.
* GCM authentication means a wrong passphrase or a tampered character never
  yields plaintext: decode reports `PASSPHRASE` (auth failure — the two cases are
  cryptographically indistinguishable and both mean "no").

## 3. Restore flow

1. New install (or any device) → Settings → **Backup & restore** → paste blob →
   enter passphrase → **Check code**. The app shows the node id the blob
   re-derives; confirm it matches what your peers pinned.
2. **Restore identity** installs the material. If the identity on the device
   differs from the blob's, the screen says so explicitly — restoring a
   *different* identity makes this phone "someone else" to the mesh.
3. Restart the app (Android offers a button; on iOS force-quit). The mesh
   service reads the identity once at start-up, and restarting also stops any
   in-flight signing with the superseded key.
4. Old DMs in the store stay readable (same key), and peers keep verifying your
   signatures (same id + same key). Safety codes for verified peers depend on
   *your* key — restoring the same identity leaves them valid by construction.

Payload validation on install: the scalar must load as a P-256 key whose derived
public key equals the embedded `publicKeyWire` (Android verifies via an
internal sign/verify check, iOS via `Identity(rawScalar:)`). A decoded-but
self-inconsistent blob is refused without touching storage.

## 4. Passphrase equivalence across platforms

The iOS port hashes the **raw UTF-8 bytes** of the passphrase with no Unicode
normalization (its own `HMAC` loop). Android delegates to the platform
`PBEKeySpec`/`PBKDF2WithHmacSHA256` implementation, whose normalization can vary
by provider and OS version — for non-ASCII passphrases an Android↔iOS restore may
therefore not match byte-for-byte. **Use an ASCII passphrase** for guaranteed
cross-platform portability; the shared test vectors pin the ASCII behaviour
exactly, and wrong-normalization failures are loud (auth failure → refusal), never
silent mis-decrypts.

## 5. Where the identity lives afterwards

* **Android (new installs and any restore):** the private scalar is stored
  wrapped by a non-exportable Android Keystore AES-GCM key (`data/IdentityStore.kt`);
  the public wire form is stored beside it. The key pair is rebuilt at each app
  start. At-rest protection is the Keystore, as before; *exportability* is what
  changed, because backup is impossible without the scalar being recoverable.
  A Keystore failure at load **throws** — the app never silently creates a new
  identity (a changed node id would strand every peer's pin).
* **Android (legacy ≥ API 31 Keystore-only installs):** continue to work, but
  their key cannot be exported — the UI says so instead of pretending (there is
  no API to extract a Keystore EC private key, by design). A restore *onto* such
  a device migrates it to the new model.
* **iOS:** the identity has always been a raw scalar in the Keychain
  (`AfterFirstUnlockThisDeviceOnly`), so export/restore are direct; the Keychain
  remains the at-rest protection.

## 6. What is *not* included (v1 trade-offs)

* **No revocation/rotation.** A stolen blob is you until Phase 2.2 lands signed
  key rotation. Until then: store offline, delete nothing sensitive twice.
* **No cloud transport.** QR / paste / share-sheet only; the apps keep the
  no-INTERNET property (Android declares none; nothing here adds networking).
* **No extra pinning of peers.** After restoring onto a *different* phone, your
  peers still verify your signatures (same key), but their *safety-code* check
  predates the restore only if they never pinned you. Nothing to re-verify for a
  same-key restore; that is the point of the feature.
* Message history itself is **not** in the blob — only the identity.

## 7. Platform consistency

The codec lives in pure, duplicated code with **shared literal vectors**, exactly
like `docs/PAIRING.md`:

* Android: `app.ripple.mesh.core.Backup` — tests in `BackupTest.kt`.
* iOS: `Ripple/Core/Backup.swift` — tests in `BackupTests.swift`.
* The vectors (fixed salt/nonce/passphrase ⇒ one exact blob string, 202 chars)
  were derived with a throwaway Node script using `crypto.pbkdf2Sync` +
  `aes-256-gcm` and are hardcoded verbatim in both test files. No
  `tools/protocol` vectors are involved: this is app-layer, not wire bytes.

## 8. Versioning

Application-layer format, independent of the mesh protocol version byte. A
breaking change (e.g. a stronger KDF) means `RIPPLE-BKP:v2`; old builds refuse v2
loudly (decode failure), which for a backup means "no plaintext" — never a
mis-import.
