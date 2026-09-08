import Foundation

/// Guided field-test mode — app-only, no wire changes.
///
/// The scenario catalog mirrors docs/FIELD_TESTING.md so a tester can run the
/// checklist on real phones without reading the doc, and export a report that
/// pastes straight into the "Field test report" issue template. Pure Foundation
/// (no SwiftUI/UIKit) so it is unit-testable.
enum FieldTestResult: String, CaseIterable {
    case notRun = "NOT RUN"
    case passed = "PASS ✅"
    case failed = "FAIL ❌"
    case skipped = "SKIP ⏭"
}

/// One row of the checklist. `steps` / `expected` / `record` mirror the doc columns.
struct FieldTestScenario: Identifiable, Hashable {
    let number: String       // "1.1"
    let tier: Int            // 1, 2 or 3
    let title: String
    let steps: String
    let expected: String
    let record: String

    var id: String { number }
}

struct FieldTestTier {
    let number: Int
    let title: String
}

/// Ordered catalog — the single source of truth for ids and copy on this platform.
enum FieldTestCatalog {
    static let tiers: [FieldTestTier] = [
        FieldTestTier(number: 1, title: "Tier 1 — two phones (A, B)"),
        FieldTestTier(number: 2, title: "Tier 2 — three phones in a line (A — B — C)"),
        FieldTestTier(number: 3, title: "Tier 3 — stress and mixed platforms"),
    ]

    static let scenarios: [FieldTestScenario] = [
        // ---- Tier 1: two phones -------------------------------------------------------
        s("1.1", 1, "First discovery",
          "Launch Ripple on both phones, about 1 m apart, both in the foreground.",
          "Each sees the other in Peers within 15 s at \"1 hop\"; Diagnostics shows one link.",
          "Time to discover; which side is central; RSSI."),
        s("1.2", 1, "Broadcast",
          "A sends a message to \"Everyone nearby\".",
          "B receives it within about 2 s.",
          "Latency."),
        s("1.3", 1, "Direct message",
          "A sends a direct message to B.",
          "B receives it; on A the tick changes ✓ → ✓✓ within 3 s.",
          "Latency to ✓✓."),
        s("1.4", 1, "Long message (fragmentation)",
          "B replies with a message of ~1 000 characters.",
          "Arrives intact — fragmentation works.",
          "Frame size shown in Diagnostics."),
        s("1.5", 1, "Notification while backgrounded",
          "Background B (home screen, screen on). A sends a direct message.",
          "B shows a notification within ~10 s.",
          "Delay."),
        s("1.6", 1, "Locked-screen delivery",
          "Lock B's screen for 5 minutes. A sends a direct message.",
          "B receives it (may take up to ~60 s on iOS).",
          "Delay, or \"never arrived\" + how long you waited."),
        s("1.7", 1, "Bluetooth toggle reconnect",
          "Toggle Bluetooth off then on, on B.",
          "Link drops, then re-forms within ~30 s; no app restart needed.",
          "Time to reconnect."),
        s("1.8", 1, "Kill and reopen",
          "Kill Ripple on B (swipe away), then reopen.",
          "B still knows A as a peer; link re-forms; messages sent while B was dead arrive (store-and-forward).",
          "Did the queued message arrive?"),
        s("1.9", 1, "Range test",
          "Walk apart until the link drops; note the distance; walk back.",
          "Link re-forms automatically.",
          "Range at which it dropped (indoors/outdoors); time to reconnect."),
        // ---- Tier 2: three phones in a line (A — B — C) --------------------------------
        s("2.1", 2, "Hop counting",
          "All three running, C out of A's range (e.g. two rooms apart, B in between). Check Peers on A.",
          "A sees B at 1 hop and C at 2 hops; Diagnostics shows no direct A–C link.",
          "Hop counts on each phone."),
        s("2.2", 2, "Broadcast across the mesh",
          "A sends a broadcast.",
          "B and C both receive it exactly once.",
          "Any duplicates?"),
        s("2.3", 2, "End-to-end privacy",
          "A sends a direct message to C.",
          "C receives it; A gets ✓✓. On B the message does not appear anywhere (it was only ciphertext).",
          "Confirm B's chat list has nothing from A."),
        s("2.4", 2, "Store-and-forward",
          "Turn C off. A sends C a direct message, wait ~30 s, turn C on.",
          "C receives the message once it re-links with B (B held it).",
          "Delay after C came back."),
        s("2.5", 2, "Hub goes down",
          "Turn B off entirely.",
          "A and C lose each other from online status; peers remain listed.",
          "—"),
        // ---- Tier 3: stress and mixed platforms ---------------------------------------
        s("3.1", 3, "Android ↔ iPhone",
          "One Android and one iPhone; run scenarios 1.1–1.3.",
          "Same results as Tier 1.",
          "Which platform ended up central."),
        s("3.2", 3, "Both backgrounded (iOS)",
          "iPhone ↔ iPhone, both backgrounded for 2 min, then A sends.",
          "Message arrives — iOS overflow-area discovery is slow; up to a few minutes is expected.",
          "Delay."),
        s("3.3", 3, "Many phones in one room",
          "4–6 phones in one room.",
          "All see all at 1 hop; a broadcast reaches everyone once.",
          "Time for the last peer to appear; any phone that never linked?"),
        s("3.4", 3, "Broadcast burst",
          "Send 20 broadcasts as fast as you can from A.",
          "All 20 arrive on B, in order.",
          "Any lost?"),
        s("3.5", 3, "Overnight soak",
          "Leave two phones linked overnight, plugged in.",
          "Link still up in the morning, or re-formed; battery drain acceptable.",
          "Battery % used; Diagnostics link age."),
    ]

    static func scenario(_ number: String) -> FieldTestScenario {
        scenarios.first { $0.number == number }!
    }

    static func scenarios(inTier tier: Int) -> [FieldTestScenario] {
        scenarios.filter { $0.tier == tier }
    }

    private static func s(_ number: String, _ tier: Int, _ title: String, _ steps: String, _ expected: String, _ record: String) -> FieldTestScenario {
        FieldTestScenario(number: number, tier: tier, title: title, steps: steps, expected: expected, record: record)
    }
}

/// Verdict + free-form note for one scenario.
struct ScenarioEntry: Equatable {
    var result: FieldTestResult = .notRun
    var note: String = ""
}

/// One field-test run: a value-type snapshot of verdicts/notes per scenario.
/// Mutations return a new [FieldTestSession], which makes it safe to publish
/// via @State/@Published and trivial to test.
struct FieldTestSession: Equatable {
    let startedAt: Date
    var entries: [String: ScenarioEntry]

    init(startedAt: Date = Date(), entries: [String: ScenarioEntry] = [:]) {
        self.startedAt = startedAt
        self.entries = entries
    }

    func recording(_ number: String, _ result: FieldTestResult, note: String? = nil) -> FieldTestSession {
        var copy = self
        let prev = copy.entries[number] ?? ScenarioEntry()
        copy.entries[number] = ScenarioEntry(result: result, note: note ?? prev.note)
        return copy
    }

    func withNote(_ number: String, _ note: String) -> FieldTestSession {
        var copy = self
        let prev = copy.entries[number] ?? ScenarioEntry()
        copy.entries[number] = ScenarioEntry(result: prev.result, note: note)
        return copy
    }

    func entry(_ number: String) -> ScenarioEntry {
        entries[number] ?? ScenarioEntry()
    }

    var passedCount: Int { entries.values.filter { $0.result == .passed }.count }
    var failedCount: Int { entries.values.filter { $0.result == .failed }.count }
    var skippedCount: Int { entries.values.filter { $0.result == .skipped }.count }
    var attemptedCount: Int { passedCount + failedCount }
    var notRunCount: Int { FieldTestCatalog.scenarios.count - entries.values.filter { $0.result != .notRun }.count }

    /// Plain-text report ready to paste into the "Field test report" issue
    /// template. `meshSnapshot` (e.g. the Diagnostics export header) is appended
    /// verbatim when supplied.
    func export(exportedAt: Date = Date(), meshSnapshot: String = "") -> String {
        var out = "Ripple field-test report\n"
        out += "Started \(Self.fmt(startedAt)) · exported \(Self.fmt(exportedAt))\n"
        out += "Verdicts: \(passedCount) passed · \(failedCount) failed · \(skippedCount) skipped · \(notRunCount) not run\n\n"
        for tier in FieldTestCatalog.tiers {
            out += "## \(tier.title)\n"
            for s in FieldTestCatalog.scenarios(inTier: tier.number) {
                let e = entry(s.number)
                out += "\(s.number) \(s.title) — \(e.result.rawValue)\n"
                if !e.note.isEmpty {
                    out += "    \(e.note)\n"
                }
            }
            out += "\n"
        }
        out += "## Extra notes for the issue\n"
        out += "- Build (commit / APK run / TestFlight):\n"
        out += "- Devices and roles (A/B/C, manufacturer, model, OS):\n"
        out += "- Environment (indoors/outdoors, distances, battery settings):\n"
        if !meshSnapshot.isEmpty {
            out += "\n## Mesh snapshot at export time\n"
            let trimmed = meshSnapshot.trimmingCharacters(in: .whitespacesAndNewlines)
            out += trimmed
            if !trimmed.hasSuffix("\n") { out += "\n" }
        }
        return out
    }

    private static let reportFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm"
        f.locale = Locale(identifier: "en_US_POSIX")
        return f
    }()

    private static func fmt(_ d: Date) -> String { reportFormatter.string(from: d) }
}
