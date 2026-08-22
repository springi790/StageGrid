import Foundation
import AVFoundation
import ZIPFoundation

@MainActor
final class LibraryStore: ObservableObject {
    @Published private(set) var songs: [StageSong] = []
    @Published private(set) var setlists: [StageSetlist] = []

    private let fm = FileManager.default
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()
    private let audioExtensions = Set(["wav", "mp3", "m4a", "aac", "caf", "aif", "aiff", "flac", "ogg"])

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

    func songFolder(_ song: StageSong) -> URL {
        rootURL.appendingPathComponent(song.id.uuidString, isDirectory: true)
    }

    func trackURL(song: StageSong, track: StageTrack) -> URL {
        songFolder(song).appendingPathComponent(track.relativePath)
    }

    func importZip(_ source: URL) throws -> StageSong {
        let accessed = source.startAccessingSecurityScopedResource()
        defer { if accessed { source.stopAccessingSecurityScopedResource() } }
        let staging = fm.temporaryDirectory.appendingPathComponent("stagegrid-import-\(UUID().uuidString)", isDirectory: true)
        defer { try? fm.removeItem(at: staging) }
        try fm.createDirectory(at: staging, withIntermediateDirectories: true)
        try fm.unzipItem(at: source, to: staging)
        guard let enumerator = fm.enumerator(at: staging, includingPropertiesForKeys: [.isRegularFileKey]) else {
            throw ImportError.noFiles
        }
        var audio: [URL] = []
        while let url = enumerator.nextObject() as? URL {
            let values = try? url.resourceValues(forKeys: [.isRegularFileKey])
            guard values?.isRegularFile == true else { continue }
            let lowerPath = url.path.lowercased()
            guard !lowerPath.contains("/__macosx/"), audioExtensions.contains(url.pathExtension.lowercased()) else { continue }
            audio.append(url)
        }
        audio.sort { $0.lastPathComponent.localizedStandardCompare($1.lastPathComponent) == .orderedAscending }
        guard !audio.isEmpty else { throw ImportError.noFiles }
        return try importAudioFiles(audio, suggestedTitle: source.deletingPathExtension().lastPathComponent)
    }

    func importAudioFiles(_ urls: [URL], suggestedTitle: String? = nil) throws -> StageSong {
        guard !urls.isEmpty else { throw ImportError.noFiles }
        let songID = UUID()
        let songFolder = rootURL.appendingPathComponent(songID.uuidString, isDirectory: true)
        try fm.createDirectory(at: songFolder, withIntermediateDirectories: true)

        do {
            var tracks: [StageTrack] = []
            var duration: TimeInterval = 0
            var titleCandidate = suggestedTitle ?? urls[0].deletingPathExtension().lastPathComponent

            for (index, source) in urls.enumerated() {
                let accessed = source.startAccessingSecurityScopedResource()
                defer { if accessed { source.stopAccessingSecurityScopedResource() } }

                let cleanName = source.lastPathComponent
                    .replacingOccurrences(of: "/", with: "-")
                    .replacingOccurrences(of: ":", with: "-")
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

            let removableSuffixes = ["drums", "bass", "guitar", "keys", "vocals", "click", "guide", "cue", "tracks", "stems"]
            for suffix in removableSuffixes {
                titleCandidate = titleCandidate.replacingOccurrences(of: " - \(suffix)", with: "", options: .caseInsensitive)
                titleCandidate = titleCandidate.replacingOccurrences(of: "_\(suffix)", with: "", options: .caseInsensitive)
            }

            let defaultSection = StageSection(name: "Song", start: 0, end: duration, addCount: false, colorHex: "#5B8CFF")
            let song = StageSong(
                id: songID,
                title: titleCandidate.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "Nueva canción" : titleCandidate.trimmingCharacters(in: .whitespacesAndNewlines),
                duration: duration,
                tracks: tracks,
                sections: [defaultSection]
            )
            songs.insert(song, at: 0)
            try save()
            return song
        } catch {
            try? fm.removeItem(at: songFolder)
            throw error
        }
    }

    func update(_ song: StageSong) {
        guard let index = songs.firstIndex(where: { $0.id == song.id }) else { return }
        songs[index] = reconcile(song)
        try? save()
    }

    func delete(_ song: StageSong) {
        songs.removeAll { $0.id == song.id }
        setlists = setlists.map { list in
            var copy = list
            copy.songIDs.removeAll { $0 == song.id }
            return copy
        }
        try? fm.removeItem(at: songFolder(song))
        try? save()
    }

    func createSetlist(name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        setlists.append(StageSetlist(name: trimmed, songIDs: []))
        try? save()
    }

    func deleteSetlist(_ id: UUID) {
        setlists.removeAll { $0.id == id }
        try? save()
    }

    func updateSetlist(_ setlist: StageSetlist) {
        if let index = setlists.firstIndex(where: { $0.id == setlist.id }) {
            setlists[index] = setlist
            try? save()
        }
    }

    func save() throws {
        try fm.createDirectory(at: rootURL, withIntermediateDirectories: true)
        try encoder.encode(songs).write(to: catalogURL, options: .atomic)
        try encoder.encode(setlists).write(to: setlistsURL, options: .atomic)
    }

    func reload() {
        songs = []
        setlists = []
        load()
    }

    private func load() {
        if let data = try? Data(contentsOf: catalogURL), let decoded = try? decoder.decode([StageSong].self, from: data) {
            songs = decoded.map(reconcile)
        }
        if let data = try? Data(contentsOf: setlistsURL), let decoded = try? decoder.decode([StageSetlist].self, from: data) {
            setlists = decoded
        }
    }

    private func reconcile(_ input: StageSong) -> StageSong {
        var song = input
        song.sections.sort { $0.start < $1.start }
        for index in song.sections.indices {
            song.sections[index].start = min(max(0, song.sections[index].start), song.duration)
            if index + 1 < song.sections.count {
                song.sections[index].end = max(song.sections[index].start, min(song.duration, song.sections[index + 1].start))
            } else {
                song.sections[index].end = max(song.sections[index].start, song.duration)
            }
        }
        if let nodes = song.arrangement {
            var reconciled = nodes.filter { node in song.sections.contains(where: { $0.id == node.sectionID }) }
            for section in song.sections where !reconciled.contains(where: { $0.sectionID == section.id }) {
                reconciled.append(StageArrangementNode(sectionID: section.id, label: section.name))
            }
            song.arrangement = reconciled
        }
        return song
    }

    enum ImportError: LocalizedError {
        case noFiles
        var errorDescription: String? { "No se encontraron archivos de audio compatibles." }
    }
}
