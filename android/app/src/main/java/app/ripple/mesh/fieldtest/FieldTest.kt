package app.ripple.mesh.fieldtest

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Guided field-test mode — app-only, no wire changes.
 *
 * The scenario catalog below mirrors docs/FIELD_TESTING.md so a tester can run
 * the checklist on real phones without reading the doc, and export a report that
 * pastes straight into the "Field test report" issue template. Pure Kotlin (no
 * Android imports) so it is unit-testable on the JVM.
 */
enum class FieldTestResult { NOT_RUN, PASSED, FAILED, SKIPPED }

/** One row of the checklist. `steps` / `expected` / `record` mirror the doc columns. */
data class FieldTestScenario(
    val id: String,          // "1.1"
    val tier: Int,           // 1, 2 or 3
    val title: String,
    val steps: String,       // what to do
    val expected: String,    // what should happen
    val record: String,      // what to note down for the report
)

/** Ordered catalog — the single source of truth for ids and copy on this platform. */
data class FieldTestTier(val number: Int, val title: String)

object FieldTestCatalog {
    val tiers: List<FieldTestTier> = listOf(
        FieldTestTier(1, "Tier 1 — two phones (A, B)"),
        FieldTestTier(2, "Tier 2 — three phones in a line (A — B — C)"),
        FieldTestTier(3, "Tier 3 — stress and mixed platforms"),
    )

    val all: List<FieldTestScenario> = listOf(
        // ---- Tier 1: two phones -------------------------------------------------------
        scenario(
            "1.1", 1, "First discovery",
            "Launch Ripple on both phones, about 1 m apart, both in the foreground.",
            "Each sees the other in Peers within 15 s at \"1 hop\"; Diagnostics shows one link.",
            "Time to discover; which side is central; RSSI.",
        ),
        scenario(
            "1.2", 1, "Broadcast",
            "A sends a message to \"Everyone nearby\".",
            "B receives it within about 2 s.",
            "Latency.",
        ),
        scenario(
            "1.3", 1, "Direct message",
            "A sends a direct message to B.",
            "B receives it; on A the tick changes ✓ → ✓✓ within 3 s.",
            "Latency to ✓✓.",
        ),
        scenario(
            "1.4", 1, "Long message (fragmentation)",
            "B replies with a message of ~1 000 characters.",
            "Arrives intact — fragmentation works.",
            "Frame size shown in Diagnostics.",
        ),
        scenario(
            "1.5", 1, "Notification while backgrounded",
            "Background B (home screen, screen on). A sends a direct message.",
            "B shows a notification within ~10 s.",
            "Delay.",
        ),
        scenario(
            "1.6", 1, "Locked-screen delivery",
            "Lock B's screen for 5 minutes. A sends a direct message.",
            "B receives it (may take up to ~60 s on iOS).",
            "Delay, or \"never arrived\" + how long you waited.",
        ),
        scenario(
            "1.7", 1, "Bluetooth toggle reconnect",
            "Toggle Bluetooth off then on, on B.",
            "Link drops, then re-forms within ~30 s; no app restart needed.",
            "Time to reconnect.",
        ),
        scenario(
            "1.8", 1, "Kill and reopen",
            "Kill Ripple on B (swipe away), then reopen.",
            "B still knows A as a peer; link re-forms; messages sent while B was dead arrive (store-and-forward).",
            "Did the queued message arrive?",
        ),
        scenario(
            "1.9", 1, "Range test",
            "Walk apart until the link drops; note the distance; walk back.",
            "Link re-forms automatically.",
            "Range at which it dropped (indoors/outdoors); time to reconnect.",
        ),
        // ---- Tier 2: three phones in a line (A — B — C) --------------------------------
        scenario(
            "2.1", 2, "Hop counting",
            "All three running, C out of A's range (e.g. two rooms apart, B in between). Check Peers on A.",
            "A sees B at 1 hop and C at 2 hops; Diagnostics shows no direct A–C link.",
            "Hop counts on each phone.",
        ),
        scenario(
            "2.2", 2, "Broadcast across the mesh",
            "A sends a broadcast.",
            "B and C both receive it exactly once.",
            "Any duplicates?",
        ),
        scenario(
            "2.3", 2, "End-to-end privacy",
            "A sends a direct message to C.",
            "C receives it; A gets ✓✓. On B the message does not appear anywhere (it was only ciphertext).",
            "Confirm B's chat list has nothing from A.",
        ),
        scenario(
            "2.4", 2, "Store-and-forward",
            "Turn C off. A sends C a direct message, wait ~30 s, turn C on.",
            "C receives the message once it re-links with B (B held it).",
            "Delay after C came back.",
        ),
        scenario(
            "2.5", 2, "Hub goes down",
            "Turn B off entirely.",
            "A and C lose each other from online status; peers remain listed.",
            "—",
        ),
        // ---- Tier 3: stress and mixed platforms ---------------------------------------
        scenario(
            "3.1", 3, "Android ↔ iPhone",
            "One Android and one iPhone; run scenarios 1.1–1.3.",
            "Same results as Tier 1.",
            "Which platform ended up central.",
        ),
        scenario(
            "3.2", 3, "Both backgrounded (iOS)",
            "iPhone ↔ iPhone, both backgrounded for 2 min, then A sends.",
            "Message arrives — iOS overflow-area discovery is slow; up to a few minutes is expected.",
            "Delay.",
        ),
        scenario(
            "3.3", 3, "Many phones in one room",
            "4–6 phones in one room.",
            "All see all at 1 hop; a broadcast reaches everyone once.",
            "Time for the last peer to appear; any phone that never linked?",
        ),
        scenario(
            "3.4", 3, "Broadcast burst",
            "Send 20 broadcasts as fast as you can from A.",
            "All 20 arrive on B, in order.",
            "Any lost?",
        ),
        scenario(
            "3.5", 3, "Overnight soak",
            "Leave two phones linked overnight, plugged in.",
            "Link still up in the morning, or re-formed; battery drain acceptable.",
            "Battery % used; Diagnostics link age.",
        ),
    )

    fun byId(id: String): FieldTestScenario = all.first { it.id == id }
    fun scenariosIn(tier: Int): List<FieldTestScenario> = all.filter { it.tier == tier }

    private fun scenario(id: String, tier: Int, title: String, steps: String, expected: String, record: String) =
        FieldTestScenario(id, tier, title, steps, expected, record)
}

/** Verdict + free-form note for one scenario. */
data class ScenarioEntry(
    val result: FieldTestResult = FieldTestResult.NOT_RUN,
    val note: String = "",
)

/**
 * One field-test run: an immutable snapshot of verdicts/notes per scenario.
 * Mutations return a new [FieldTestSession], which makes it safe to expose via
 * a StateFlow and trivial to test.
 */
class FieldTestSession(
    val startedAtMillis: Long = System.currentTimeMillis(),
    val entries: Map<String, ScenarioEntry> = emptyMap(),
) {
    fun record(id: String, result: FieldTestResult, note: String? = null): FieldTestSession {
        val prev = entries[id] ?: ScenarioEntry()
        val entry = ScenarioEntry(result, note ?: prev.note)
        return FieldTestSession(startedAtMillis, entries + (id to entry))
    }

    fun setNote(id: String, note: String): FieldTestSession {
        val prev = entries[id] ?: ScenarioEntry()
        return FieldTestSession(startedAtMillis, entries + (id to prev.copy(note = note)))
    }

    fun entry(id: String): ScenarioEntry = entries[id] ?: ScenarioEntry()

    val passedCount: Int get() = entries.values.count { it.result == FieldTestResult.PASSED }
    val failedCount: Int get() = entries.values.count { it.result == FieldTestResult.FAILED }
    val skippedCount: Int get() = entries.values.count { it.result == FieldTestResult.SKIPPED }
    val notRunCount: Int get() = FieldTestCatalog.all.size - entries.values.count { it.result != FieldTestResult.NOT_RUN }
    val attemptedCount: Int get() = passedCount + failedCount

    /**
     * Markdown-ish plain-text report ready to paste into the "Field test report"
     * issue template. [meshSnapshot] (e.g. the Diagnostics export header) is
     * appended verbatim when supplied.
     */
    fun export(nowMillis: Long = System.currentTimeMillis(), meshSnapshot: String = ""): String = buildString {
        appendLine("Ripple field-test report")
        appendLine("Started ${fmt(startedAtMillis)} · exported ${fmt(nowMillis)}")
        appendLine("Verdicts: $passedCount passed · $failedCount failed · $skippedCount skipped · $notRunCount not run")
        appendLine()
        for (tier in FieldTestCatalog.tiers) {
            appendLine("## ${tier.title}")
            for (s in FieldTestCatalog.scenariosIn(tier.number)) {
                val e = entry(s.id)
                appendLine("${s.id} ${s.title} — ${mark(e.result)}")
                if (e.note.isNotBlank()) appendLine("    ${e.note}")
            }
            appendLine()
        }
        appendLine("## Extra notes for the issue")
        appendLine("- Build (commit / APK run / TestFlight):")
        appendLine("- Devices and roles (A/B/C, manufacturer, model, OS):")
        appendLine("- Environment (indoors/outdoors, distances, battery settings):")
        if (meshSnapshot.isNotBlank()) {
            appendLine()
            appendLine("## Mesh snapshot at export time")
            append(meshSnapshot.trimEnd())
        }
    }

    private companion object {
        fun mark(r: FieldTestResult): String = when (r) {
            FieldTestResult.PASSED -> "PASS ✅"
            FieldTestResult.FAILED -> "FAIL ❌"
            FieldTestResult.SKIPPED -> "SKIP ⏭"
            FieldTestResult.NOT_RUN -> "NOT RUN"
        }

        fun fmt(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))
    }
}
