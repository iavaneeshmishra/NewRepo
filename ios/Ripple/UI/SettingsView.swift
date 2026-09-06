import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var mesh: MeshService
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("Your node ID") {
                    Text(mesh.router.selfId.display).font(.body.monospaced()).textSelection(.enabled)
                    Text("Derived from a P-256 key generated on this device. Share it out-of-band to let others verify it's you.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Section("Display name") {
                    TextField("Display name", text: $name).onChange(of: name) { _, v in if v.count > 32 { name = String(v.prefix(32)) } }
                    Button("Save") { mesh.setDisplayName(name.trimmingCharacters(in: .whitespaces)) }
                        .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || name.trimmingCharacters(in: .whitespaces) == mesh.displayName)
                }
                Section("Mesh status") {
                    LabeledContent("Bluetooth", value: mesh.status.bluetoothOn ? "On" : "Off")
                    LabeledContent("Advertising", value: mesh.status.advertising ? "Yes" : "No")
                    LabeledContent("Direct links", value: "\(mesh.status.directLinks)")
                    LabeledContent("Peers known", value: "\(mesh.status.knownPeers)")
                }
                Section {
                    Text("Ripple never uses the internet. Messages hop phone-to-phone over Bluetooth LE, are signed by the sender, and direct messages are end-to-end encrypted. Messages for peers who are out of range are held and delivered when the mesh reconnects.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Settings")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .onAppear { name = mesh.displayName }
        }
    }
}
