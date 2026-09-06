import SwiftUI
import SwiftData

struct ChatView: View {
    let conversation: String
    @EnvironmentObject private var mesh: MeshService
    @Query private var messages: [MessageRecord]
    @Query private var peers: [PeerRecord]
    @State private var draft = ""
    @FocusState private var focused: Bool

    private var isBroadcast: Bool { conversation == Persistence.broadcastConversation }

    init(conversation: String) {
        self.conversation = conversation
        _messages = Query(filter: #Predicate<MessageRecord> { $0.conversation == conversation }, sort: \MessageRecord.timestamp)
        _peers = Query(filter: #Predicate<PeerRecord> { $0.nodeId == conversation })
    }

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 6) {
                        ForEach(messages) { m in
                            MessageBubble(message: m, showSender: isBroadcast).id(m.messageId)
                        }
                    }
                    .padding(12)
                }
                .onChange(of: messages.count) { _, _ in
                    if let last = messages.last { withAnimation { proxy.scrollTo(last.messageId, anchor: .bottom) } }
                }
                .onAppear { if let last = messages.last { proxy.scrollTo(last.messageId, anchor: .bottom) } }
            }
            Divider()
            HStack(alignment: .bottom) {
                TextField("Message", text: $draft, axis: .vertical).lineLimit(1...4)
                    .textFieldStyle(.roundedBorder).focused($focused)
                Button {
                    let t = draft.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !t.isEmpty else { return }
                    mesh.send(conversation: conversation, text: t); draft = ""
                } label: { Image(systemName: "paperplane.fill").font(.title3) }
                .disabled(draft.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            .padding(8)
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                VStack(spacing: 0) {
                    Text(isBroadcast ? "Everyone nearby" : (peers.first?.name ?? NodeId(hex: conversation)?.display ?? conversation)).font(.headline)
                    HStack(spacing: 3) {
                        if !isBroadcast { Image(systemName: "lock.fill").font(.system(size: 9)) }
                        Text(isBroadcast ? "Public · signed · up to 7 hops" : "End-to-end encrypted · relayed by the mesh")
                    }
                    .font(.caption2).foregroundStyle(.secondary)
                }
            }
        }
        .onAppear { mesh.visibleConversation = conversation; mesh.markRead(conversation) }
        .onDisappear { if mesh.visibleConversation == conversation { mesh.visibleConversation = nil } }
    }
}

private struct MessageBubble: View {
    let message: MessageRecord
    let showSender: Bool

    var body: some View {
        let mine = message.outgoing
        HStack {
            if mine { Spacer(minLength: 60) }
            VStack(alignment: .leading, spacing: 2) {
                if showSender && !mine {
                    Text(message.fromName ?? NodeId(hex: message.fromNodeId)?.display ?? message.fromNodeId)
                        .font(.caption.bold()).foregroundStyle(Color.accentColor)
                }
                Text(message.text)
                HStack(spacing: 6) {
                    Spacer(minLength: 0)
                    Text(message.timestamp, style: .time).font(.caption2).foregroundStyle(.secondary)
                    if mine {
                        Text(statusGlyph).font(.caption2).foregroundStyle(message.status == .failed ? .red : .secondary)
                    } else if !message.verified {
                        Text("unverified").font(.caption2).foregroundStyle(.red)
                    }
                }
            }
            .padding(.horizontal, 12).padding(.vertical, 8)
            .background(mine ? Color.accentColor.opacity(0.25) : Color(.secondarySystemBackground),
                        in: UnevenRoundedRectangle(topLeadingRadius: 16, bottomLeadingRadius: mine ? 16 : 4, bottomTrailingRadius: mine ? 4 : 16, topTrailingRadius: 16))
            if !mine { Spacer(minLength: 60) }
        }
    }

    private var statusGlyph: String {
        switch message.status {
        case .pending: return "🕓"
        case .sent: return "✓"
        case .delivered: return "✓✓"
        case .failed: return "!"
        case .received: return ""
        }
    }
}
