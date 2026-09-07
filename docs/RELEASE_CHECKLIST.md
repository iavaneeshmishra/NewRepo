# Release checklist

A v1 GA release of Ripple needs more than "it compiles."  This document is the
gate — every box must be checked before the app ships.  Work through it in order;
each section depends on the ones above it.

---

## 0. Pre-release (before the first beta tag)

| # | Item | Owner | Done |
|---|------|-------|------|
| 0.1 | All CI jobs green on `main` (protocol, Android unit + debug build, iOS unit tests). | CI | ☐ |
| 0.2 | `protocol/test-vectors.json` matches `node tools/protocol/gen-vectors.js` output and both app copies match. | CI | ☐ |
| 0.3 | `docs/FIELD_TESTING.md` Tier 1 scenarios (1.1–1.9) passed on at least **3 Android phones** (different manufacturers — must include one Xiaomi/Oppo/Realme) and **2 iPhones**. | Field team | ☐ |
| 0.4 | `docs/FIELD_TESTING.md` Tier 2 scenarios (2.1–2.5) passed on at least **2 Android + 1 iPhone** in a 3-phone line topology. | Field team | ☐ |
| 0.5 | Tier 3 scenario 3.5 (overnight link) ran for ≥ 8 h on at least one Android/iPhone pair; battery drain ≤ 5 % over 8 h (phones plugged in is fine, drain must be reported). | Field team | ☐ |
| 0.6 | No open **P0/P1** bugs labelled `field-test` or `bug`. | Maintainer | ☐ |
| 0.7 | `PROTOCOL.md` reviewed for correctness by at least one person who did *not* write it. | Reviewer | ☐ |
| 0.8 | Security: keys, nonce, node-ID derivation, ECIES AAD re-verified against spec. | Reviewer | ☐ |
| 0.9 | Android `INTERNET` permission absent from merged manifest. | CI / manual | ☐ |
| 0.10 | iOS `NSBluetoothAlwaysUsageDescription` string is clear and honest. | Manual | ☐ |

---

## 1. Beta release (tag `v1.0.0-beta.N`)

### 1.1 Cut the tag

```bash
git tag -a v1.0.0-beta.1 -m "Ripple v1.0.0 beta 1"
git push origin v1.0.0-beta.1
```

The **`beta-release`** workflow runs automatically:

| Step | What it produces |
|------|-----------------|
| Protocol tests | Must pass; tag is rejected otherwise. |
| Android | Signed debug APK uploaded as a GitHub Release asset. |
| iOS | `.ipa` archived (unsigned unless a distribution certificate is configured); uploaded as an artifact. |
| Test vectors | Checked for staleness. |

### 1.2 Distribute the beta

| Platform | Channel | Notes |
|----------|---------|-------|
| Android | GitHub Release APK + direct link | Testers sideload; enable "Install unknown apps." |
| iOS | TestFlight (when Apple Developer account is configured) or Ad Hoc build | Requires UDID registration for Ad Hoc. |

Announce the beta:
- Post in Discussions (or a pinned issue) with the download link, the field-test
  checklist link, and a link to the **Beta feedback** issue template.
- Tag the call for testers in the README "Status" section.

### 1.3 Beta exit criteria

All of these must hold before moving to RC:

| # | Criterion | Evidence |
|---|-----------|----------|
| 1.1 | Tier 1 scenarios pass on **≥ 5 distinct phone models** (≥ 2 iPhone, ≥ 3 Android) with field-test reports filed. | GitHub issues with `field-test` label. |
| 1.2 | Tier 2 scenarios pass on **≥ 2 distinct 3-phone topologies** (at least one Android-only, one mixed). | GitHub issues. |
| 1.3 | No open **P0** bugs. | Issue tracker. |
| 1.4 | All open **P1** bugs have a documented workaround or are explicitly deferred with maintainer sign-off. | Issue comments. |
| 1.5 | Overnight (Tier 3.5) test passed on ≥ 2 device pairs with battery drain ≤ 5 % / 8 h. | Field reports. |
| 1.6 | At least one test with **4+ phones** in the same mesh (Tier 3.3). | Field report. |
| 1.7 | Stress test (Tier 3.4 — 20 rapid broadcasts) passed on at least one topology. | Field report. |
| 1.8 | Diagnostics export reviewed — no message plaintext in the event log. | Manual audit. |

---

## 2. Release Candidate (tag `v1.0.0-rc.N`)

Same workflow as beta; the tag triggers it.

| # | Item | Done |
|---|------|------|
| 2.1 | All beta exit criteria met. | ☐ |
| 2.2 | Changelog drafted (`CHANGELOG.md` or GitHub Release notes). | ☐ |
| 2.3 | App store metadata prepared (descriptions, screenshots, privacy policy URL). | ☐ |
| 2.4 | Google Play internal testing track upload (if Play Store publishing is configured). | ☐ |
| 2.5 | TestFlight external beta (if configured). | ☐ |
| 2.6 | At least 48 h soak with no new P0/P1 filed. | ☐ |

---

## 3. GA release (tag `v1.0.0`)

| # | Item | Done |
|---|------|------|
| 3.1 | RC soak complete, no regressions. | ☐ |
| 3.2 | README "Status" section updated to "Released v1.0.0." | ☐ |
| 3.3 | Git tag `v1.0.0` pushed; `beta-release` workflow creates the GitHub Release. | ☐ |
| 3.4 | Release notes published on GitHub. | ☐ |
| 3.5 | (Optional) Play Store / App Store submissions. | ☐ |
| 3.6 | Announce: social media, project README, Discussions. | ☐ |
| 3.7 | Lock the release branch (or mark it `protected`); open `main` for v2 work. | ☐ |

---

## Severity levels

Used throughout this document:

| Level | Definition |
|-------|-----------|
| **P0** | Data loss, crash on launch, security flaw, message delivered to wrong recipient. |
| **P1** | Feature broken in common scenario (e.g. store-and-forward never delivers, ACK never arrives, background notifications broken on ≥ 1 popular phone). |
| **P2** | Feature broken in edge case, cosmetic issue, minor battery concern. |
| **P3** | Nice-to-have, docs typo, accessibility improvement. |

---

*This checklist is a living document. If you find a gap, open a PR.*
