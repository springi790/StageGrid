import SwiftUI
import UniformTypeIdentifiers

struct LibraryView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var library: LibraryStore
    @State private var audioImporterOpen = false
    @State private var zipImporterOpen = false
    @State private var query = ""
    @State private var importError: String?

    private var filtered: [StageSong] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !q.isEmpty else { return library.songs }
        return library.songs.filter {
            $0.title.lowercased().contains(q) || $0.artist.lowercased().contains(q) || $0.musicalKey.lowercased().contains(q)
        }
    }

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 14) {
                header
                if library.songs.isEmpty {
                    ContentUnavailableView {
                        Label("Tu biblioteca está vacía", systemImage: "waveform.badge.plus")
                    } description: {
                        Text("Importa un ZIP de stems o selecciona varios archivos de audio. StageGrid los copiará a su biblioteca local.")
                    } actions: {
                        Button("Importar ZIP") { zipImporterOpen = true }.buttonStyle(.borderedProminent)
                        Button("Seleccionar audios") { audioImporterOpen = true }.buttonStyle(.bordered)
                    }
                    .padding(.vertical, 40)
                } else if filtered.isEmpty {
                    ContentUnavailableView.search(text: query)
                        .padding(.vertical, 40)
                } else {
                    ForEach(filtered) { song in SongLibraryCard(song: song) }
                }
            }
            .padding(16)
        }
        .background(Color.sgCanvas)
        .navigationTitle("Biblioteca")
        .searchable(text: $query, prompt: "Título, artista o tonalidad")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Button { zipImporterOpen = true } label: { Label("Importar ZIP", systemImage: "archivebox") }
                    Button { audioImporterOpen = true } label: { Label("Seleccionar audios", systemImage: "waveform.badge.plus") }
                } label: { Label("Importar", systemImage: "plus") }
            }
        }
        .fileImporter(isPresented: $audioImporterOpen, allowedContentTypes: [.audio, .wav], allowsMultipleSelection: true) { result in
            switch result {
            case .success(let urls):
                do { model.load(try library.importAudioFiles(urls)) }
                catch { importError = error.localizedDescription }
            case .failure(let error): importError = error.localizedDescription
            }
        }
        .fileImporter(isPresented: $zipImporterOpen, allowedContentTypes: [.zip], allowsMultipleSelection: false) { result in
            switch result {
            case .success(let urls):
                guard let url = urls.first else { return }
                do { model.load(try library.importZip(url)) }
                catch { importError = error.localizedDescription }
            case .failure(let error): importError = error.localizedDescription
            }
        }
        .alert("No se pudo importar", isPresented: Binding(get: { importError != nil }, set: { if !$0 { importError = nil } })) {
            Button("Cerrar", role: .cancel) { importError = nil }
        } message: { Text(importError ?? "") }
    }

    private var header: some View {
        HStack(spacing: 14) {
            StageGridMark().frame(width: 58, height: 58)
            VStack(alignment: .leading, spacing: 3) {
                Text("STAGEGRID").font(.caption.bold()).foregroundStyle(Color.sgBlue).tracking(1.4)
                Text("Multitracks locales").font(.title2.bold())
                Text("\(library.songs.count) canción(es) · iPhone / iPad").foregroundStyle(.secondary)
            }
            Spacer()
        }
        .padding(16)
        .background(Color.sgSurface, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
    }
}

private struct SongLibraryCard: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var library: LibraryStore
    let song: StageSong
    @State private var editing = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text(song.title).font(.title3.bold()).lineLimit(1)
                    Text(song.artist.isEmpty ? "Sin artista" : song.artist).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer()
                Text("READY").font(.caption2.bold()).foregroundStyle(Color.sgMint)
                    .padding(.horizontal, 9).padding(.vertical, 5).background(Color.sgMint.opacity(0.12), in: Capsule())
            }

            HStack(spacing: 22) {
                metric("BPM", String(format: "%.1f", song.bpm).replacingOccurrences(of: ".0", with: ""))
                metric("KEY", song.musicalKey)
                metric("STEMS", "\(song.tracks.count)")
                metric("TIME", stageClock(song.duration))
            }

            HStack {
                Button { model.load(song) } label: { Label("Cargar", systemImage: "play.fill") }.buttonStyle(.borderedProminent)
                Button("Editar") { editing = true }.buttonStyle(.bordered)
                Spacer()
                Menu {
                    Button(role: .destructive) { library.delete(song) } label: { Label("Eliminar", systemImage: "trash") }
                } label: { Image(systemName: "ellipsis.circle") }
            }
        }
        .padding(16)
        .background(Color.sgSurface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 20).stroke(Color.white.opacity(0.07)))
        .sheet(isPresented: $editing) { SongMetadataEditor(song: song) }
    }

    private func metric(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption2).foregroundStyle(.secondary)
            Text(value).font(.headline).foregroundStyle(Color.sgBlue)
        }
    }
}

private struct SongMetadataEditor: View {
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let song: StageSong
    @State private var title: String
    @State private var artist: String
    @State private var bpm: String
    @State private var key: String
    @State private var signature: String
    @State private var gridOffset: String
    @State private var notes: String

    init(song: StageSong) {
        self.song = song
        _title = State(initialValue: song.title)
        _artist = State(initialValue: song.artist)
        _bpm = State(initialValue: String(format: "%.2f", song.bpm).replacingOccurrences(of: ".00", with: ""))
        _key = State(initialValue: song.musicalKey)
        _signature = State(initialValue: song.timeSignature)
        _gridOffset = State(initialValue: String(Int(song.resolvedGridOffsetMs)))
        _notes = State(initialValue: song.notes)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Canción") {
                    TextField("Título", text: $title)
                    TextField("Artista", text: $artist)
                    TextField("BPM", text: $bpm).keyboardType(.decimalPad)
                    TextField("Tonalidad", text: $key)
                    TextField("Compás", text: $signature)
                    TextField("Grid offset (ms)", text: $gridOffset).keyboardType(.numberPad)
                    TextField("Notas", text: $notes, axis: .vertical)
                }
            }
            .navigationTitle("Editar canción")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Guardar") {
                        var updated = song
                        updated.title = title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? song.title : title.trimmingCharacters(in: .whitespacesAndNewlines)
                        updated.artist = artist.trimmingCharacters(in: .whitespacesAndNewlines)
                        updated.bpm = min(max(Double(bpm.replacingOccurrences(of: ",", with: ".")) ?? song.bpm, 20), 400)
                        updated.musicalKey = key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? song.musicalKey : key.trimmingCharacters(in: .whitespacesAndNewlines)
                        updated.timeSignature = signature.contains("/") ? signature : song.timeSignature
                        updated.gridOffsetMs = min(max(Double(gridOffset) ?? song.resolvedGridOffsetMs, 0), 60_000)
                        updated.notes = notes
                        library.update(updated)
                        if model.audio.song?.id == song.id { model.load(updated) }
                        dismiss()
                    }
                }
            }
        }
    }
}

func stageClock(_ seconds: TimeInterval) -> String {
    let total = max(0, Int(seconds.rounded()))
    return String(format: "%d:%02d", total / 60, total % 60)
}
