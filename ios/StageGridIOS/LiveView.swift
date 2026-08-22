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
                    VStack(spacing: 12) {
                        songHeader(song)
                        if model.setlistLiveState.active { setlistLivePanel }
                        progressPanel(song)
                        dspPanel(song)
                        nowNextPanel(song)
                        sectionRail(song)
                        performanceControls(song)
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
                .onChange(of: audio.tempoRatio) { _, _ in syncDSPFields(song) }
            } else {
                EmptyStageView(title: "No hay canción cargada", subtitle: "Carga una canción desde Biblioteca para abrir Live.", systemImage: "play.square.stack")
                    .background(Color.sgCanvas)
            }
        }
    }

    private func songHeader(_ song: StageSong) -> some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(song.title).font(.title.bold()).lineLimit(1)
                Text(song.artist.isEmpty ? "StageGrid" : song.artist).foregroundStyle(.secondary).lineLimit(1)
                HStack(spacing: 6) {
                    statusPill(audio.isPlaying ? "PLAYING" : "READY", color: audio.isPlaying ? .sgMint : .secondary)
                    if audio.crossfadeInProgress { statusPill("CROSSFADE", color: .orange) }
                    if model.arrangementState.active { statusPill("ARRANGEMENT", color: .purple) }
                    if audio.guideSource == .cue { statusPill("CUE AUTO", color: .sgBlue) }
                }
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 3) {
                Text("\(Int((song.bpm * Double(audio.tempoRatio)).rounded())) BPM")
                    .font(.headline.bold()).foregroundStyle(Color.sgBlue)
                Text(currentBarBeat(song)).font(.caption).foregroundStyle(.secondary)
                Text("\(audio.outputChannelCount)ch · \(audio.outputName)").font(.caption2).foregroundStyle(.secondary).lineLimit(1)
            }
        }
        .padding(16)
        .background(Color.sgSurface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private var setlistLivePanel: some View {
        let list = model.setlistLiveState.setlistID.flatMap { id in model.library.setlists.first(where: { $0.id == id }) }
        let songs = list?.songIDs.compactMap { id in model.library.songs.first(where: { $0.id == id }) } ?? []
        let index = model.setlistLiveState.currentIndex
        return VStack(spacing: 9) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("SETLIST LIVE").font(.caption.bold()).foregroundStyle(Color.sgMint)
                    Text(list?.name ?? "Setlist").font(.headline.bold())
                }
                Spacer()
                Text(index >= 0 ? "\(index + 1)/\(songs.count)" : "—").font(.caption.bold()).foregroundStyle(.secondary)
            }
            HStack {
                Button { model.setlistPrevious() } label: { Label("Anterior", systemImage: "backward.end.fill") }
                    .disabled(index <= 0)
                Spacer()
                VStack(spacing: 1) {
                    Text("SIGUIENTE").font(.caption2).foregroundStyle(.secondary)
                    Text(songs.indices.contains(index + 1) ? songs[index + 1].title : "—").font(.subheadline.bold()).lineLimit(1)
                    if model.setlistLiveState.nextReady { Text("PRELOADED").font(.caption2.bold()).foregroundStyle(Color.sgMint) }
                }
                Spacer()
                Button { model.setlistNext() } label: { Label("Siguiente", systemImage: "forward.end.fill") }
                    .disabled(!songs.indices.contains(index + 1) || audio.crossfadeInProgress)
            }
            .buttonStyle(.bordered)
            if let error = model.setlistLiveState.error { Text(error).font(.caption).foregroundStyle(.orange) }
        }
        .padding(14)
        .background(Color.sgMint.opacity(0.07), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(Color.sgMint.opacity(0.25)))
    }

    private func progressPanel(_ song: StageSong) -> some View {
        VStack(spacing: 10) {
            StageWaveformView(
                song: song,
                position: audio.position,
                currentSectionID: currentSection(song)?.id,
                onSeek: { audio.seek(to: $0, autoPlay: audio.isPlaying) }
            )
            HStack {
                Text(stageClock(audio.position)).font(.caption.monospacedDigit()).foregroundStyle(Color.sgBlue)
                Spacer()
                Text(stageClock(audio.duration)).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
            }
        }
        .padding(12)
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
                        .onChange(of: bpmText) { _, newValue in
                            if newValue.count > 6 { bpmText = String(newValue.prefix(6)) }
                        }
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
        .padding(14)
        .background(Color.sgSurface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private func nowNextPanel(_ song: StageSong) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 3) {
                Text("AHORA").font(.caption2).foregroundStyle(.secondary)
                Text(model.arrangementState.activeNodeID.flatMap { id in song.resolvedArrangement.first(where: { $0.id == id })?.label } ?? currentSection(song)?.name ?? "—")
                    .font(.headline.bold()).foregroundStyle(Color.sgBlue)
                if model.arrangementState.active, model.arrangementState.iteration > 1 {
                    Text("Vuelta \(model.arrangementState.iteration)").font(.caption2).foregroundStyle(.secondary)
                }
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 3) {
                Text("SIGUIENTE").font(.caption2).foregroundStyle(.secondary)
                Text(queuedDestinationName(song) ?? nextSection(song)?.name ?? "—").font(.headline.bold()).foregroundStyle(Color.orange)
            }
        }
        .padding(14)
        .background(Color.sgSurface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private func sectionRail(_ song: StageSong) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(model.arrangementState.active ? "ARRANGEMENT" : "SECCIONES").font(.caption.bold()).foregroundStyle(.secondary)
                Spacer()
                if model.queuedSectionID != nil { Text("TRANSICIÓN PREPARADA").font(.caption2.bold()).foregroundStyle(.orange) }
            }
            if model.arrangementState.active {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(song.resolvedArrangement) { node in
                            Button { model.selectArrangementNode(node.id) } label: {
                                sectionButton(
                                    label: node.label,
                                    supporting: model.arrangementState.queuedNodeID == node.id ? "EN COLA" : node.repeatCount < 0 ? "∞" : node.repeatCount > 1 ? "\(node.repeatCount)x" : nil,
                                    current: model.arrangementState.activeNodeID == node.id,
                                    queued: model.arrangementState.queuedNodeID == node.id
                                )
                            }.buttonStyle(.plain)
                        }
                    }
                }
            } else if song.sections.isEmpty {
                Text("Añade secciones para saltar entre partes de la canción.").foregroundStyle(.secondary)
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(song.sections.sorted(by: { $0.start < $1.start })) { section in
                            let current = currentSection(song)?.id == section.id
                            let queued = model.queuedSectionID == section.id
                            Button { model.selectSection(section) } label: {
                                sectionButton(label: section.name, supporting: queued ? "EN COLA" : (current ? "ACTUAL" : stageClock(section.start)), current: current, queued: queued)
                            }.buttonStyle(.plain)
                        }
                    }
                }
            }
        }
        .padding(14)
        .background(Color.sgSurface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private func performanceControls(_ song: StageSong) -> some View {
        VStack(spacing: 9) {
            HStack(spacing: 9) {
                Button {
                    audio.clickEnabled.toggle()
                    model.updatePreferences { $0.clickEnabled = audio.clickEnabled }
                } label: { Label(audio.clickEnabled ? "Click On" : "Click Off", systemImage: "metronome") }
                    .buttonStyle(.bordered).tint(audio.clickEnabled ? .sgMint : .secondary)
                Button {
                    audio.guideEnabled.toggle()
                    model.updatePreferences { $0.guideEnabled = audio.guideEnabled }
                } label: { Label(audio.guideEnabled ? "Guía On" : "Guía Off", systemImage: "speaker.wave.2") }
                    .buttonStyle(.bordered).tint(audio.guideEnabled ? .sgBlue : .secondary)
                Spacer()
                Button(model.arrangementState.active ? "Stop Arrangement" : "Arrangement") {
                    model.arrangementState.active ? model.stopArrangement() : model.startArrangement()
                }
                .buttonStyle(.bordered)
            }
            if model.arrangementState.active,
               let node = song.resolvedArrangement.first(where: { $0.id == model.arrangementState.activeNodeID }), node.repeatCount < 0 {
                Button("Salir del loop en el próximo límite") { model.exitArrangementLoop() }
                    .buttonStyle(.borderedProminent).tint(.orange).frame(maxWidth: .infinity)
            }
        }
        .padding(12)
        .background(Color.sgSurface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private func transport(_ song: StageSong) -> some View {
        VStack(spacing: 8) {
            if audio.countInRemaining > 0 {
                Text("COUNT-IN · \(Int(ceil(audio.countInRemaining)))")
                    .font(.title2.bold().monospacedDigit()).foregroundStyle(.orange)
            } else {
                ProgressView(value: min(audio.position, audio.duration), total: max(0.1, audio.duration)).tint(.sgBlue)
            }
            HStack(spacing: 9) {
                Button { audio.isPlaying ? audio.pause() : audio.play() } label: {
                    Label(audio.isPlaying ? "Pausa" : "Play", systemImage: audio.isPlaying ? "pause.fill" : "play.fill")
                        .frame(maxWidth: .infinity).frame(height: 46)
                }
                .buttonStyle(.borderedProminent).tint(.sgBlue)
                .disabled(audio.crossfadeInProgress || audio.countInRemaining > 0)

                Button {
                    model.cancelQueuedSection()
                    audio.stop(unload: false)
                } label: { Label("Stop", systemImage: "stop.fill").frame(maxWidth: .infinity).frame(height: 46) }
                    .buttonStyle(.bordered)

                Button { audio.stopAll() } label: { Image(systemName: "exclamationmark.octagon.fill").frame(width: 38, height: 46) }
                    .buttonStyle(.borderedProminent).tint(.red)
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
        .background(.ultraThinMaterial)
    }

    private func sectionButton(label: String, supporting: String?, current: Bool, queued: Bool) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(.headline.bold()).lineLimit(1)
            if let supporting {
                Text(supporting).font(.caption2.bold()).foregroundStyle(queued ? Color.orange : current ? Color.sgMint : .secondary)
            }
        }
        .frame(minWidth: 112, minHeight: 48, alignment: .leading)
        .padding(12)
        .background(current ? Color.sgBlue.opacity(0.18) : queued ? Color.orange.opacity(0.14) : Color.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 15))
        .overlay(RoundedRectangle(cornerRadius: 15).stroke(current ? Color.sgBlue : queued ? Color.orange : Color.white.opacity(0.08), lineWidth: current || queued ? 2 : 1))
        .animation(.easeOut(duration: 0.16), value: current)
        .animation(.easeOut(duration: 0.16), value: queued)
    }

    private func statusPill(_ text: String, color: Color) -> some View {
        Text(text).font(.system(size: 9, weight: .black)).foregroundStyle(color)
            .padding(.horizontal, 7).padding(.vertical, 4).background(color.opacity(0.12), in: Capsule())
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

    private func queuedDestinationName(_ song: StageSong) -> String? {
        if let id = model.arrangementState.queuedNodeID { return song.resolvedArrangement.first(where: { $0.id == id })?.label }
        if let id = model.queuedSectionID { return song.sections.first(where: { $0.id == id })?.name }
        return nil
    }

    private func currentBarBeat(_ song: StageSong) -> String {
        let beatDuration = 60.0 / max(20, song.bpm)
        let source = max(0, audio.position - song.resolvedGridOffsetMs / 1000)
        let beatIndex = max(0, Int(source / beatDuration))
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
                            let colors = ["#5B8CFF", "#2FBF9F", "#9C6CFF", "#F39C55", "#E85D75", "#4FB6E9"]
                            let start = audio.position
                            song.sections.append(StageSection(name: trimmed, start: start, end: song.duration, addCount: count, colorHex: colors[song.sections.count % colors.count]))
                            song.arrangement = nil
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
