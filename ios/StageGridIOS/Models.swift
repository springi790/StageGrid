import Foundation

enum TrackKind: String, Codable, CaseIterable {
    case drums, bass, guitar, keys, synth, strings, vocals, percussion, click, guide, pad, other

    static func infer(from name: String) -> TrackKind {
        let value = name.lowercased()
        if value.contains("drum") || value.contains("bater") { return .drums }
        if value.contains("bass") || value.contains("bajo") { return .bass }
        if value.contains("guitar") || value.contains("guit") { return .guitar }
        if value.contains("keys") || value.contains("piano") || value.contains("tecla") { return .keys }
        if value.contains("synth") { return .synth }
        if value.contains("string") { return .strings }
        if value.contains("vocal") || value.contains("voz") || value.contains("vox") { return .vocals }
        if value.contains("perc") { return .percussion }
        if value.contains("click") || value.contains("metron") { return .click }
        if value.contains("guide") || value.contains("cue") || value.contains("guia") || value.contains("guía") { return .guide }
        if value.contains("pad") { return .pad }
        return .other
    }
}

enum StageStereoRoute: String, Codable, CaseIterable, Identifiable {
    case both = "L+R"
    case left = "L"
    case right = "R"
    var id: String { rawValue }
}

enum StageGuideSource: String, Codable, CaseIterable {
    case original
    case cue
}

enum StageClickSubdivision: Int, Codable, CaseIterable, Identifiable {
    case quarter = 1
    case eighth = 2
    case triplet = 3
    case sixteenth = 4
    var id: Int { rawValue }
    var label: String {
        switch self {
        case .quarter: "1/4"
        case .eighth: "1/8"
        case .triplet: "1/8T"
        case .sixteenth: "1/16"
        }
    }
}

struct StageTrack: Identifiable, Codable, Hashable {
    var id = UUID()
    var name: String
    var kind: TrackKind
    var relativePath: String
    var volume: Float = 1
    var pan: Float = 0
    var muted = false
    var solo = false
    /// Stereo pair index: 0=1/2, 1=3/4, 2=5/6, 3=7/8. Optional keeps alpha01 catalogs decodable.
    var outputBus: Int? = nil
    var stereoRoute: StageStereoRoute? = nil

    var resolvedOutputBus: Int { min(max(outputBus ?? 0, 0), 3) }
    var resolvedStereoRoute: StageStereoRoute { stereoRoute ?? .both }
}

struct StageSection: Identifiable, Codable, Hashable {
    var id = UUID()
    var name: String
    var start: TimeInterval
    var end: TimeInterval
    var addCount = false
    var colorHex: String? = nil

    var resolvedColorHex: String { colorHex ?? "#5B8CFF" }
}

struct StageArrangementNode: Identifiable, Codable, Hashable {
    var id = UUID()
    var sectionID: UUID
    var label: String
    /// -1 means repeat until Continue, otherwise 1...16.
    var repeatCount: Int = 1
    var preRollBars: Int = 0
    var guideEnabled: Bool = true
}

struct StageNativeGuideEvent: Identifiable, Codable, Hashable {
    var id = UUID()
    var key: String
    var kind: String
    var language: String
    var cueTime: TimeInterval
    var confidence: Double
}

struct StageNativeGuideAnalysis: Codable, Hashable {
    var detectedLanguage: String?
    var outputLanguage: String?
    var events: [StageNativeGuideEvent]
    var analyzedAt: Date = Date()
}

struct StageSong: Identifiable, Codable, Hashable {
    var id = UUID()
    var title: String
    var artist: String = ""
    var bpm: Double = 120
    var musicalKey: String = "C"
    var timeSignature: String = "4/4"
    var duration: TimeInterval = 0
    var tracks: [StageTrack] = []
    var sections: [StageSection] = []
    var notes: String = ""
    var gridOffsetMs: Double? = nil
    var arrangement: [StageArrangementNode]? = nil
    var guideSource: StageGuideSource? = nil
    var nativeGuideLanguage: String? = nil
    var nativeGuideAnalysis: StageNativeGuideAnalysis? = nil

    var beatsPerBar: Int {
        Int(timeSignature.split(separator: "/").first ?? "4") ?? 4
    }
    var resolvedGridOffsetMs: Double { min(max(gridOffsetMs ?? 0, 0), 60_000) }
    var resolvedGuideSource: StageGuideSource { guideSource ?? .original }
    var resolvedNativeGuideLanguage: String { nativeGuideLanguage ?? "auto" }

    var resolvedArrangement: [StageArrangementNode] {
        if let arrangement, !arrangement.isEmpty {
            let valid = arrangement.filter { node in sections.contains(where: { $0.id == node.sectionID }) }
            if !valid.isEmpty { return valid }
        }
        return sections.sorted(by: { $0.start < $1.start }).map {
            StageArrangementNode(sectionID: $0.id, label: $0.name)
        }
    }
}

struct StageSetlist: Identifiable, Codable, Hashable {
    var id = UUID()
    var name: String
    var songIDs: [UUID]
}

struct StagePreferences: Codable, Equatable {
    var setupComplete = false
    var liveMode = true
    var performanceLock = false
    var clickEnabled = true
    var guideEnabled = true
    var countInBars = 1
    var guideLanguage = "auto"
    var clickSubdivision: StageClickSubdivision? = nil
    var guideSource: StageGuideSource? = nil
    var preferredOutputBusCount: Int? = nil
    var midiClockOutputEnabled: Bool? = nil

    var resolvedClickSubdivision: StageClickSubdivision { clickSubdivision ?? .quarter }
    var resolvedGuideSource: StageGuideSource { guideSource ?? .original }
    var resolvedOutputBusCount: Int { min(max(preferredOutputBusCount ?? 1, 1), 4) }
    var resolvedMidiClockOutputEnabled: Bool { midiClockOutputEnabled ?? false }
}

enum StageMidiAction: Codable, Hashable, Identifiable {
    case playPause
    case stop
    case stopAll
    case nextSong
    case previousSong
    case clickToggle
    case guideToggle
    case section(UUID)
    case trackMute(UUID)
    case trackSolo(UUID)

    var id: String {
        switch self {
        case .playPause: "playPause"
        case .stop: "stop"
        case .stopAll: "stopAll"
        case .nextSong: "nextSong"
        case .previousSong: "previousSong"
        case .clickToggle: "clickToggle"
        case .guideToggle: "guideToggle"
        case .section(let id): "section:\(id.uuidString)"
        case .trackMute(let id): "mute:\(id.uuidString)"
        case .trackSolo(let id): "solo:\(id.uuidString)"
        }
    }
}

struct StageMidiBinding: Identifiable, Codable, Hashable {
    enum Kind: String, Codable { case note, controlChange, programChange }
    var id = UUID()
    var deviceName: String?
    var kind: Kind
    var channel: UInt8
    var number: UInt8
    var action: StageMidiAction
}
