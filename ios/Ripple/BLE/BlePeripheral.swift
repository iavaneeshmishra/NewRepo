import Foundation
import CoreBluetooth
import os

/// Peripheral role: advertises the mesh service and hosts the GATT server. Each
/// central that subscribes to TX becomes a `PeripheralLink`.
final class BlePeripheral: NSObject {
    private static let log = Logger(subsystem: "app.ripple.mesh", category: "peripheral")

    private var manager: CBPeripheralManager!
    private let queue = DispatchQueue(label: "app.ripple.mesh.peripheral")
    private var tx: CBMutableCharacteristic!
    private var links: [UUID: PeripheralLink] = [:]
    private let log = EventLog.global
    func allLinks() -> [BleLink] { queue.sync { Array(links.values) } }
    private var serviceAdded = false
    private(set) var isAdvertising = false

    private let onPacket: (BleLink, Data) -> Void
    private let onLinkReady: (BleLink) -> Void
    private let onLinkClosed: (BleLink) -> Void
    var onStateChange: ((CBManagerState) -> Void)?

    init(onPacket: @escaping (BleLink, Data) -> Void, onLinkReady: @escaping (BleLink) -> Void, onLinkClosed: @escaping (BleLink) -> Void) {
        self.onPacket = onPacket; self.onLinkReady = onLinkReady; self.onLinkClosed = onLinkClosed
        super.init()
        manager = CBPeripheralManager(delegate: self, queue: queue,
                                      options: [CBPeripheralManagerOptionRestoreIdentifierKey: "app.ripple.mesh.peripheral"])
    }

    var connectedCentralIds: Set<UUID> { queue.sync { Set(links.keys) } }

    func start() { queue.async { [self] in startLocked() } }

    private func startLocked() {
        guard manager.state == .poweredOn else { return }
        if !serviceAdded {
            let rx = CBMutableCharacteristic(type: CBUUID(string: MeshProtocol.rxUUID), properties: [.write, .writeWithoutResponse], value: nil, permissions: [.writeable])
            tx = CBMutableCharacteristic(type: CBUUID(string: MeshProtocol.txUUID), properties: [.notify], value: nil, permissions: [.readable])
            let service = CBMutableService(type: CBUUID(string: MeshProtocol.serviceUUID), primary: true)
            service.characteristics = [rx, tx]
            manager.add(service)
            serviceAdded = true
        }
        startAdvertising()
    }

    private func startAdvertising() {
        guard !manager.isAdvertising else { return }
        // Note: in the background iOS strips the local name and moves the service UUID to the
        // overflow area; other iOS devices scanning for that UUID can still find us.
        manager.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [CBUUID(string: MeshProtocol.serviceUUID)]])
    }

    func stop() {
        queue.async { [self] in
            manager.stopAdvertising(); isAdvertising = false
            links.values.forEach { $0.close() }
        }
    }

    fileprivate func linkClosed(_ link: PeripheralLink) {
        queue.async { [self] in
            if links[link.central.identifier] === link { links[link.central.identifier] = nil }
        }
        onLinkClosed(link)
    }

    final class PeripheralLink: BleLink {
        let central: CBCentral
        fileprivate weak var owner: BlePeripheral?

        init(central: CBCentral, owner: BlePeripheral) {
            self.central = central; self.owner = owner
            super.init(id: "in:\(central.identifier.uuidString.prefix(8))",
                       onPacket: owner.onPacket, onClosed: { [weak owner] in owner?.linkClosed($0 as! PeripheralLink) })
            frameSize = min(central.maximumUpdateValueLength, 512)
        }

        override func writeFrame(_ frame: Data) -> Bool {
            guard let owner else { close(); return true }
            // updateValue returns false when the transmit queue is full; peripheralManagerIsReady
            // will fire and we retry via radioReady().
            let ok = owner.manager.updateValue(frame, for: owner.tx, onSubscribedCentrals: [central])
            if ok { radioReady() }   // notifications have no per-frame ack; release the next one now
            return ok
        }
    }
}

extension BlePeripheral: CBPeripheralManagerDelegate {
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        Self.log.info("peripheral state \(peripheral.state.rawValue)")
        onStateChange?(peripheral.state)
        if peripheral.state == .poweredOn { startLocked() } else { isAdvertising = false }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, willRestoreState dict: [String: Any]) {
        if let services = dict[CBPeripheralManagerRestoredStateServicesKey] as? [CBMutableService], !services.isEmpty {
            serviceAdded = true
            if let t = services.first?.characteristics?.first(where: { $0.uuid == CBUUID(string: MeshProtocol.txUUID) }) as? CBMutableCharacteristic { tx = t }
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error { Self.log.error("add service: \(error.localizedDescription)"); log.e("peripheral", "add service failed: \(error.localizedDescription)"); serviceAdded = false }
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        isAdvertising = error == nil
        if let error { Self.log.error("advertise: \(error.localizedDescription)"); log.e("peripheral", "advertise failed: \(error.localizedDescription)") }
        else { Self.log.info("advertising"); log.i("peripheral", "advertising mesh service") }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        guard characteristic.uuid == CBUUID(string: MeshProtocol.txUUID), links[central.identifier] == nil else { return }
        let link = PeripheralLink(central: central, owner: self)
        links[central.identifier] = link
        Self.log.info("\(link.id) ready, frame=\(link.frameSize)"); log.i("peripheral", "\(link.id) subscribed, frame \(link.frameSize) B")
        onLinkReady(link)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        if let l = links[central.identifier] { log.i("peripheral", "\(l.id) unsubscribed"); l.close() }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for r in requests {
            if r.characteristic.uuid == CBUUID(string: MeshProtocol.rxUUID), let v = r.value { links[r.central.identifier]?.onFrame(v) }
        }
        if let first = requests.first { peripheral.respond(to: first, withResult: .success) }
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        links.values.forEach { $0.radioReady() }
    }
}
