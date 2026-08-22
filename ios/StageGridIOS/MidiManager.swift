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
        case .pitchBend: "PITCH · CH \(channel + 1) · \((Int(data2) << 7) | Int(data1))"
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

private final class MidiOutputPipe: @unchecked Sendable {
    private let lock = NSLock()
    private var outputPort = MIDIPortRef()
    private var destination = MIDIEndpointRef()

    func configure(port: MIDIPortRef, destination: MIDIEndpointRef?) {
        lock.lock(); defer { lock.unlock() }
        outputPort = port
        self.destination = destination ?? 0
    }

    func send(_ bytes: [UInt8]) {
        lock.lock(); defer { lock.unlock() }
        guard outputPort != 0, destination != 0, !bytes.isEmpty else { return }
        var list = MIDIPacketList()
        var packet = MIDIPacketListInit(&list)
        bytes.withUnsafeBufferPointer { buffer in
            guard let base = buffer.baseAddress else { return }
            packet = MIDIPacketListAdd(&list, 1024, packet, 0, buffer.count, base)
        }
        if packet != nil { MIDISend(outputPort, destination, &list) }
    }
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
    @Published var selectedDestinationID: MIDIEndpointRef? { didSet { outputPipe.configure(port: outputPort, destination: selectedDestinationID) } }

    var onAction: ((StageMidiAction) -> Void)?

    private var client = MIDIClientRef()
    private var inputPort = MIDIPortRef()
    private var outputPort = MIDIPortRef()
    private var connectedSources = Set<MIDIEndpointRef>()
    private var clockTimer: DispatchSourceTimer?
    private var currentClockBPM: Double?
    private let clockQueue = DispatchQueue(label: "dev.stagegrid.midi-clock", qos: .userInteractive)
    private let outputPipe = MidiOutputPipe()
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
        let newSources = (0..<MIDIGetNumberOfSources()).compactMap { index -> StageMidiEndpoint? in
            let endpoint = MIDIGetSource(index)
            return endpoint == 0 ? nil : .init(id: endpoint, name: Self.endpointName(endpoint))
        }
        let newDestinations = (0..<MIDIGetNumberOfDestinations()).compactMap { index -> StageMidiEndpoint? in
            let endpoint = MIDIGetDestination(index)
            return endpoint == 0 ? nil : .init(id: endpoint, name: Self.endpointName(endpoint))
        }
        sources = newSources
        destinations = newDestinations
        if selectedDestinationID == nil || !newDestinations.contains(where: { $0.id == selectedDestinationID }) {
            selectedDestinationID = newDestinations.first?.id
        }
        outputPipe.configure(port: outputPort, destination: selectedDestinationID)
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
        currentClockBPM = bpm
        outputPipe.send([0xFA])
        let interval = 60.0 / bpm / 24.0
        let timer = DispatchSource.makeTimerSource(queue: clockQueue)
        timer.schedule(deadline: .now(), repeating: interval, leeway: .microseconds(120))
        let pipe = outputPipe
        timer.setEventHandler { pipe.send([0xF8]) }
        clockTimer = timer
        timer.resume()
    }

    func updateClockBPM(_ bpm: Double) {
        guard clockTimer != nil else { return }
        if let currentClockBPM, abs(currentClockBPM - bpm) < 0.02 { return }
        startClock(bpm: bpm)
    }

    func stopClock(sendStop: Bool = true) {
        clockTimer?.cancel()
        clockTimer = nil
        currentClockBPM = nil
        if sendStop { outputPipe.send([0xFC]) }
    }

    private func createClient() {
        let clientStatus = MIDIClientCreateWithBlock("StageGrid" as CFString, &client) { [weak self] _ in
            Task { @MainActor in self?.refreshEndpoints() }
        }
        guard clientStatus == noErr else { return }

        MIDIInputPortCreateWithBlock(client, "StageGrid Input" as CFString, &inputPort) { [weak self] list, sourceRef in
            let source: MIDIEndpointRef? = sourceRef.map { MIDIEndpointRef(truncatingIfNeeded: UInt(bitPattern: $0)) }
            let name = source.map(Self.endpointName)
            let messages = Self.decodePacketList(list, sourceName: name)
            Task { @MainActor in
                guard let self else { return }
                messages.forEach(self.deliver)
            }
        }
        MIDIOutputPortCreate(client, "StageGrid Output" as CFString, &outputPort)
        outputPipe.configure(port: outputPort, destination: selectedDestinationID)
    }

    private func connectAllSources() {
        guard inputPort != 0 else { return }
        for source in sources where !connectedSources.contains(source.id) {
            let refCon = UnsafeMutableRawPointer(bitPattern: UInt(source.id))
            if MIDIPortConnectSource(inputPort, source.id, refCon) == noErr { connectedSources.insert(source.id) }
        }
        connectedSources = connectedSources.filter { ref in sources.contains(where: { $0.id == ref }) }
    }

    private func deliver(_ message: StageMidiMessage) {
        if message.kind == .clock {
            clockCount += 1
            return
        }
        messageCount += 1
        lastMessage = message
        if let learning = learningAction, let kind = bindingKind(for: message) {
            let binding = StageMidiBinding(
                deviceName: message.deviceName,
                kind: kind,
                channel: message.channel,
                number: message.data1,
                action: learning
            )
            bindings.removeAll { $0.action.id == learning.id }
            bindings.append(binding)
            learningAction = nil
            persistBindings()
            return
        }
        guard message.kind == .noteOn || message.kind == .controlChange || message.kind == .programChange else { return }
        if message.kind == .controlChange, message.data2 == 0 { return }
        if let binding = bindings.first(where: { matches($0, message) }) { onAction?(binding.action) }
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

    nonisolated private static func decodePacketList(_ list: UnsafePointer<MIDIPacketList>, sourceName: String?) -> [StageMidiMessage] {
        var output: [StageMidiMessage] = []
        var runningStatus: UInt8?
        var packet = list.pointee.packet
        for _ in 0..<list.pointee.numPackets {
            let mirror = Mirror(reflecting: packet.data)
            let bytes = mirror.children.prefix(Int(packet.length)).compactMap { $0.value as? UInt8 }
            var index = 0
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
                    output.append(.init(kind: kind, channel: 0, data1: 0, data2: 0, deviceName: sourceName))
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
                output.append(.init(kind: kind, channel: channel, data1: d1, data2: d2, deviceName: sourceName))
            }
            packet = MIDIPacketNext(&packet).pointee
        }
        return output
    }

    nonisolated private static func endpointName(_ endpoint: MIDIEndpointRef) -> String {
        var unmanaged: Unmanaged<CFString>?
        if MIDIObjectGetStringProperty(endpoint, kMIDIPropertyDisplayName, &unmanaged) == noErr,
           let value = unmanaged?.takeRetainedValue() { return value as String }
        if MIDIObjectGetStringProperty(endpoint, kMIDIPropertyName, &unmanaged) == noErr,
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
        if let data = try? JSONEncoder().encode(bindings) { UserDefaults.standard.set(data, forKey: bindingsKey) }
    }
}
