import SwiftUI

struct LiveView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var audio: StageGridAudioEngine
    @State private var bpmText = ""
    @State private var targetKey = "C"
    @State private var sectionEditor = false

    var body: some View {
        Group {
            if let song = audio.song {
                ScrollView {
                    VStack(spacing: 14) {
                        songHeader(song)
                        progressPanel(song)
                        dspPanel(song)
                        sectionRail(song)
                        statusPanel(song)
                    }
                    .padding(14)
                }
                .safeAreaInset(edge: .bottom) { transport(song) }
                .background(Color.sgCanvas)
                .navigationTitle("Live")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    Button { sectionEditor = true } label: { Label("Sección", systemImage: "plus") }
                }
                .sheet(isPresented: $sectionEditor) { AddSectionSheet() }
                .onAppear { syncDSPFields(song) }
                .onChange(of: song.id) { _, _ in syncDSPFields(song) }
            } else {
                EmptyStageView(title: "No hay canción cargada", subtitle: "Carga una canción desde Biblioteca para abrir Live.", systemImage: "play.square.stack")
                    .background(Color.sgCanvas)
            }
        }
    }

    private func songHeader(_ song: StageSong) -> some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 3) {
                Text(song.title).font(.title.bold()).lineLimit(1)
                Text(song.artist.isEmpty ? "StageGrid" : song.artist).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 3) {
                Text("\(Int((song.bpm * Double(audio.tempoRatio)).rounded())) BPM")
                    .font(.headline.bold()).foregroundStyle(Color.sgBlue)
                Text(currentBarBeat(song)).font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(16)
        .background(Color.sgSurface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private func progressPanel(_ song: StageSong) -> some View {
        VStack(spacing: 10) {
            HStack {
                Text(stageClock(audio.position)).font(.caption.monospacedDigit()).foregroundStyle(Color.sgBlue)
                Spacer()
                Text(stageClock(audio.duration)).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
            }
            Slider(value: Binding(
                get: { audio.duration > 0 ? min(audio.position, audio.duration) : 0 },
                set: { audio.seek(to: $0, autoPlay: audio.isPlaying) }
            ), in: 0...max(0.1, audio.duration))
            .tint(.sgBlue)
        }
        .padding(16)
        .background(Color.sgSurface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private func dspPanel(_ song: StageSong) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 6) {
                Text("BPM").font(.caption.bold()).foregroundStyle(.secondary)
                HStack(spacing: 5) {
                    TextField("\(Int(song.bpm))", text: $bpmText)
                        .keyboardType(.decimalPad)
                        .textFieldStyle(.roundedBorder)
                        .frame(minWidth: 78, maxWidth: 110)
                        .onSubmit { applyBPM(song) }
                    Text("BPM").font(.caption.bold())
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            VStack(alignment: .leading, spacing: 6) {
                Text("TONALIDAD").font(.caption.bold()).foregroundStyle(.secondary)
                Menu {
                    ForEach(keyChoices(for: song.musicalKey), id: \.self) { key in
                        Button(key) {
                            targetKey = key
                            audio.setPitchSemitones(Float(shortestSemitoneDistance(from: song.musicalKey, to: key)))
                        }
                    }
                } label: {
                    HStack {
                        Text(targetKey).font(.headline)
                        Image(systemName: "chevron.up.chevron.down").font(.caption)
                    }
                    .padding(.horizontal, 13).padding(.vertical, 10)
                    .background(Color.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 10))
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(16)
        .background(Color.sgSurface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private func sectionRail(_ song: StageSong) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("SECCIONES").font(.caption.bold()).foregroundStyle(.secondary)
                Spacer()
                if model.queuedSectionID != nil {
                    Button("Cancelar cola") { model.cancelQueuedSection() }.font(.caption)
                }
            }
            if song.sections.isEmpty {
                Text("Añade secciones para saltar entre partes de la canción.").foregroundStyle(.secondary)
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(song.sections.sorted(by: { $0.start < $1.start })) { section in
                            let current = currentSection(song)?.id == section.id
                            let queued = model.queuedSectionID == section.id
                            Button { model.selectSection(section) } label: {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(section.name).font(.headline.bold()).lineLimit(1)
                                    Text(queued ? "EN COLA" : (current ? "ACTUAL" : stageClock(section.start)))
                                        .font(.caption2.bold())
                                        .foregroundStyle(queued ? Color.orange : (current ? Color.sgMint : .secondary))
                                }
                                .frame(minWidth: 112, alignment: .leading)
                                .padding(13)
                                .background(
                                    (current ? Color.sgBlue.opacity(0.18) : queued ? Color.orange.opacity(0.14) : Color.white.opacity(0.05)),
                                    in: RoundedRectangle(cornerRadius: 15)
                                )
                                .overlay(RoundedRectangle(cornerRadius: 15).stroke(current ? Color.sgBlue : queued ? Color.orange : Color.white.opacity(0.08), lineWidth: current || queued ? 2 : 1))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
        }
        .padding(16)
        .background(Color.sgSurface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private func statusPanel(_ song: StageSong) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 3) {
                Text("AHORA").font(.caption2).foregroundStyle(.secondary)
                Text(currentSection(song)?.name ?? "—").font(.headline.bold()).foregroundStyle(Color.sgBlue)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 3) {
                Text("SIGUIENTE").font(.caption2).foregroundStyle(.secondary)
                Text(nextSection(song)?.name ?? "—").font(.headline.bold()).foregroundStyle(Color.orange)
            }
        }
        .padding(16)
        .background(Color.sgSurface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private func transport(_ song: StageSong) -> some View {
        VStack(spacing: 8) {
            ProgressView(value: min(audio.position, audio.duration), total: max(0.1, audio.duration)).tint(.sgBlue)
            HStack(spacing: 10) {
                Button {
                    audio.isPlaying ? audio.pause() : audio.play()
                } label: {
                    Label(audio.isPlaying ? "Pausa" : "Play", systemImage: audio.isPlaying ? "pause.fill" : "play.fill")
                        .frame(maxWidth: .infinity).frame(height: 46)
                }
                .buttonStyle(.borderedProminent).tint(.sgBlue)

                Button {
                    model.cancelQueuedSection()
                    audio.stop(unload: false)
                } label: {
                    Label("Stop", systemImage: "stop.fill").frame(maxWidth: .infinity).frame(height: 46)
                }
                .buttonStyle(.bordered)
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
        .background(.ultraThinMaterial)
    }

    private func syncDSPFields(_ song: StageSong) {
        bpmText = String(format: "%.1f", song.bpm * Double(audio.tempoRatio)).replacingOccurrences(of: ".0", with: "")
        targetKey = transposedKey(base: song.musicalKey, semitones: Int(audio.pitchSemitones.rounded()))
    }

    private func applyBPM(_ song: StageSong) {
        let parsed = Double(bpmText.replacingOccurrences(of: ",", with: ".")) ?? song.bpm
        let target = min(max(parsed, song.bpm * 0.75), song.bpm * 1.5)
        audio.setTempoRatio(Float(target / song.bpm))
        bpmText = String(format: "%.1f", target).replacingOccurrences(of: ".0", with: "")
    }

    private func currentSection(_ song: StageSong) -> StageSection? {
        song.sections.sorted(by: { $0.start < $1.start }).last { audio.position >= $0.start && audio.position < $0.end }
    }

    private func nextSection(_ song: StageSong) -> StageSection? {
        let sorted = song.sections.sorted(by: { $0.start < $1.start })
        guard let current = currentSection(song), let index = sorted.firstIndex(of: current) else { return sorted.first }
        return sorted.indices.contains(index + 1) ? sorted[index + 1] : nil
    }

    private func currentBarBeat(_ song: StageSong) -> String {
        let beatDuration = 60.0 / max(20, song.bpm)
        let beatIndex = max(0, Int(audio.position / beatDuration))
        return "\(beatIndex / max(1, song.beatsPerBar) + 1) · \(beatIndex % max(1, song.beatsPerBar) + 1)"
    }
}

private struct AddSectionSheet: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var audio: StageGridAudioEngine
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var count = true

    var body: some View {
        NavigationStack {
            Form {
                Section("Nueva sección") {
                    TextField("Intro, Verso, Coro…", text: $name)
                    Toggle("Añadir conteo 2, 3, 4", isOn: $count)
                    LabeledContent("Posición", value: stageClock(audio.position))
                }
            }
            .navigationTitle("Añadir sección")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Guardar") {
                        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
                        guard !trimmed.isEmpty else { return }
                        model.saveLoadedSong { song in
                            let start = audio.position
                            song.sections.append(StageSection(name: trimmed, start: start, end: song.duration, addCount: count))
                            song.sections.sort { $0.start < $1.start }
                            for index in song.sections.indices {
                                if index + 1 < song.sections.count { song.sections[index].end = song.sections[index + 1].start }
                                else { song.sections[index].end = song.duration }
                            }
                        }
                        dismiss()
                    }
                }
            }
        }
        .presentationDetents([.medium])
    }
}

private let chromaticKeys = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]

private func normalizedRoot(_ key: String) -> String {
    let raw = key.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "m", with: "", options: [.caseInsensitive, .anchored])
    let flats = ["Db":"C#", "Eb":"D#", "Gb":"F#", "Ab":"G#", "Bb":"A#"]
    return flats[raw] ?? raw
}

private func keyChoices(for base: String) -> [String] {
    let minor = base.trimmingCharacters(in: .whitespacesAndNewlines).lowercased().hasSuffix("m")
    return chromaticKeys.map { minor ? "\($0)m" : $0 }
}

private func keyIndex(_ key: String) -> Int { chromaticKeys.firstIndex(of: normalizedRoot(key)) ?? 0 }

private func shortestSemitoneDistance(from: String, to: String) -> Int {
    var delta = keyIndex(to) - keyIndex(from)
    if delta > 6 { delta -= 12 }
    if delta < -6 { delta += 12 }
    return delta
}

private func transposedKey(base: String, semitones: Int) -> String {
    let minor = base.trimmingCharacters(in: .whitespacesAndNewlines).lowercased().hasSuffix("m")
    let index = (keyIndex(base) + semitones % 12 + 12) % 12
    return chromaticKeys[index] + (minor ? "m" : "")
}
