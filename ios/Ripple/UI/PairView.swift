import SwiftUI
import UIKit

/// Settings → Pair & verify (ROADMAP Phase 0.2; see docs/PAIRING.md).
///
/// Shows this device's identity code as a QR (Core Image, render-only — no camera),
/// lets it be copied/shared as text, imports a peer's code by paste with a parsed
/// summary + 12-digit safety code, and lists the persisted verified peers. A
/// same-id/different-key import is refused via the shared `Pairing.verifyOutcome`
/// rule, never silently re-pinned.
struct PairView: View {
    @EnvironmentObject private var mesh: MeshService

    @State private var importText = ""
    @State private var parsed: Pairing.IdentityCode?
    @State private var parseFailed = false
    @State private var pinNote: PinNote?
    @State private var verified: [VerifiedPeer] = []
    @State private var shareItem: ShareItem?

    private struct ShareItem: Identifiable { let text: String; var id: String { text } }
    private struct PinNote: Identifiable {
        let id = UUID()
        let text: String
        let isError: Bool
    }

    private var selfKeyWire: Data { mesh.router.identity.publicKeyWire }
    private var selfKeyHex: String { selfKeyWire.hex }
    private var identityCode: String { Pairing.encodeIdentityCode(publicKeyWire: selfKeyWire, name: mesh.displayName) }
    private var identityQR: UIImage? { QrCode.image(from: identityCode) }

    /// The 12-digit safety code for (me, imported peer) — shown whether or not pinned.
    private var parsedSafetyCode: String? {
        guard let parsed, let theirs = Data(hex: parsed.publicKeyWireHex) else { return nil }
        return Pairing.safetyCode(publicKeyWireA: selfKeyWire, publicKeyWireB: theirs)
    }
    private var parsedIsSelf: Bool { parsed?.publicKeyWireHex.lowercased() == selfKeyHex }
    /// Defence-in-depth: the imported key vs whatever the mesh peer table holds for that id.
    private var parsedMeshKeyMismatch: Bool {
        guard let parsed, let id = NodeId(hex: parsed.nodeIdHex), let peer = mesh.router.peer(id) else { return false }
        return peer.publicKeyWire.hex != parsed.publicKeyWireHex
    }

    var body: some View {
        Form {
            Section("Your identity code") {
                if let img = identityQR {
                    Image(uiImage: img)
                        .interpolation(.none).resizable().scaledToFit()
                        .frame(maxHeight: 220)
                        .accessibilityLabel("QR code of your Ripple identity code")
                }
                Text(identityCode)
                    .font(.caption.monospaced()).textSelection(.enabled)
                HStack(spacing: 12) {
                    Button("Copy") { UIPasteboard.general.string = identityCode }
                    Button("Share") { shareItem = ShareItem(text: identityCode) }
                }
                .font(.body)
                Text("Scan this with any QR app (or copy/share the text) and hand the code to your contact over a channel you already trust. Ripple never uses the camera.")
                    .font(.footnote).foregroundStyle(.secondary)
            }

            Section("Add a peer") {
                Text("Paste the RIPPLE-ID:v1 code you received from them. The code is fully validated before anything is stored.")
                    .font(.footnote).foregroundStyle(.secondary)
                TextField("Identity code (RIPPLE-ID:v1:…)", text: $importText, axis: .vertical)
                    .lineLimit(2...4)
                    .font(.caption.monospaced())
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .onChange(of: importText) { _, _ in parsed = nil; parseFailed = false; pinNote = nil }
                HStack {
                    Button("Import") { importCode() }
                        .disabled(importText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    Spacer()
                }
                if parseFailed {
                    Text("Not a valid Ripple identity code — check the prefix, lengths and hex, and paste it complete.")
                        .font(.footnote).foregroundStyle(.red)
                }
                if let parsed {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(parsed.name ?? "Peer \(parsed.nodeIdHex.suffix(4))").font(.headline)
                        Text(NodeId(hex: parsed.nodeIdHex)?.display ?? parsed.nodeIdHex)
                            .font(.callout.monospaced()).foregroundStyle(.secondary)
                        Text("Key \(parsed.publicKeyWireHex.prefix(8))…\(parsed.publicKeyWireHex.suffix(8))")
                            .font(.caption.monospaced()).foregroundStyle(.secondary)
                        if let safety = parsedSafetyCode {
                            Text("Safety code — read these digits to each other over any channel:")
                                .font(.footnote).foregroundStyle(.secondary)
                            Text(safety).font(.title2.monospaced())
                            Text("Equal digits on both phones mean you hold each other's real keys, not an impostor's.")
                                .font(.footnote).foregroundStyle(.secondary)
                        }
                        if parsedIsSelf {
                            Text("That is your own identity code.").font(.footnote).foregroundStyle(.red)
                        }
                        if parsedMeshKeyMismatch {
                            Text("Key conflict: the mesh already knows a different key for this node id. Do NOT trust either until you resolve it in person — the pinned key is kept.")
                                .font(.footnote).foregroundStyle(.red)
                        }
                        HStack {
                            Button("Pin as verified") { pin() }
                                .disabled(parsedIsSelf || parsedSafetyCode == nil)
                            Button("Copy") { UIPasteboard.general.string = parsed.encode() }
                        }
                        if let pinNote {
                            Text(pinNote.text).font(.footnote).foregroundStyle(pinNote.isError ? .red : .green)
                        }
                    }
                    .padding(.vertical, 4)
                }
            }

            Section("Verified peers") {
                if verified.isEmpty {
                    Text("No pinned peers yet. Import a peer's identity code above and pin it after comparing safety codes.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                ForEach(verified) { v in verifiedRow(v) }
            }

            Section {
                Text("Pins live only in this app's container, alongside nothing secret (public keys) — but they are never logged. Un-pin only when your contact tells you their identity changed.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Pair & verify")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { verified = VerifiedPeers.all() }
        .sheet(item: $shareItem) { item in ActivityView(items: [item.text]) }
    }

    @ViewBuilder
    private func verifiedRow(_ peer: VerifiedPeer) -> some View {
        let current = NodeId(hex: peer.nodeIdHex).flatMap { mesh.router.peer($0) }
        let mismatch = current.map { $0.publicKeyWire.hex != peer.publicKeyWireHex } ?? false
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(peer.name ?? "Peer \(peer.nodeIdHex.suffix(4))").font(.headline)
                    Text(NodeId(hex: peer.nodeIdHex)?.display ?? peer.nodeIdHex)
                        .font(.caption.monospaced()).foregroundStyle(.secondary)
                }
                Spacer()
                Button {
                    UIPasteboard.general.string = Pairing.IdentityCode(nodeIdHex: peer.nodeIdHex, publicKeyWireHex: peer.publicKeyWireHex, name: peer.name).encode()
                } label: { Image(systemName: "square.and.arrow.up") }
                .buttonStyle(.borderless)
            }
            Text(peer.safetyCode).font(.body.monospaced())
            Text("Pinned \(Date(timeIntervalSince1970: Double(peer.addedAtMs) / 1000).formatted(date: .abbreviated, time: .shortened))")
                .font(.caption2).foregroundStyle(.secondary)
            if mismatch {
                Text("Key conflict: the mesh peer table currently has a different key for this node id.").font(.footnote).foregroundStyle(.red)
            }
        }
        .swipeActions(edge: .trailing) {
            Button(role: .destructive) {
                VerifiedPeers.unpin(nodeIdHex: peer.nodeIdHex)
                verified = VerifiedPeers.all()
            } label: { Label("Remove pin", systemImage: "trash") }
        }
    }

    private func importCode() {
        let code = importText.trimmingCharacters(in: .whitespacesAndNewlines)
        parsed = Pairing.decodeIdentityCode(code)
        parseFailed = parsed == nil
        pinNote = nil
    }

    private func pin() {
        guard let parsed, let safety = parsedSafetyCode else { return }
        let (outcome, _) = VerifiedPeers.pin(nodeIdHex: parsed.nodeIdHex, publicKeyWireHex: parsed.publicKeyWireHex,
                                             name: parsed.name, safetyCode: safety)
        switch outcome {
        case .verified:
            pinNote = PinNote(text: "Pinned — this peer is now verified on this device.", isError: false)
            importText = ""
            self.parsed = nil
        case .alreadyVerified:
            pinNote = PinNote(text: "Already pinned with the same key; record refreshed.", isError: false)
            importText = ""
            self.parsed = nil
        case .conflict:
            pinNote = PinNote(text: "Refused: this node id is pinned to a different key. Your old pin was kept — talk to your contact before trusting either key.", isError: true)
        }
        verified = VerifiedPeers.all()
    }
}

/// UIActivityViewController wrapper for the share sheet (same pattern as Diagnostics).
struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}
