import SwiftUI

struct SetlistsView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var library: LibraryStore
    @State private var creating = false
    @State private var newName = ""

    var body: some View {
        List {
            if library.setlists.isEmpty {
                Section {
                    ContentUnavailableView {
                        Label("Sin setlists", systemImage: "list.bullet.rectangle")
                    } description: {
                        Text("Crea un setlist y agrega las canciones que usarás en vivo.")
                    } actions: {
                        Button("Crear setlist") { creating = true }
                    }
                }
            } else {
                ForEach(library.setlists) { setlist in
                    NavigationLink {
                        SetlistDetailView(setlistID: setlist.id)
                    } label: {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(setlist.name).font(.headline)
                            Text("\(setlist.songIDs.count) canción(es)").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(Color.sgCanvas)
        .navigationTitle("Setlists")
        .toolbar { Button { creating = true } label: { Image(systemName: "plus") } }
        .alert("Nuevo setlist", isPresented: $creating) {
            TextField("Nombre", text: $newName)
            Button("Cancelar", role: .cancel) { newName = "" }
            Button("Crear") {
                library.createSetlist(name: newName)
                newName = ""
            }
        }
    }
}

private struct SetlistDetailView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var library: LibraryStore
    let setlistID: UUID
    @State private var adding = false

    private var setlist: StageSetlist? { library.setlists.first { $0.id == setlistID } }
    private var songs: [StageSong] {
        guard let setlist else { return [] }
        return setlist.songIDs.compactMap { id in library.songs.first(where: { $0.id == id }) }
    }

    var body: some View {
        List {
            if songs.isEmpty {
                Text("Este setlist todavía no tiene canciones.").foregroundStyle(.secondary)
            }
            ForEach(Array(songs.enumerated()), id: \.offset) { index, song in
                Button {
                    model.load(song)
                } label: {
                    HStack {
                        Text("\(index + 1)").font(.caption.bold()).foregroundStyle(.secondary).frame(width: 22)
                        VStack(alignment: .leading) {
                            Text(song.title).font(.headline).foregroundStyle(.primary)
                            Text("\(Int(song.bpm)) BPM · \(song.musicalKey)").font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Image(systemName: "play.fill").foregroundStyle(Color.sgBlue)
                    }
                }
                .buttonStyle(.plain)
            }
            .onMove { from, to in
                guard var list = setlist else { return }
                list.songIDs.move(fromOffsets: from, toOffset: to)
                library.updateSetlist(list)
            }
            .onDelete { offsets in
                guard var list = setlist else { return }
                list.songIDs.remove(atOffsets: offsets)
                library.updateSetlist(list)
            }
        }
        .scrollContentBackground(.hidden)
        .background(Color.sgCanvas)
        .navigationTitle(setlist?.name ?? "Setlist")
        .toolbar {
            EditButton()
            Button { adding = true } label: { Image(systemName: "plus") }
        }
        .sheet(isPresented: $adding) {
            NavigationStack {
                List(library.songs) { song in
                    Button {
                        guard var list = setlist else { return }
                        if !list.songIDs.contains(song.id) { list.songIDs.append(song.id) }
                        library.updateSetlist(list)
                        adding = false
                    } label: {
                        VStack(alignment: .leading) {
                            Text(song.title)
                            Text(song.artist).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                .navigationTitle("Añadir canción")
                .toolbar { Button("Cerrar") { adding = false } }
            }
        }
    }
}
