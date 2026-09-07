import SwiftUI

extension BatteryProfile {
    var title: String {
        switch self {
        case .performance: return "Performance"
        case .balanced: return "Balanced"
        case .powerSaver: return "Battery saver"
        }
    }

    var detail: String {
        switch self {
        case .performance:
            return "Relays everything at full speed. Shortest battery life."
        case .balanced:
            return "Relays chat and SOS. Best trade-off for everyday use."
        case .powerSaver:
            return "Stops relaying ordinary chat to save battery, but still forwards SOS beacons and stays reachable."
        }
    }
}

struct PowerView: View {
    @EnvironmentObject private var mesh: MeshService

    var body: some View {
        Form {
            Section {
                Text("How aggressively Ripple forwards messages and manages battery. SOS beacons are always relayed regardless of profile.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Section("Battery profile") {
                ForEach(BatteryProfile.allCases, id: \.self) { profile in
                    Button {
                        mesh.setPowerProfile(profile)
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(profile.title).foregroundStyle(.primary)
                                Text(profile.detail).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            if mesh.powerProfile == profile {
                                Image(systemName: "checkmark.circle.fill").foregroundStyle(Color.accentColor)
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Power")
    }
}
