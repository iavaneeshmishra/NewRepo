import SwiftUI

/// Guided field-test mode (ROADMAP Phase 0.1): a phone-based walkthrough of the
/// docs/FIELD_TESTING.md checklist. Verdicts and notes live in the
/// `session` state; the share button exports a report pre-formatted for the
/// "Field test report" issue template, with a Diagnostics snapshot appended.
struct FieldTestView: View {
    @EnvironmentObject private var mesh: MeshService
    @State private var session = FieldTestSession()
    @State private var expanded: Set<String> = [FieldTestCatalog.scenarios[0].number]
    @State private var shareText: String?
    @State private var confirmReset = false

    var body: some View {
        List {
            Section {
                LabeledContent("Scenarios done", value: "\(session.attemptedCount) / \(FieldTestCatalog.scenarios.count)")
                LabeledContent("Verdicts", value: "\(session.passedCount) ✅ · \(session.failedCount) ❌ · \(session.skippedCount) ⏭")
                LabeledContent("Direct links", value: "\(mesh.status.directLinks)")
            } header: {
                Text("Session")
            }

            ForEach(FieldTestCatalog.tiers, id: \.number) { tier in
                Section(tier.title) {
                    ForEach(FieldTestCatalog.scenarios(inTier: tier.number)) { scenario in
                        scenarioRow(scenario)
                    }
                }
            }
        }
        .navigationTitle("Field test")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button { confirmReset = true } label: { Image(systemName: "trash") }
                Button { shareText = session.export(meshSnapshot: mesh.diagnosticsHeader()) } label: { Image(systemName: "square.and.arrow.up") }
            }
        }
        .alert("Reset session", isPresented: $confirmReset) {
            Button("Reset session", role: .destructive) {
                session = FieldTestSession()
                expanded = [FieldTestCatalog.scenarios[0].number]
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Clear all verdicts and notes for this field-test run? The report has not been shared yet.")
        }
        .sheet(item: Binding(get: { shareText.map(FieldShareItem.init) }, set: { shareText = $0?.text })) { item in
            FieldShareSheet(items: [item.text])
        }
    }

    // MARK: - Rows

    private func scenarioRow(_ scenario: FieldTestScenario) -> some View {
        DisclosureGroup(isExpanded: expandedBinding(for: scenario.number)) {
            VStack(alignment: .leading, spacing: 8) {
                Text("Do: \(scenario.steps)").font(.footnote).foregroundStyle(.secondary)
                Text("Expected: \(scenario.expected)").font(.footnote).foregroundStyle(.secondary)
                Text("Record: \(scenario.record)").font(.footnote).foregroundStyle(.secondary)
                TextField("Notes / timings", text: noteBinding(for: scenario.number), axis: .vertical)
                    .lineLimit(1...3)
                    .textFieldStyle(.roundedBorder)
                HStack(spacing: 8) {
                    verdictButton(scenario, .passed, "PASS", .green)
                    verdictButton(scenario, .failed, "FAIL", .red)
                    verdictButton(scenario, .skipped, "SKIP", .orange)
                }
            }
            .padding(.top, 4)
        } label: {
            HStack(spacing: 8) {
                Text(scenario.number).font(.caption.monospaced()).foregroundStyle(Color.accentColor).frame(width: 36, alignment: .leading)
                Text(scenario.title).font(.subheadline)
                Spacer()
                verdictLabel(scenario)
            }
        }
    }

    @ViewBuilder
    private func verdictButton(_ scenario: FieldTestScenario, _ result: FieldTestResult, _ label: String, _ color: Color) -> some View {
        let selected = session.entry(scenario.number).result == result
        if selected {
            Button { record(scenario, result) } label: { Text(label).fontWeight(.semibold).frame(maxWidth: .infinity) }
                .buttonStyle(.borderedProminent)
                .tint(color)
        } else {
            Button { record(scenario, result) } label: { Text(label).fontWeight(.semibold).frame(maxWidth: .infinity) }
                .buttonStyle(.bordered)
                .tint(color)
        }
    }

    @ViewBuilder
    private func verdictLabel(_ scenario: FieldTestScenario) -> some View {
        let e = session.entry(scenario.number)
        if e.result == .notRun {
            Text("not run").font(.caption).foregroundStyle(.tertiary)
        } else {
            Text(e.result.rawValue).font(.caption)
                .foregroundStyle(e.result == .passed ? .green : e.result == .failed ? .red : .orange)
        }
    }

    // MARK: - State helpers

    private func expandedBinding(for number: String) -> Binding<Bool> {
        Binding(get: { expanded.contains(number) }, set: { on in
            if on { expanded.insert(number) } else { expanded.remove(number) }
        })
    }

    private func noteBinding(for number: String) -> Binding<String> {
        Binding(
            get: { session.entry(number).note },
            set: { session = session.withNote(number, $0) }
        )
    }

    private func record(_ scenario: FieldTestScenario, _ result: FieldTestResult) {
        session = session.recording(scenario.number, result, note: session.entry(scenario.number).note)
        // Auto-advance to the next scenario that still needs a run.
        let all = FieldTestCatalog.scenarios
        if let idx = all.firstIndex(where: { $0.number == scenario.number }) {
            let next = all.dropFirst(idx + 1).first { session.entry($0.number).result == .notRun }
            expanded = next.map { [$0.number] } ?? []
        }
    }
}

private struct FieldShareItem: Identifiable {
    let text: String
    var id: String { text }
}

private struct FieldShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: items, applicationActivities: nil) }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}
