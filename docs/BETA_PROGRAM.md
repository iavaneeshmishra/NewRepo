# Ripple beta program

Ripple works without the internet — but it only gets *reliable* by running on
real phones.  Beta testers are the people who make that happen.

## How to join

1. **Watch this repository** (click "Watch → Custom → Releases" at minimum).
2. When a beta tag appears (`v1.0.0-beta.N`), download the build:
   - **Android** — grab the APK from the [GitHub Releases](../../releases) page.
     Enable *Install unknown apps* for your browser or file manager.
   - **iOS** — join via TestFlight (link posted in the release discussion) or
     contact the maintainer to register your device UDID for an Ad Hoc build.
3. Read [docs/FIELD_TESTING.md](FIELD_TESTING.md) — that's your test plan.

## What we need from you

### Minimum (one evening, ~30 min)

Run **Tier 1 scenarios** (1.1–1.9) from `docs/FIELD_TESTING.md` on **two phones**
you own (or borrow).  File a single **Field test report** issue with:

- Phone models + OS versions.
- The build identifier (commit hash or APK/TestFlight build number).
- Scenario results: ✅ / ❌ for each, with timings.
- Diagnostics exports from **both** phones (Settings → Diagnostics → share icon).

That's it.  Even a partial report (you got stuck at 1.6 and gave up) is valuable.

### Ideal (a weekend, ~2 h)

- Run **Tier 1 + Tier 2** (three phones in a line).
- Try Tier 3 scenarios you can manage (mixed platforms, stress, overnight).
- File one report per topology / set of devices, or one big report — either is fine.

### Ongoing

- Retest on new beta builds — regressions happen.
- Try the app in the environment where you'd actually use it: outdoors, in a
  building with thick walls, on a bus, at a campsite.

## What we do with your reports

1. Every report gets a `field-test` label and is triaged within a week.
2. Failures are reproduced (if we have the hardware) or marked `needs-repro` if
   we don't.
3. Bugs are prioritised using the severity levels in
   [docs/RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md).
4. The beta exit criteria (§1.3 of the release checklist) are updated as reports
   come in.

## Rules

- **Don't test alone if the scenario needs two people** — recruit a friend,
  coworker, or fellow tester.
- **Disable battery optimisation** for Ripple on Android (Settings → Apps →
  Ripple → Battery → Unrestricted).  This is the #1 reason for "it works in
  foreground but not background."
- **Don't run the simulated neighbourhood at the same time as real BLE testing.**
  The loopback peers will relay for real traffic, which produces confusing results.
- **Include diagnostics from every phone**, not just the one that misbehaved.
  BLE problems are almost always two-sided.

## Privacy

- The diagnostics export contains BLE metadata (peer IDs, RSSI, timestamps,
  link events).  It does **not** contain message plaintext.
- Your full node ID (public key hash) is in the export.  If you're concerned,
  you can redact it, but it makes debugging harder.
- Reports are public GitHub issues.  Don't include anything you wouldn't post
  publicly.

## FAQ

**I only have one phone.**
That's fine — enable *Simulated neighbourhood* (Settings → Diagnostics) and run
the UI-level scenarios.  File a report labelled "simulated neighbourhood" so we
know.

**The app won't install / crashes on launch.**
File a **Bug report** issue instead — that's a P0 and we want to know immediately.

**I'm on a Xiaomi / Oppo / Vivo / Realme / OnePlus / Huawei.**
We *especially* need you.  Those manufacturers aggressively kill background BLE
services.  Your results on scenarios 1.5, 1.6, and 1.8 are the most valuable
data we can get.

**Can I test across Android ↔ iOS?**
That's Tier 3 scenario 3.1 and it's one of the most important tests.  If you
can do only one thing, do that.

---

*Thank you for field-testing Ripple.  Mesh networking only works if someone
tests the mesh.*
