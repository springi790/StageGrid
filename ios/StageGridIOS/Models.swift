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

struct StageTrack: Identifiable, Codable, Hashable {
    var id = UUID()
    var name: String
    var kind: TrackKind
    var relativePath: String
    var volume: Float = 1
    var pan: Float = 0
    var muted = false
    var solo = false
}

struct StageSection: Identifiable, Codable, Hashable {
    var id = UUID()
    var name: String
    var start: TimeInterval
    var end: TimeInterval
    var addCount = false
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

    var beatsPerBar: Int {
        Int(timeSignature.split(separator: "/").first ?? "4") ?? 4
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
}
