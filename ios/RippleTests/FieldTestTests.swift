import XCTest
@testable import Ripple

final class FieldTestTests: XCTestCase {
    func testCatalogMirrorsTheFieldTestingDocIdsAndGrouping() {
        XCTAssertEqual(FieldTestCatalog.tiers.count, 3)
        XCTAssertEqual(FieldTestCatalog.scenarios(inTier: 1).count, 9)
        XCTAssertEqual(FieldTestCatalog.scenarios(inTier: 2).count, 5)
        XCTAssertEqual(FieldTestCatalog.scenarios(inTier: 3).count, 5)
        XCTAssertEqual(FieldTestCatalog.scenarios.count, 19)

        // Ids are unique and tier-prefixed.
        let ids = FieldTestCatalog.scenarios.map(\.number)
        XCTAssertEqual(Set(ids).count, ids.count)
        for s in FieldTestCatalog.scenarios {
            XCTAssertTrue(s.number.range(of: #"^[123]\.[0-9]+$"#, options: .regularExpression) != nil)
            XCTAssertEqual(Int(s.number.prefix(1)), s.tier)
        }

        // Copy is non-empty everywhere a tester needs guidance.
        for s in FieldTestCatalog.scenarios {
            XCTAssertFalse(s.steps.isEmpty, "steps for \(s.number)")
            XCTAssertFalse(s.expected.isEmpty, "expected for \(s.number)")
            XCTAssertFalse(s.record.isEmpty, "record for \(s.number)")
        }
    }

    func testRecordingVerdictsUpdatesCountsAndNotes() {
        var s = FieldTestSession(startedAt: Date(timeIntervalSince1970: 0))
        XCTAssertEqual(s.notRunCount, 19)
        XCTAssertEqual(s.attemptedCount, 0)

        s = s.recording("1.1", .passed, note: "6 s, A central, RSSI -52")
        s = s.recording("1.3", .failed)
        s = s.recording("1.6", .skipped, note: "no second phone available")
        s = s.withNote("1.3", "never arrived after 10 min")

        XCTAssertEqual(s.passedCount, 1)
        XCTAssertEqual(s.failedCount, 1)
        XCTAssertEqual(s.skippedCount, 1)
        XCTAssertEqual(s.notRunCount, 16)

        XCTAssertEqual(s.entry("1.3").result, .failed)
        XCTAssertEqual(s.entry("1.3").note, "never arrived after 10 min")
        // recording() without a note keeps an existing one.
        XCTAssertEqual(s.entry("1.1").note, "6 s, A central, RSSI -52")

        // Mutations are immutable: the original session is untouched.
        let original = FieldTestSession()
        XCTAssertNotEqual(original.recording("1.1", .passed), original)
    }

    func testRecordingReplacesEarlierVerdict() {
        var s = FieldTestSession()
        s = s.recording("2.4", .failed, note: "first attempt")
        s = s.recording("2.4", .passed, note: "worked after power-cycling C")
        XCTAssertEqual(s.passedCount, 1)
        XCTAssertEqual(s.failedCount, 0)
        XCTAssertEqual(s.entry("2.4").result, .passed)
        XCTAssertEqual(s.entry("2.4").note, "worked after power-cycling C")
    }

    func testExportIsASelfContainedReport() {
        var s = FieldTestSession(startedAt: Date(timeIntervalSince1970: 1_700_000_000))
        s = s.recording("1.1", .passed, note: "6 s, A central, RSSI -52")
        s = s.recording("2.4", .skipped, note: "only two phones today")
        let text = s.export(exportedAt: Date(timeIntervalSince1970: 1_700_000_100), meshSnapshot: "node: aaaa\nlinks: 1")

        XCTAssertTrue(text.contains("Ripple field-test report"))
        XCTAssertTrue(text.contains("1 passed · 0 failed · 1 skipped · 17 not run"))
        XCTAssertTrue(text.contains("## Tier 1 — two phones (A, B)"))
        XCTAssertTrue(text.contains("## Tier 2 — three phones in a line (A — B — C)"))
        XCTAssertTrue(text.contains("## Tier 3 — stress and mixed platforms"))
        XCTAssertTrue(text.contains("1.1 First discovery — PASS ✅"))
        XCTAssertTrue(text.contains("    6 s, A central, RSSI -52"))
        XCTAssertTrue(text.contains("2.4 Store-and-forward — SKIP ⏭"))
        XCTAssertTrue(text.contains("## Mesh snapshot at export time"))
        XCTAssertTrue(text.contains("node: aaaa"))
        // Everything a tester still needs to fill in for the issue template.
        XCTAssertTrue(text.contains("Devices and roles"))
        XCTAssertTrue(text.contains("Environment"))
    }
}
