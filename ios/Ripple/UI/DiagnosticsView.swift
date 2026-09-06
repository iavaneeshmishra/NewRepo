import SwiftUI

/// Field-debugging screen: adapter/role state, live links with RSSI and throughput,
/// the rolling event log, loopback toggle, and a share-sheet export for bug reports.
struct DiagnosticsView: View {
    @EnvironmentObject private var mesh: MeshService
    @State private var links: [LinkInfo] = []
    @State private var events: [EventLog.Event] = []
    @State private var shareText: String?
    private let tick = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        List {
            Section {
                statusRow("Bluetooth", mesh.status.bluetoothOn ? "on" : "off", good: mesh.status.bluetoothOn)
                statusRow("Advertising", mesh.status.advertising ? "yes" : "no", good: mesh.status.advertising)
                statusRow("Links", "\(links.count) open · \(mesh.status.directLinks) identified", good: !links.isEmpty)
                statusRow("Peers known", "\(mesh.status.knownPeers)", good: mesh.status.knownPeers > 0)
                Toggle(isOn: Binding(get: { mesh.status.loopback }, set: { mesh.setLoopback($0) })) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Simulated neighbourhood")
                        Text("Adds two fake peers (Asha, Ravi) so you can try the app with one phone. Ravi is two hops away and wanders out of range.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
            }

            if !links.isEmpty {
                Section("Links") {
                    ForEach(links) { l in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("\(l.peerName ?? l.peerShort ?? "identifying…")  ·  \(l.role)")
                                Text("\(l.id)  frame \(l.frameSize)  ↓\(l.packetsIn)p/\(l.bytesIn)B  ↑\(l.packetsOut)p/\(l.bytesOut)B  age \(Int(l.age))s")
                                    .font(.caption2.monospaced()).foregroundStyle(.secondary)
                            }
                            Spacer()
                            if let r = l.rssi {
                                Text("\(r) dBm").font(.caption.monospaced())
                                    .foregroundStyle(r > -60 ? .green : r > -75 ? .orange : .red)
                            }
                        }
                    }
                }
            }

            Section("Event log (\(events.count))") {
                ForEach(events) { e in
                    HStack(alignment: .top, spacing: 6) {
                        Text(String(EventLog.formatTime(e.at).prefix(8))).foregroundStyle(.secondary)
                        Text(e.tag).foregroundStyle(Color.accentColor).frame(width: 64, alignment: .leading)
                        Text(e.message).foregroundStyle(color(for: e.level))
                    }
                    .font(.caption2.monospaced())
                    .listRowInsets(EdgeInsets(top: 2, leading: 12, bottom: 2, trailing: 12))
                }
            }
        }
        .listStyle(.plain)
        .navigationTitle("Diagnostics")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button { EventLog.global.clear(); events = [] } label: { Image(systemName: "trash") }
                Button { shareText = EventLog.global.export(header: mesh.diagnosticsHeader()) } label: { Image(systemName: "square.and.arrow.up") }
            }
        }
        .onReceive(tick) { _ in refresh() }
        .onAppear(perform: refresh)
        .sheet(item: Binding(get: { shareText.map(ShareItem.init) }, set: { shareText = $0?.text })) { item in
            ShareSheet(items: [item.text])
        }
    }

    private func refresh() {
        links = mesh.linkInfos()
        events = EventLog.global.snapshot()
    }

    private func statusRow(_ label: String, _ value: String, good: Bool) -> some View {
        LabeledContent(label) { Text(value).foregroundStyle(good ? .green : .secondary) }
    }

    private func color(for level: EventLog.Level) -> Color {
        switch level {
        case .error: return .red
        case .warn: return .orange
        case .debug: return .secondary
        case .info: return .primary
        }
    }
}

private struct ShareItem: Identifiable { let text: String; var id: String { text } }

private struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: items, applicationActivities: nil) }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}
