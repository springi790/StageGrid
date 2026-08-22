import SwiftUI
import UniformTypeIdentifiers

struct LibraryView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var library: LibraryStore
    @State private var importerOpen = false
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
                        Text("Importa los WAV/stems de una canción. StageGrid los copiará a su biblioteca local.")
                    } actions: {
                        Button("Importar stems") { importerOpen = true }
                            .buttonStyle(.borderedProminent)
                    }
                    .padding(.vertical, 40)
                } else {
                    ForEach(filtered) { song in
                        SongLibraryCard(song: song)
                    }
                }
            }
            .padding(16)
        }
        .background(Color.sgCanvas)
        .navigationTitle("Biblioteca")
        .searchable(text: $query, prompt: "Título, artista o tonalidad")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { importerOpen = true } label: { Label("Importar", systemImage: "plus") }
            }
        }
        .fileImporter(
            isPresented: $importerOpen,
            allowedContentTypes: [.audio, .wav],
            allowsMultipleSelection: true
        ) { result in
            switch result {
            case .success(let urls):
                do {
                    let song = try library.importAudioFiles(urls)
                    model.load(song)
                } catch {
                    importError = error.localizedDescription
                }
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

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text(song.title).font(.title3.bold()).lineLimit(1)
                    Text(song.artist.isEmpty ? "Sin artista" : song.artist).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer()
                Text("READY")
                    .font(.caption2.bold())
                    .foregroundStyle(Color.sgMint)
                    .padding(.horizontal, 9).padding(.vertical, 5)
                    .background(Color.sgMint.opacity(0.12), in: Capsule())
            }

            HStack(spacing: 22) {
                metric("BPM", String(format: "%.1f", song.bpm).replacingOccurrences(of: ".0", with: ""))
                metric("KEY", song.musicalKey)
                metric("STEMS", "\(song.tracks.count)")
                metric("TIME", stageClock(song.duration))
            }

            HStack {
                Button { model.load(song) } label: { Label("Cargar", systemImage: "play.fill") }
                    .buttonStyle(.borderedProminent)
                Spacer()
                Menu {
                    Button(role: .destructive) { library.delete(song) } label: { Label("Eliminar", systemImage: "trash") }
                } label: { Image(systemName: "ellipsis.circle") }
            }
        }
        .padding(16)
        .background(Color.sgSurface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 20).stroke(Color.white.opacity(0.07)))
    }

    private func metric(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption2).foregroundStyle(.secondary)
            Text(value).font(.headline).foregroundStyle(Color.sgBlue)
        }
    }
}

func stageClock(_ seconds: TimeInterval) -> String {
    let total = max(0, Int(seconds.rounded()))
    return String(format: "%d:%02d", total / 60, total % 60)
}
