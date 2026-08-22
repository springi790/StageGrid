import Foundation
import CoreMIDI

struct StageMidiMessage: Identifiable, Equatable {
    enum Kind: String { case noteOn, noteOff, controlChange, programChange, pitchBend, aftertouch, clock, start, stop, `continue`, other }
    let id = UUID()
    let kind: Kind
    let channel: UInt8
    let data1: UInt8
    let data2: UInt8
    let deviceName: String?

    var display: String {
        switch kind {
        case .noteOn: "NOTE ON · CH \(channel + 1) · \(data1) = \(data2)"
        case .noteOff: "NOTE OFF · CH \(channel + 1) · \(data1)"
        case .controlChange: "CC · CH \(channel + 1) · \(data1) = \(data2)"
        case .programChange: "PC · CH \(channel + 1) · \(data1)"
        case .pitchBend: "PITCH · CH \(channel + 1) · \(Int(data2) << 7 | Int(data1))"
        case .aftertouch: "AFTERTOUCH · CH \(channel + 1)"
        case .clock: "CLOCK"
        case .start: "START"
        case .stop: "STOP"
        case .continue: "CONTINUE"
        case .other: "MIDI"
        }
    }
}

struct StageMidiEndpoint: Identifiable, Hashable {
    let id: MIDIEndpointRef
    let name: String
}

@MainActor
final class MidiManager: ObservableObject {
    @Published private(set) var sources: [StageMidiEndpoint] = []
    @Published private(set) var destinations: [StageMidiEndpoint] = []
    @Published private(set) var lastMessage: StageMidiMessage?
    @Published private(set) var messageCount = 0
    @Published private(set) var clockCount = 0
    @Published private(set) var bindings: [StageMidiBinding] = []
    @Published var learningAction: StageMidiAction?
    @Published var selectedDestinationID: MIDIEndpointRef?

    var onAction: ((StageMidiAction) -> Void)?

    private var client = MIDIClientRef()
    private var inputPort = MIDIPortRef()
    private var outputPort = MIDIPortRef()
    private var connectedSources = Set<MIDIEndpointRef>()
    private var clockTimer: DispatchSourceTimer?
    private let clockQueue = DispatchQueue(label: "dev.stagegrid.midi-clock", qos: .userInteractive)
    private let bindingsKey = "stagegrid.ios.midi-bindings.v1"

    init() {
        loadBindings()
        createClient()
        refreshEndpoints()
    }

    deinit {
        clockTimer?.cancel()
        if inputPort != 0 { MIDIPortDispose(inputPort) }
        if outputPort != 0 { MIDIPortDispose(outputPort) }
        if client != 0 { MIDIClientDispose(client) }
    }

    func refreshEndpoints() {
        var newSources: [StageMidiEndpoint] = []
        for index in 0..<MIDIGetNumberOfSources() {
            let endpoint = MIDIGetSource(index)
            if endpoint != 0 { newSources.append(.init(id: endpoint, name: endpointName(endpoint))) }
        }
        var newDestinations: [StageMidiEndpoint] = []
        for index in 0..<MIDIGetNumberOfDestinations() {
            let endpoint = MIDIGetDestination(index)
            if endpoint != 0 { newDestinations.append(.init(id: endpoint, name: endpointName(endpoint))) }
        }
        sources = newSources
        destinations = newDestinations
        if selectedDestinationID == nil || !newDestinations.contains(where: { $0.id == selectedDestinationID }) {
            selectedDestinationID = newDestinations.first?.id
        }
        connectAllSources()
    }

    func beginLearn(_ action: StageMidiAction) { learningAction = action }
    func cancelLearn() { learningAction = nil }

    func removeBinding(_ binding: StageMidiBinding) {
        bindings.removeAll { $0.id == binding.id }
        persistBindings()
    }

    func clearBindings() {
        bindings.removeAll()
        persistBindings()
    }

    func startClock(bpm: Double) {
        stopClock(sendStop: false)
        guard bpm > 0, selectedDestinationID != nil else { return }
        send(bytes: [0xFA])
        let interval = 60.0 / bpm / 24.0
        let timer = DispatchSource.makeTimerSource(queue: clockQueue)
        timer.schedule(deadline: .now(), repeating: interval, leeway: .microseconds(120))
        timer.setEventHandler { [weak self] in self?.send(bytes: [0xF8]) }
        clockTimer = timer
        timer.resume()
    }

    func updateClockBPM(_ bpm: Double) {
        guard clockTimer != nil else { return }
        startClock(bpm: bpm)
    }

    func stopClock(sendStop: Bool = true) {
        clockTimer?.cancel()
        clockTimer = nil
        if sendStop { send(bytes: [0xFC]) }
    }

    private func createClient() {
        let clientStatus = MIDIClientCreateWithBlock("StageGrid" as CFString, &client) { [weak self] _ in
            Task { @MainActor in self?.refreshEndpoints() }
        }
        guard clientStatus == noErr else { return }
        MIDIInputPortCreateWithBlock(client, "StageGrid Input" as CFString, &inputPort) { [weak self] list, sourceRef in
            guard let self else { return }
            var packet = list.pointee.packet
            for _ in 0..<list.pointee.numPackets {
                let mirror = Mirror(reflecting: packet.data)
                let bytes = mirror.children.prefix(Int(packet.length)).compactMap { $0.value as? UInt8 }
                let source = sourceRef.map { MIDIEndpointRef(UInt(bitPattern: $0)) }
                let name = source.map { self.endpointName($0) }
                self.parse(bytes: bytes, sourceName: name)
                packet = MIDIPacketNext(&packet).pointee
            }
        }
        MIDIOutputPortCreate(client, "StageGrid Output" as CFString, &outputPort)
    }

    private func connectAllSources() {
        guard inputPort != 0 else { return }
        for source in sources where !connectedSources.contains(source.id) {
            let refCon = UnsafeMutableRawPointer(bitPattern: UInt(source.id))
            if MIDIPortConnectSource(inputPort, source.id, refCon) == noErr {
                connectedSources.insert(source.id)
            }
        }
        connectedSources = connectedSources.filter { ref in sources.contains(where: { $0.id == ref }) }
    }

    nonisolated private func parse(bytes: [UInt8], sourceName: String?) {
        guard !bytes.isEmpty else { return }
        var index = 0
        var runningStatus: UInt8?
        while index < bytes.count {
            let byte = bytes[index]
            if byte >= 0xF8 {
                let kind: StageMidiMessage.Kind = switch byte {
                case 0xF8: .clock
                case 0xFA: .start
                case 0xFB: .continue
                case 0xFC: .stop
                default: .other
                }
                deliver(.init(kind: kind, channel: 0, data1: 0, data2: 0, deviceName: sourceName))
                index += 1
                continue
            }
            if byte & 0x80 != 0 {
                runningStatus = byte
                index += 1
            }
            guard let status = runningStatus, status < 0xF0 else { index += 1; continue }
            let type = status & 0xF0
            let channel = status & 0x0F
            let needed = (type == 0xC0 || type == 0xD0) ? 1 : 2
            guard index + needed <= bytes.count else { break }
            let d1 = bytes[index] & 0x7F
            let d2 = needed == 2 ? bytes[index + 1] & 0x7F : 0
            index += needed
            let kind: StageMidiMessage.Kind = switch type {
            case 0x80: .noteOff
            case 0x90: d2 == 0 ? .noteOff : .noteOn
            case 0xB0: .controlChange
            case 0xC0: .programChange
            case 0xD0: .aftertouch
            case 0xE0: .pitchBend
            default: .other
            }
            deliver(.init(kind: kind, channel: channel, data1: d1, data2: d2, deviceName: sourceName))
        }
    }

    nonisolated private func deliver(_ message: StageMidiMessage) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            if message.kind == .clock {
                self.clockCount += 1
                return
            }
            self.messageCount += 1
            self.lastMessage = message
            if let learning = self.learningAction,
               let kind = self.bindingKind(for: message) {
                let binding = StageMidiBinding(
                    deviceName: message.deviceName,
                    kind: kind,
                    channel: message.channel,
                    number: message.data1,
                    action: learning
                )
                self.bindings.removeAll { $0.action.id == learning.id }
                self.bindings.append(binding)
                self.learningAction = nil
                self.persistBindings()
                return
            }
            guard message.kind == .noteOn || message.kind == .controlChange || message.kind == .programChange else { return }
            if message.kind == .controlChange, message.data2 == 0 { return }
            if let binding = self.bindings.first(where: { self.matches($0, message) }) {
                self.onAction?(binding.action)
            }
        }
    }

    private func bindingKind(for message: StageMidiMessage) -> StageMidiBinding.Kind? {
        switch message.kind {
        case .noteOn: .note
        case .controlChange: .controlChange
        case .programChange: .programChange
        default: nil
        }
    }

    private func matches(_ binding: StageMidiBinding, _ message: StageMidiMessage) -> Bool {
        if let name = binding.deviceName, let incoming = message.deviceName, name != incoming { return false }
        guard binding.channel == message.channel, binding.number == message.data1 else { return false }
        return (binding.kind == .note && message.kind == .noteOn)
            || (binding.kind == .controlChange && message.kind == .controlChange)
            || (binding.kind == .programChange && message.kind == .programChange)
    }

    nonisolated private func endpointName(_ endpoint: MIDIEndpointRef) -> String {
        var unmanaged: Unmanaged<CFString>?
        if MIDIObjectGetStringProperty(endpoint, kMIDIPropertyDisplayName, &unmanaged) == noErr,
           let value = unmanaged?.takeRetainedValue() { return value as String }
        if MIDIObjectGetStringProperty(endpoint, kMIDIPropertyName, &unmanaged) == noErr,
           let value = unmanaged?.takeRetainedValue() { return value as String }
        return "MIDI \(endpoint)"
    }

    nonisolated private func send(bytes: [UInt8]) {
        guard !bytes.isEmpty else { return }
        Task { @MainActor [weak self] in
            guard let self, self.outputPort != 0, let destination = self.selectedDestinationID else { return }
            var list = MIDIPacketList()
            var packet = MIDIPacketListInit(&list)
            bytes.withUnsafeBufferPointer { buffer in
                if let base = buffer.baseAddress {
                    packet = MIDIPacketListAdd(&list, 1024, packet, 0, buffer.count, base)
                }
            }
            if packet != nil { MIDISend(self.outputPort, destination, &list) }
        }
    }

    private func endpointName(_ endpoint: MIDIEndpointRef) -> String { Self.endpointNameStatic(endpoint) }

    nonisolated private static func endpointNameStatic(_ endpoint: MIDIEndpointRef) -> String {
        var unmanaged: Unmanaged<CFString>?
        if MIDIObjectGetStringProperty(endpoint, kMIDIPropertyDisplayName, &unmanaged) == noErr,
           let value = unmanaged?.takeRetainedValue() { return value as String }
        return "MIDI \(endpoint)"
    }

    private func loadBindings() {
        if let data = UserDefaults.standard.data(forKey: bindingsKey),
           let saved = try? JSONDecoder().decode([StageMidiBinding].self, from: data) {
            bindings = saved
        }
    }

    private func persistBindings() {
        if let data = try? JSONEncoder().encode(bindings) {
            UserDefaults.standard.set(data, forKey: bindingsKey)
        }
    }
}
