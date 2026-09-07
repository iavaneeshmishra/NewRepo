import SwiftUI
import CoreLocation

/// One-shot opt-in location capture for SOS beacons. Nothing is shared unless the
/// operator flips the toggle; coordinates are degraded to the declared accuracy.
final class SosLocationProvider: NSObject, ObservableObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    @Published private(set) var status: CLAuthorizationStatus = .notDetermined
    @Published private(set) var location: SosLocation?

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        status = manager.authorizationStatus
    }

    /// Ask for a fix only when the user has opted in.
    func requestFix() {
        let st = manager.authorizationStatus
        if st == .notDetermined {
            manager.requestWhenInUseAuthorization()
        } else if st == .authorizedWhenInUse || st == .authorizedAlways {
            manager.requestLocation()
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        status = manager.authorizationStatus
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let c = locations.last else { return }
        let acc = Int(min(max(c.horizontalAccuracy, 0), 65_000))
        location = SosLocation(latE7: Int32((c.coordinate.latitude * 1e7).rounded()),
                               lngE7: Int32((c.coordinate.longitude * 1e7).rounded()),
                               accuracyMeters: acc)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // Location is optional; the beacon is still broadcast without a fix.
    }
}

struct SosView: View {
    @EnvironmentObject private var mesh: MeshService
    @Environment(\.dismiss) private var dismiss
    @StateObject private var location = SosLocationProvider()
    @State private var message = ""
    @State private var shareLocation = false
    @State private var sent = false

    private var canSend: Bool {
        !message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || true
    }

    var body: some View {
        Form {
            Section {
                LabeledContent("Status", value: sent ? "Beacon sent" : "Not active")
                if let sos = mesh.recentSos {
                    LabeledContent("Last SOS received", value: sos.fromName ?? sos.from.short)
                }
            }
            Section {
                TextField("Message for responders (optional)", text: $message, axis: .vertical)
                Text("Broadcast to every phone in the mesh — even through power-saver relays — for 72 h.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Section("Opt-in GPS") {
                Toggle("Include my location", isOn: $shareLocation)
                    .onChange(of: shareLocation) { _, on in if on { location.requestFix() } }
                if shareLocation {
                    if let l = location.location {
                        Text("Sharing a fix accurate to ±\(l.accuracyMeters)m").font(.footnote).foregroundStyle(.secondary)
                    } else {
                        Text("Waiting for a GPS fix — no location is sent until one is ready.").font(.footnote).foregroundStyle(.secondary)
                    }
                }
            }
            Section {
                Button(action: send) {
                    Label("Send SOS", systemImage: "exclamationmark.octagon.fill")
                        .frame(maxWidth: .infinity)
                        .fontWeight(.semibold)
                }
                .foregroundStyle(Color.white)
                .listRowBackground(Color.red)
            }
        }
        .navigationTitle("SOS Beacon")
    }

    private func send() {
        let text = message.trimmingCharacters(in: .whitespacesAndNewlines)
        let loc: SosLocation? = (shareLocation && location.location != nil) ? location.location : nil
        mesh.sendSos(text, location: loc)
        sent = true
        dismiss()
    }
}
