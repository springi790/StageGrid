import SwiftUI
import UniformTypeIdentifiers

struct AdvancedView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var audio: StageGridAudioEngine
    @State private var guideImporter = false
    @State private var guideError: String?

    var body: some View {
        Group {
            if let song = audio.song {
                List {
                    Section("Secciones") {
                        ForEach(song.sections.sorted(by: { $0.start < $1.start })) { section in
                            VStack(alignment: .leading, spacing: 7) {
                                HStack {
                                    VStack(alignment: .leading) {
                                        Text(section.name).font(.headline)
                                        Text("\(stageClock(section.start)) → \(stageClock(section.end))")
                                            .font(.caption).foregroundStyle(.secondary)
                                    }
                                    Spacer()
                                    if section.addCount { Text("2·3·4").font(.caption.bold()).foregroundStyle(Color.sgMint) }
                                }
                                HStack {
                                    Button("Ir") { model.selectSection(section) }
                                    Button("Conteo + Play") { model.playSectionWithCountIn(section) }
                                    Spacer()
                                    Button(role: .destructive) {
                                        model.saveLoadedSong { value in value.sections.removeAll { $0.id == section.id } }
                                    } label: { Image(systemName: "trash") }
                                }
                                .buttonStyle(.borderless)
                            }
                            .padding(.vertical, 3)
                        }
                    }

                    arrangementSection(song)
                    guideSection(song)
                    clickSection
                    diagnosticsSection(song)
                }
                .scrollContentBackground(.hidden)
                .background(Color.sgCanvas)
                .navigationTitle("Advanced")
                .fileImporter(isPresented: $guideImporter, allowedContentTypes: [.zip], allowsMultipleSelection: false) { result in
                    switch result {
                    case .success(let urls):
                        guard let url = urls.first else { return }
                        model.installGuidePack(url)
                        guideError = model.nativeGuideError
                    case .failure(let error): guideError = error.localizedDescription
                    }
                }
                .alert("Guide Pack", isPresented: Binding(get: { guideError != nil }, set: { if !$0 { guideError = nil } })) {
                    Button("Cerrar", role: .cancel) { guideError = nil }
                } message: { Text(guideError ?? "") }
            } else {
                EmptyStageView(title: "Advanced sin canción", subtitle: "Carga una canción para editar secciones, Arrangement y Native Guide.", systemImage: "waveform.path.ecg.rectangle")
                    .background(Color.sgCanvas)
            }
        }
    }

    @ViewBuilder
    private func arrangementSection(_ song: StageSong) -> some View {
        Section("Arrangement") {
            HStack {
                Button(model.arrangementState.active ? "Reiniciar" : "Iniciar") { model.startArrangement() }
                if model.arrangementState.active {
                    Button("Detener") { model.stopArrangement() }
                    if let node = song.resolvedArrangement.first(where: { $0.id == model.arrangementState.activeNodeID }), node.repeatCount < 0 {
                        Button("Exit at boundary") { model.exitArrangementLoop() }
                    }
                }
            }
            .buttonStyle(.borderless)

            ForEach(Array(song.resolvedArrangement.enumerated()), id: \.element.id) { index, node in
                ArrangementNodeRow(song: song, node: node, index: index)
            }
        }
    }

    @ViewBuilder
    private func guideSection(_ song: StageSong) -> some View {
        Section("Guía / Cue Auto") {
            Picker("Fuente", selection: Binding(
                get: { audio.guideSource },
                set: { model.setGuideSource($0) }
            )) {
                Text("Guía original").tag(StageGuideSource.original)
                Text("Cue Auto").tag(StageGuideSource.cue)
            }
            .pickerStyle(.segmented)

            HStack {
                VStack(alignment: .leading) {
                    Text(model.guidePacks.status.installed ? "Guide Pack listo" : "Guide Pack no instalado")
                    if model.guidePacks.status.installed {
                        Text("\(model.guidePacks.status.sampleCount) samples · \(model.guidePacks.status.languages.joined(separator: ", "))")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
                Spacer()
                Button(model.guidePacks.status.installed ? "Reemplazar" : "Instalar") { guideImporter = true }
            }

            Picker("Idioma", selection: Binding(
                get: { song.resolvedNativeGuideLanguage },
                set: { language in model.saveLoadedSong { $0.nativeGuideLanguage = language } }
            )) {
                Text("Auto").tag("auto")
                Text("Español").tag("es")
                Text("English").tag("en")
                Text("Français").tag("fr")
                Text("Português").tag("pt")
            }

            if model.nativeGuideRunning {
                VStack(alignment: .leading, spacing: 6) {
                    ProgressView(value: model.nativeGuideProgress?.fraction ?? 0)
                    Text(model.nativeGuideProgress?.stage ?? "Analizando").font(.caption.bold())
                    if let detail = model.nativeGuideProgress?.detail { Text(detail).font(.caption).foregroundStyle(.secondary) }
                }
            } else {
                Button("Reanalizar Native Guide") { model.reanalyzeNativeGuide() }
                    .disabled(!model.guidePacks.status.installed || !song.tracks.contains(where: { $0.kind == .guide }))
            }
            if let analysis = song.nativeGuideAnalysis {
                LabeledContent("Cues detectados", value: "\(analysis.events.count)")
                LabeledContent("Idioma detectado", value: analysis.detectedLanguage?.uppercased() ?? "—")
            }
            if let error = model.nativeGuideError { Text(error).foregroundStyle(.red).font(.footnote) }
        }
    }

    private var clickSection: some View {
        Section("Click / Count-in") {
            Picker("Subdivisión", selection: Binding(
                get: { audio.clickSubdivision },
                set: { value in
                    audio.clickSubdivision = value
                    model.updatePreferences { $0.clickSubdivision = value }
                }
            )) {
                ForEach(StageClickSubdivision.allCases) { subdivision in Text(subdivision.label).tag(subdivision) }
            }
            Picker("Count-in", selection: Binding(
                get: { model.preferences.countInBars },
                set: { value in model.updatePreferences { $0.countInBars = value } }
            )) {
                Text("Off").tag(0)
                Text("1 compás").tag(1)
                Text("2 compases").tag(2)
            }
        }
    }

    private func diagnosticsSection(_ song: StageSong) -> some View {
        Section("Diagnóstico") {
            LabeledContent("Motor", value: audio.isPlaying ? "PLAYING" : "READY")
            LabeledContent("Salida", value: audio.outputName)
            LabeledContent("Canales", value: "\(audio.outputChannelCount)")
            LabeledContent("Stems", value: "\(song.tracks.count)")
            LabeledContent("DSP", value: audio.tempoRatio == 1 && audio.pitchSemitones == 0 ? "Bypass" : "Activo")
            if let next = audio.preloadedSongTitle { LabeledContent("Preload", value: next) }
            if let error = audio.errorMessage { Text(error).foregroundStyle(.red).font(.footnote) }
        }
    }
}

private struct ArrangementNodeRow: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var audio: StageGridAudioEngine
    let song: StageSong
    let node: StageArrangementNode
    let index: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("\(index + 1). \(node.label)").font(.headline)
                Spacer()
                if model.arrangementState.activeNodeID == node.id { Text("ACTUAL").font(.caption2.bold()).foregroundStyle(Color.sgMint) }
                if model.arrangementState.queuedNodeID == node.id { Text("EN COLA").font(.caption2.bold()).foregroundStyle(.orange) }
            }
            HStack(spacing: 8) {
                Menu {
                    ForEach([1, 2, 4, 8, 16], id: \.self) { repeatCount in
                        Button("\(repeatCount)x") { mutate { $0.repeatCount = repeatCount } }
                    }
                    Button("∞") { mutate { $0.repeatCount = -1 } }
                } label: { Label(node.repeatCount < 0 ? "∞" : "\(node.repeatCount)x", systemImage: "repeat") }

                Menu {
                    ForEach(0...2, id: \.self) { bars in Button("\(bars) compás(es)") { mutate { $0.preRollBars = bars } } }
                } label: { Label("Pre \(node.preRollBars)", systemImage: "metronome") }

                Button { mutate { $0.guideEnabled.toggle() } } label: {
                    Label(node.guideEnabled ? "Cue" : "Sin cue", systemImage: node.guideEnabled ? "speaker.wave.2.fill" : "speaker.slash")
                }
                Spacer()
                Button { move(-1) } label: { Image(systemName: "arrow.up") }.disabled(index == 0)
                Button { move(1) } label: { Image(systemName: "arrow.down") }.disabled(index >= song.resolvedArrangement.count - 1)
            }
            .buttonStyle(.borderless)
        }
        .padding(.vertical, 4)
    }

    private func mutate(_ edit: (inout StageArrangementNode) -> Void) {
        var nodes = song.resolvedArrangement
        guard let position = nodes.firstIndex(where: { $0.id == node.id }) else { return }
        edit(&nodes[position])
        model.updateArrangement(nodes)
    }

    private func move(_ delta: Int) {
        var nodes = song.resolvedArrangement
        let destination = index + delta
        guard nodes.indices.contains(index), nodes.indices.contains(destination) else { return }
        nodes.swapAt(index, destination)
        model.updateArrangement(nodes)
    }
}
