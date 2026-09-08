# Field-test checklist

Bluetooth behaves differently on every phone. The only way Ripple gets reliable is
people running these scenarios on real hardware and reporting what happened. Each
scenario takes 2–10 minutes.

**Prefer the in-app version:** Settings → **Field test** walks you through the same
checklist below with per-scenario prompts, PASS/FAIL/SKIP buttons, space for notes
and timings, and a share button that exports a report pre-formatted for the
[Field test report](../.github/ISSUE_TEMPLATE/field_test_report.yml) issue
template (with a Diagnostics snapshot appended). This page remains the reference
for what each scenario is testing.

**Before you start, on every phone:**
- Install the same build on each device (note the commit or APK/TestFlight build number).
- Grant the Bluetooth permission; on Android 13+ also allow notifications.
- Android: disable battery optimisation for Ripple (Settings → Apps → Ripple → Battery → Unrestricted). Note the phone's manufacturer/model — Xiaomi, Oppo, Vivo, Realme, OnePlus, Huawei are known to kill background BLE aggressively.
- Set distinct display names (Settings) so logs are readable.
- Open Settings → Diagnostics on each phone and leave it visible when possible; the event log is your evidence.

**After each scenario:** share the diagnostics export from **every** phone (Diagnostics → share icon) and attach them to your report, together with the scenario number, result, and timings.

---

## Tier 1 — two phones (A, B)

| # | Scenario | Expected | Record |
|---|----------|----------|--------|
| 1.1 | Launch Ripple on both, 1 m apart, both foregrounded. | Each sees the other in *Peers* within 15 s, "1 hop", Diagnostics shows 1 link (one side `central`, the other `peripheral`). | Time to discover. Which side is central. RSSI. |
| 1.2 | A sends a broadcast. | B receives it within 2 s. | Latency. |
| 1.3 | A sends a direct message to B. | B receives it; on A the tick changes ✓ → ✓✓ within 3 s. | Latency to ✓✓. |
| 1.4 | B replies with a long message (~1 000 characters). | Arrives intact (fragmentation works). | Frame size shown in Diagnostics. |
| 1.5 | Background B (home screen, screen on). A sends a direct message. | B shows a notification within 10 s. | Delay. |
| 1.6 | Lock B's screen for 5 minutes. A sends a direct message. | B receives it (may take up to ~60 s on iOS). | Delay, or "never arrived" + how long you waited. |
| 1.7 | Toggle Bluetooth off then on, on B. | Link drops, then re-forms within 30 s; no app restart needed. | Time to reconnect. |
| 1.8 | Kill Ripple on B (swipe away), then reopen. | B still knows A as a peer (persisted); link re-forms; messages sent by A while B was dead arrive (store-and-forward). | Did the queued message arrive? |
| 1.9 | Walk apart until the link drops; note distance; walk back. | Link re-forms automatically. | Range at which it dropped (indoors/outdoors). Time to reconnect. |

## Tier 2 — three phones in a line (A — B — C)

Position C out of A's range but within B's (e.g. two rooms apart, B in between). Confirm in Diagnostics that A and C have **no** direct link to each other.

| # | Scenario | Expected | Record |
|---|----------|----------|--------|
| 2.1 | All three running. Check Peers on A. | A sees B at 1 hop and C at **2 hops**. | Hop counts on each phone. |
| 2.2 | A sends a broadcast. | B and C both receive it exactly once. | Any duplicates? |
| 2.3 | A sends a direct message to C. | C receives it; A gets ✓✓. On B, the message does **not** appear anywhere (it was only ciphertext). | Confirm B's chat list has nothing from A. |
| 2.4 | Turn C off. A sends C a direct message. Wait 30 s. Turn C on. | C receives the message once it re-links with B (B held it). | Delay after C came back. |
| 2.5 | Turn B off entirely. | A and C lose each other from *online* status; peers remain listed. | — |

## Tier 3 — stress and mixed platforms

| # | Scenario | Expected | Record |
|---|----------|----------|--------|
| 3.1 | Android ↔ iPhone, scenarios 1.1–1.3. | Same as Tier 1. | Which platform ended up central. |
| 3.2 | iPhone ↔ iPhone, both **backgrounded** for 2 min, then A sends. | Message arrives (iOS overflow-area discovery is slow; up to a few minutes is expected). | Delay. |
| 3.3 | 4–6 phones in one room. | All see all at 1 hop; a broadcast reaches everyone once. | Time for the last peer to appear. Any phone that never linked? |
| 3.4 | Send 20 broadcasts as fast as you can from A. | All 20 arrive on B, in order. | Any lost? |
| 3.5 | Leave two phones linked overnight, plugged in. | Link still up in the morning, or re-formed; battery drain acceptable. | Battery % used. Diagnostics link age. |

## Reporting

Open an issue using the **Field test report** template. Minimum information:

- Phones (manufacturer, model, OS version) and roles (A/B/C).
- Build (commit hash or build number).
- Scenario numbers with ✅/❌ and the timings you recorded.
- Diagnostics exports from every phone.

Negative results are as useful as positive ones. "1.6 never arrived on Realme 11 after 10 minutes" is exactly what we need to know.
