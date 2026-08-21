import Foundation
import AVFoundation

@MainActor
final class LibraryStore: ObservableObject {
    @Published private(set) var songs: [StageSong] = []
    @Published private(set) var setlists: [StageSetlist] = []

    private let fm = FileManager.default
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init() {
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        load()
    }

    var rootURL: URL {
        let base = fm.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let root = base.appendingPathComponent("StageGridLibrary", isDirectory: true)
        try? fm.createDirectory(at: root, withIntermediateDirectories: true)
        return root
    }

    private var catalogURL: URL { rootURL.appendingPathComponent("library.json") }
    private var setlistsURL: URL { rootURL.appendingPathComponent("setlists.json") }

    func trackURL(song: StageSong, track: StageTrack) -> URL {
        rootURL.appendingPathComponent(song.id.uuidString, isDirectory: true)
            .appendingPathComponent(track.relativePath)
    }

    func importAudioFiles(_ urls: [URL]) throws -> StageSong {
        guard !urls.isEmpty else { throw ImportError.noFiles }
        let songID = UUID()
        let songFolder = rootURL.appendingPathComponent(songID.uuidString, isDirectory: true)
        try fm.createDirectory(at: songFolder, withIntermediateDirectories: true)

        var tracks: [StageTrack] = []
        var duration: TimeInterval = 0
        var titleCandidate = urls[0].deletingPathExtension().lastPathComponent

        for (index, source) in urls.enumerated() {
            let accessed = source.startAccessingSecurityScopedResource()
            defer { if accessed { source.stopAccessingSecurityScopedResource() } }

            let cleanName = source.lastPathComponent.replacingOccurrences(of: "/", with: "-")
            let destination = songFolder.appendingPathComponent(String(format: "%02d-%@", index, cleanName))
            if fm.fileExists(atPath: destination.path) { try fm.removeItem(at: destination) }
            try fm.copyItem(at: source, to: destination)

            let audioFile = try AVAudioFile(forReading: destination)
            if audioFile.fileFormat.sampleRate > 0 {
                duration = max(duration, Double(audioFile.length) / audioFile.fileFormat.sampleRate)
            }
            tracks.append(
                StageTrack(
                    name: source.deletingPathExtension().lastPathComponent,
                    kind: TrackKind.infer(from: source.lastPathComponent),
                    relativePath: destination.lastPathComponent
                )
            )
        }

        let removableSuffixes = ["drums", "bass", "guitar", "keys", "vocals", "click", "guide", "cue"]
        for suffix in removableSuffixes {
            titleCandidate = titleCandidate.replacingOccurrences(of: " - \(suffix)", with: "", options: .caseInsensitive)
            titleCandidate = titleCandidate.replacingOccurrences(of: "_\(suffix)", with: "", options: .caseInsensitive)
        }

        let song = StageSong(
            id: songID,
            title: titleCandidate.trimmingCharacters(in: .whitespacesAndNewlines),
            duration: duration,
            tracks: tracks,
            sections: [StageSection(name: "Song", start: 0, end: duration)]
        )
        songs.insert(song, at: 0)
        try save()
        return song
    }

    func update(_ song: StageSong) {
        guard let index = songs.firstIndex(where: { $0.id == song.id }) else { return }
        songs[index] = song
        try? save()
    }

    func delete(_ song: StageSong) {
        songs.removeAll { $0.id == song.id }
        setlists = setlists.map { list in
            var copy = list
            copy.songIDs.removeAll { $0 == song.id }
            return copy
        }
        try? fm.removeItem(at: rootURL.appendingPathComponent(song.id.uuidString, isDirectory: true))
        try? save()
    }

    func createSetlist(name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        setlists.append(StageSetlist(name: trimmed, songIDs: []))
        try? save()
    }

    func updateSetlist(_ setlist: StageSetlist) {
        if let index = setlists.firstIndex(where: { $0.id == setlist.id }) {
            setlists[index] = setlist
            try? save()
        }
    }

    func save() throws {
        try encoder.encode(songs).write(to: catalogURL, options: .atomic)
        try encoder.encode(setlists).write(to: setlistsURL, options: .atomic)
    }

    private func load() {
        if let data = try? Data(contentsOf: catalogURL), let decoded = try? decoder.decode([StageSong].self, from: data) {
            songs = decoded
        }
        if let data = try? Data(contentsOf: setlistsURL), let decoded = try? decoder.decode([StageSetlist].self, from: data) {
            setlists = decoded
        }
    }

    enum ImportError: LocalizedError {
        case noFiles
        var errorDescription: String? { "No audio files were selected." }
    }
}
