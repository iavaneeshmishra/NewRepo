package app.ripple.mesh.fieldtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldTestTest {
    @Test fun `catalog mirrors the field-testing doc ids and grouping`() {
        assertEquals(3, FieldTestCatalog.tiers.size)
        assertEquals(9, FieldTestCatalog.scenariosIn(1).size)
        assertEquals(5, FieldTestCatalog.scenariosIn(2).size)
        assertEquals(5, FieldTestCatalog.scenariosIn(3).size)
        assertEquals(19, FieldTestCatalog.all.size)

        // Ids are unique and tier-prefixed.
        val ids = FieldTestCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.matches(Regex("[123]\\.[0-9]+")) })
        FieldTestCatalog.all.forEach { s -> assertEquals(s.id.substringBefore('.').toInt(), s.tier) }

        // Copy is non-empty everywhere a tester needs guidance.
        FieldTestCatalog.all.forEach { s ->
            assertTrue("steps for ${s.id}", s.steps.isNotBlank())
            assertTrue("expected for ${s.id}", s.expected.isNotBlank())
            assertTrue("record for ${s.id}", s.record.isNotBlank())
        }
    }

    @Test fun `recording verdicts updates counts and notes`() {
        var s = FieldTestSession(startedAtMillis = 0L)
        assertEquals(19, s.notRunCount)
        assertEquals(0, s.attemptedCount)

        s = s.record("1.1", FieldTestResult.PASSED, note = "6 s, A central, RSSI -52")
        s = s.record("1.3", FieldTestResult.FAILED)
        s = s.record("1.6", FieldTestResult.SKIPPED, note = "no second phone available")
        s = s.setNote("1.3", "never arrived after 10 min")

        assertEquals(1, s.passedCount)
        assertEquals(1, s.failedCount)
        assertEquals(1, s.skippedCount)
        assertEquals(16, s.notRunCount)

        val e13 = s.entry("1.3")
        assertEquals(FieldTestResult.FAILED, e13.result)
        assertEquals("never arrived after 10 min", e13.note)
        // record() without a note keeps an existing one.
        assertEquals("6 s, A central, RSSI -52", s.entry("1.1").note)

        // Mutations are immutable: the original session is untouched.
        val original = FieldTestSession()
        assertTrue(original.record("1.1", FieldTestResult.PASSED) != original)
    }

    @Test fun `recording preserves earlier entries and replaces verdicts`() {
        var s = FieldTestSession()
        s = s.record("2.4", FieldTestResult.FAILED, note = "first attempt")
        s = s.record("2.4", FieldTestResult.PASSED, note = "worked after power-cycling C")
        assertEquals(1, s.passedCount)
        assertEquals(0, s.failedCount)
        assertEquals(FieldTestResult.PASSED, s.entry("2.4").result)
        assertEquals("worked after power-cycling C", s.entry("2.4").note)
    }

    @Test fun `export is a self-contained report`() {
        var s = FieldTestSession(startedAtMillis = 1_700_000_000_000L)
        s = s.record("1.1", FieldTestResult.PASSED, note = "6 s, A central, RSSI -52")
        s = s.record("2.4", FieldTestResult.SKIPPED, note = "only two phones today")
        val text = s.export(nowMillis = 1_700_000_100_000L, meshSnapshot = "node: aaaa\nlinks: 1")

        assertTrue(text.contains("Ripple field-test report"))
        assertTrue(text.contains("1 passed · 0 failed · 1 skipped · 17 not run"))
        assertTrue(text.contains("## Tier 1 — two phones (A, B)"))
        assertTrue(text.contains("## Tier 2 — three phones in a line (A — B — C)"))
        assertTrue(text.contains("## Tier 3 — stress and mixed platforms"))
        assertTrue(text.contains("1.1 First discovery — PASS ✅"))
        assertTrue(text.contains("    6 s, A central, RSSI -52"))
        assertTrue(text.contains("2.4 Store-and-forward — SKIP ⏭"))
        assertTrue(text.contains("## Mesh snapshot at export time"))
        assertTrue(text.contains("node: aaaa\nlinks: 1"))
        // Everything a tester still needs to fill in for the issue template.
        assertTrue(text.contains("Devices and roles"))
        assertTrue(text.contains("Environment"))
    }
}
