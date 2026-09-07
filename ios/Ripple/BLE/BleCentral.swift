import Foundation
import CoreBluetooth
import os

/// Central role: scans for the mesh service and opens an outgoing GATT link to
/// each peripheral found.
final class BleCentral: NSObject {
    private static let log = Logger(subsystem: "app.ripple.mesh", category: "central")
    private let maxOutgoing = 6
    private let reconnectBackoff: TimeInterval = 15

    private var manager: CBCentralManager!
    private let queue = DispatchQueue(label: "app.ripple.mesh.central")
    private let serviceUUID = CBUUID(string: MeshProtocol.serviceUUID)
    private let rxUUID = CBUUID(string: MeshProtocol.rxUUID)
    private let txUUID = CBUUID(string: MeshProtocol.txUUID)

    private var links: [UUID: CentralLink] = [:]
    private let log = EventLog.global
    func allLinks() -> [BleLink] { queue.sync { Array(links.values) } }
    func refreshRssi() { queue.async { [self] in links.values.forEach { $0.peripheral.readRSSI() } } }
    private var recentlyFailed: [UUID: Date] = [:]
    private(set) var isScanning = false

    private let onPacket: (BleLink, Data) -> Void
    private let onLinkReady: (BleLink) -> Void
    private let onLinkClosed: (BleLink) -> Void
    var onStateChange: ((CBManagerState) -> Void)?
    /// Devices we already hold an incoming link from; skip connecting out to them.
    var shouldSkip: (CBPeripheral) -> Bool = { _ in false }

    init(onPacket: @escaping (BleLink, Data) -> Void, onLinkReady: @escaping (BleLink) -> Void, onLinkClosed: @escaping (BleLink) -> Void) {
        self.onPacket = onPacket; self.onLinkReady = onLinkReady; self.onLinkClosed = onLinkClosed
        super.init()
        manager = CBCentralManager(delegate: self, queue: queue,
                                   options: [CBCentralManagerOptionRestoreIdentifierKey: "app.ripple.mesh.central"])
    }

    var state: CBManagerState { manager.state }

    func startScanning() {
        queue.async { [self] in
            guard manager.state == .poweredOn, !isScanning else { return }
            manager.scanForPeripherals(withServices: [serviceUUID], options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
            isScanning = true
            Self.log.info("scanning"); log.i("central", "scanning for mesh service")
        }
    }

    func stopScanning() {
        queue.async { [self] in
            guard isScanning else { return }
            manager.stopScan(); isScanning = false
        }
    }

    func stop() {
        stopScanning()
        queue.async { [self] in links.values.forEach { $0.close() } }
    }

    var linkCount: Int { links.count }

    private func consider(_ peripheral: CBPeripheral, rssi: Int) {
        let id = peripheral.identifier
        guard links[id] == nil, links.count < maxOutgoing, !shouldSkip(peripheral) else { return }
        if let failedAt = recentlyFailed[id], Date().timeIntervalSince(failedAt) < reconnectBackoff { return }
        let link = CentralLink(peripheral: peripheral, central: self)
        link.rssi = rssi
        links[id] = link
        Self.log.info("connecting to \(id)"); log.i("central", "connecting → \(id.uuidString.prefix(8)) (rssi \(link.rssi.map(String.init) ?? "?"))")
        manager.connect(peripheral, options: nil)
        queue.asyncAfter(deadline: .now() + 20) { [weak self, weak link] in
            guard let self, let link, !link.ready, !link.isClosed else { return }
            Self.log.warning("\(link.id) setup timeout"); self.log.w("central", "\(link.id) setup timeout")
            self.fail(link)
        }
    }

    fileprivate func fail(_ link: CentralLink) {
        recentlyFailed[link.peripheral.identifier] = Date()
        link.close()
    }

    fileprivate func linkClosed(_ link: CentralLink) {
        queue.async { [self] in
            if links[link.peripheral.identifier] === link { links[link.peripheral.identifier] = nil }
            manager.cancelPeripheralConnection(link.peripheral)
        }
        onLinkClosed(link)
    }

    // MARK: Link

    final class CentralLink: BleLink {
        let peripheral: CBPeripheral
        fileprivate weak var central: BleCentral?
        fileprivate var rx: CBCharacteristic?
        fileprivate var ready = false

        init(peripheral: CBPeripheral, central: BleCentral) {
            self.peripheral = peripheral; self.central = central
            super.init(id: "out:\(peripheral.identifier.uuidString.prefix(8))",
                       onPacket: central.onPacket, onClosed: { [weak central] in central?.linkClosed($0 as! CentralLink) })
        }

        override func writeFrame(_ frame: Data) -> Bool {
            guard let rx, peripheral.state == .connected else { close(); return true }
            // Use write-with-response so we get flow control via didWriteValueFor.
            peripheral.writeValue(frame, for: rx, type: .withResponse)
            return true
        }

        override func closeTransport() {}
    }
}

extension BleCentral: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        Self.log.info("central state \(central.state.rawValue)")
        onStateChange?(central.state)
        isScanning = false
        if central.state == .poweredOn { startScanning() }
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        // Peripherals restored by the system will be reconnected via didConnect if still linked.
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        links[peripheral.identifier]?.rssi = RSSI.intValue
        consider(peripheral, rssi: RSSI.intValue)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard let link = links[peripheral.identifier] else { central.cancelPeripheralConnection(peripheral); return }
        peripheral.delegate = self
        link.frameSize = min(peripheral.maximumWriteValueLength(for: .withResponse), 512)
        peripheral.discoverServices([serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        if let link = links[peripheral.identifier] { fail(link) }
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        if let link = links[peripheral.identifier] {
            log.i("central", "\(link.id) disconnected\(error.map { ": \($0.localizedDescription)" } ?? "")")
            if !link.ready { recentlyFailed[peripheral.identifier] = Date() }
            link.close()
        }
    }
}

extension BleCentral: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let link = links[peripheral.identifier] else { return }
        guard error == nil, let service = peripheral.services?.first(where: { $0.uuid == serviceUUID }) else { log.w("central", "\(link.id) service discovery failed"); fail(link); return }
        peripheral.discoverCharacteristics([rxUUID, txUUID], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let link = links[peripheral.identifier] else { return }
        guard error == nil, let chars = service.characteristics,
              let rx = chars.first(where: { $0.uuid == rxUUID }), let tx = chars.first(where: { $0.uuid == txUUID }) else { fail(link); return }
        link.rx = rx
        peripheral.setNotifyValue(true, for: tx)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard let link = links[peripheral.identifier] else { return }
        guard error == nil, characteristic.isNotifying else { fail(link); return }
        link.ready = true
        Self.log.info("\(link.id) ready, frame=\(link.frameSize)"); log.i("central", "\(link.id) ready, frame \(link.frameSize) B")
        peripheral.readRSSI()
        onLinkReady(link)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == txUUID, let value = characteristic.value, let link = links[peripheral.identifier] else { return }
        link.onFrame(value)
    }

    func peripheral(_ peripheral: CBPeripheral, didReadRSSI RSSI: NSNumber, error: Error?) {
        if error == nil { links[peripheral.identifier]?.rssi = RSSI.intValue }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let link = links[peripheral.identifier] else { return }
        if error != nil { link.close() } else { link.radioReady() }
    }
}
