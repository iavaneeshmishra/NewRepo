import SwiftUI
import UIKit

/// Settings → Backup & restore (ROADMAP Phase 0.3; see docs/BACKUP.md).
///
/// Creates a passphrase-encrypted `RIPPLE-BKP:v1` blob of this device's identity
/// (shown as QR + copy/share for offline transport) and restores one, re-deriving the
/// same node id and key. The heavy KDF runs off the main thread; nothing here touches
/// the mesh wire format. A leaked passphrase = lost identity — the screen says so.
struct BackupView: View {
    @EnvironmentObject private var mesh: MeshService

    @State private var passphrase = ""
    @State private var passphrase2 = ""
    @State private var blob: String?
    @State private var restoreText = ""
    @State private var restorePassphrase = ""
    @State private var restorePayload: Data?
    @State private var restoreFailure: Backup.Failure?
    @State private var restored = false
    @State private var installError: String?
    @State private var shareItem: ShareItem?
    @State private var busy = false

    private struct ShareItem: Identifiable { let text: String; var id: String { text } }

    private var canCreate: Bool { passphrase.count >= 8 && passphrase == passphrase2 }
    private var restoreNodeId: NodeId? { restorePayload.flatMap { NodeId(hex: Backup.nodeIdHex(of: $0)) } }
    private var isReplacing: Bool {
        guard let restoreNodeId else { return false }
        return restoreNodeId != mesh.router.selfId
    }

    var body: some View {
        Form {
            Section {
                Label("Whoever holds this code AND the passphrase *is* your identity — there is no revocation until Phase 2.2. Store it offline; a leaked passphrase means a lost identity.",
                      systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote).foregroundStyle(.red)
            }

            Section("Create a backup") {
                Text("The passphrase encrypts the export. It is never stored anywhere — a forgotten passphrase makes the backup permanently unreadable.")
                    .font(.footnote).foregroundStyle(.secondary)
                SecureField("Passphrase (min 8 characters)", text: $passphrase)
                    .onChange(of: passphrase) { _, _ in blob = nil }
                SecureField("Repeat passphrase", text: $passphrase2)
                    .onChange(of: passphrase2) { _, _ in blob = nil }
                HStack {
                    Button(busy ? "Working…" : "Create backup code") { create() }
                        .disabled(!canCreate || busy)
                    Spacer()
                    if busy { ProgressView() }
                }
                if let blob {
                    VStack(alignment: .leading, spacing: 8) {
                        if let img = QrCode.image(from: blob) {
                            Image(uiImage: img)
                                .interpolation(.none).resizable().scaledToFit()
                                .frame(maxHeight: 220)
                                .frame(maxWidth: .infinity)
                                .accessibilityLabel("QR code of your backup code")
                        }
                        Text(blob).font(.caption.monospaced()).textSelection(.enabled)
                        HStack(spacing: 12) {
                            Button("Copy") { UIPasteboard.general.string = blob }
                            Button("Share") { shareItem = ShareItem(text: blob) }
                        }
                        Text("Keep the code and the passphrase in separate places (e.g. paper + memory). The QR is only a convenience for a nearby device.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 4)
                }
            }

            Section("Restore") {
                Text("Paste a RIPPLE-BKP:v1 code from any device of this identity. On a fresh install this rebuilds your exact node id and key.")
                    .font(.footnote).foregroundStyle(.secondary)
                TextField("Backup code (RIPPLE-BKP:v1:…)", text: $restoreText, axis: .vertical)
                    .lineLimit(2...4)
                    .font(.caption.monospaced())
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .onChange(of: restoreText) { _, _ in restorePayload = nil; restoreFailure = nil; restored = false }
                SecureField("Passphrase", text: $restorePassphrase)
                    .onChange(of: restorePassphrase) { _, _ in restorePayload = nil; restoreFailure = nil; restored = false }
                HStack {
                    Button(busy ? "Working…" : "Check code") { check() }
                        .disabled(restoreText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || restorePassphrase.isEmpty || busy)
                    Spacer()
                    if busy { ProgressView() }
                }
                if let failure = restoreFailure {
                    Text(failure == .passphrase
                         ? "Wrong passphrase — or the code was tampered with. No plaintext was recovered either way."
                         : "Not a valid Ripple backup code (check prefix, length and base64 body).")
                        .font(.footnote).foregroundStyle(.red)
                }
                if let payload = restorePayload {
                    VStack(alignment: .leading, spacing: 6) {
                        if let node = restoreNodeId {
                            Text("Validates. Restores node \(node.display).").font(.callout.monospaced())
                        }
                        if isReplacing {
                            Text("Heads-up: this differs from the identity currently in use on this device. Peers' safety codes for you refer to your key — after restoring the SAME identity they stay valid; after restoring a DIFFERENT one, you are someone else to the mesh.")
                                .font(.footnote).foregroundStyle(.orange)
                        }
                        if restored {
                            Text("Restored. Force-quit Ripple (swipe it away) and reopen it to activate the identity.")
                                .font(.callout).foregroundStyle(.green)
                        } else {
                            Button("Restore identity") { install(payload) }
                        }
                        if let installError {
                            Text(installError).font(.footnote).foregroundStyle(.red)
                        }
                    }
                    .padding(.vertical, 4)
                }
            }

            Section {
                Text("Restore re-derives the node id and key from the blob, so old messages stay decryptable and other nodes keep verifying your signatures. No wire changes — this never leaves the app. See docs/BACKUP.md.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Backup & restore")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $shareItem) { item in ActivityView(items: [item.text]) }
    }

    // MARK: actions (PBKDF2 150k rounds off the main actor)

    private func create() {
        guard !busy else { return }
        busy = true
        let pw = passphrase
        Task.detached(priority: .userInitiated) {
            let material = IdentityStore.exportMaterial()
            let blob = Backup.createBlob(scalar: material.scalar, publicKeyWire: material.wire, passphrase: pw)
            await MainActor.run { self.blob = blob; self.busy = false }
        }
    }

    private func check() {
        guard !busy else { return }
        busy = true
        let text = restoreText
        let pw = restorePassphrase
        Task.detached(priority: .userInitiated) {
            let result = Backup.readBlob(text, passphrase: pw)
            await MainActor.run {
                restorePayload = result.payload
                restoreFailure = result.failure
                busy = false
            }
        }
    }

    private func install(_ payload: Data) {
        let ok = IdentityStore.prepareRestore(scalar: Backup.scalar(of: payload), wire: Backup.publicKey(of: payload))
        if ok {
            restored = true
        } else {
            // Decoded fine but the scalar does not match the embedded public key — a
            // hand-forged or corrupt payload; refuse and explain.
            restorePayload = nil
            installError = "Refused: the private key in this code does not match its own public key. The blob is corrupt or hand-forged."
        }
    }
}
