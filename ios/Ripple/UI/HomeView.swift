import SwiftUI
import SwiftData

struct HomeView: View {
    @EnvironmentObject private var mesh: MeshService
    @Query(sort: \MessageRecord.timestamp, order: .reverse) private var messages: [MessageRecord]
    @Query(sort: \PeerRecord.lastSeen, order: .reverse) private var peers: [PeerRecord]
    @State private var path = NavigationPath()
    @State private var showSettings = false
    @State private var tab = 0

    private struct Summary: Identifiable {
        let conversation: String; let lastText: String; let lastTimestamp: Date; let unread: Int
        var id: String { conversation }
    }

    private var summaries: [Summary] {
        var seen = Set<String>(); var out: [Summary] = []
        for m in messages where !seen.contains(m.conversation) {
            seen.insert(m.conversation)
            let unread = messages.filter { $0.conversation == m.conversation && !$0.outgoing && $0.status == .received }.count
            out.append(Summary(conversation: m.conversation, lastText: m.text, lastTimestamp: m.timestamp, unread: unread))
        }
        return out
    }

    var body: some View {
        NavigationStack(path: $path) {
            VStack(spacing: 0) {
                Picker("", selection: $tab) {
                    Text("Chats").tag(0)
                    Text("Peers (\(peers.count))").tag(1)
                }
                .pickerStyle(.segmented).padding(.horizontal).padding(.bottom, 8)

                if tab == 0 { chatList } else { peerList }
            }
            .navigationTitle("Ripple")
            .toolbar {
                ToolbarItem(placement: .principal) {
                    VStack(spacing: 0) {
                        Text("Ripple").font(.headline)
                        Text(statusLine).font(.caption2).foregroundStyle(mesh.status.bluetoothOn ? Color.accentColor : .red)
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showSettings = true } label: { Image(systemName: "gearshape") }
                        .accessibilityLabel("Settings")
                }
            }
            .navigationDestination(for: String.self) { ChatView(conversation: $0) }
            .sheet(isPresented: $showSettings) { SettingsView() }
            .onAppear { AppDelegate.openConversation = { path.append($0) } }
        }
    }

    private var statusLine: String {
        guard mesh.status.bluetoothOn else { return "Bluetooth is off" }
        let s = mesh.status
        return "\(s.directLinks) direct link\(s.directLinks == 1 ? "" : "s") · \(s.knownPeers) peers known"
    }

    private var chatList: some View {
        List {
            let broadcast = summaries.first { $0.conversation == Persistence.broadcastConversation }
            NavigationLink(value: Persistence.broadcastConversation) {
                HStack(spacing: 12) {
                    Image(systemName: "megaphone.fill").font(.title2).foregroundStyle(Color.accentColor).frame(width: 40)
                    VStack(alignment: .leading) {
                        Text("Everyone nearby").font(.headline)
                        Text(broadcast?.lastText ?? "Public channel — reaches every phone in the mesh").font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                    }
                    Spacer()
                    if let u = broadcast?.unread, u > 0 { UnreadBadge(count: u) }
                }
            }
            ForEach(summaries.filter { $0.conversation != Persistence.broadcastConversation }) { s in
                NavigationLink(value: s.conversation) {
                    HStack(spacing: 12) {
                        AvatarView(nodeIdHex: s.conversation)
                        VStack(alignment: .leading) {
                            Text(peers.first { $0.nodeId == s.conversation }?.name ?? NodeId(hex: s.conversation)?.display ?? s.conversation).font(.headline)
                            Text(s.lastText).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                        }
                        Spacer()
                        VStack(alignment: .trailing) {
                            Text(s.lastTimestamp, style: .time).font(.caption2).foregroundStyle(.secondary)
                            if s.unread > 0 { UnreadBadge(count: s.unread) }
                        }
                    }
                }
            }
        }
        .listStyle(.plain)
    }

    private var peerList: some View {
        Group {
            if peers.isEmpty {
                ContentUnavailableView {
                    Label("No peers yet", systemImage: "dot.radiowaves.left.and.right")
                } description: {
                    Text("Keep Bluetooth on and bring another phone running Ripple within range.")
                } actions: {
                    Button("Only one phone? Try the simulated neighbourhood") { showSettings = true }
                }
            } else {
                List(peers) { p in
                    let online = Date().timeIntervalSince(p.lastSeen) < 300
                    NavigationLink(value: p.nodeId) {
                        HStack(spacing: 12) {
                            AvatarView(nodeIdHex: p.nodeId)
                            VStack(alignment: .leading) {
                                Text(p.name).font(.headline)
                                HStack(spacing: 6) {
                                    Text(NodeId(hex: p.nodeId)?.display ?? p.nodeId).font(.caption.monospaced())
                                    Text("· \(p.hops) hop\(p.hops == 1 ? "" : "s")").font(.caption)
                                }.foregroundStyle(.secondary)
                            }
                            Spacer()
                            Circle().fill(online ? Color.green : Color.gray.opacity(0.4)).frame(width: 10, height: 10)
                                .accessibilityLabel(online ? "Online" : "Offline")
                        }
                    }
                }
                .listStyle(.plain)
            }
        }
    }
}

struct UnreadBadge: View {
    let count: Int
    var body: some View {
        Text("\(count)").font(.caption2.bold()).foregroundStyle(.white)
            .padding(.horizontal, 7).padding(.vertical, 3).background(Color.accentColor, in: Capsule())
            .accessibilityLabel("\(count) unread message\(count == 1 ? "" : "s")")
    }
}

/// Deterministic coloured circle derived from the node id.
struct AvatarView: View {
    let nodeIdHex: String
    var body: some View {
        let n = Int(nodeIdHex.prefix(6), radix: 16) ?? 0
        let hue = Double(n % 360) / 360.0
        ZStack {
            Circle().fill(Color(hue: hue, saturation: 0.45, brightness: 0.75))
            Text(nodeIdHex.suffix(2).uppercased()).font(.subheadline.bold()).foregroundStyle(.white)
        }
        .frame(width: 40, height: 40)
    }
}
