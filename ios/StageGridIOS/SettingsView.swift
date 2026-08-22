import SwiftUI
import AVFoundation
import UniformTypeIdentifiers

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var audio: StageGridAudioEngine
    @EnvironmentObject private var guidePacks: GuidePackStore
    @EnvironmentObject private var midi: MidiManager
    @EnvironmentObject private var backup: BackupManager
    @State private var guideImporter = false
    @State private var restoreImporter = false
    @State private var backupURL: URL?
    @State private var message: String?

    var body: some View {
        Form {
            Section("Presentación") {
                Toggle("Modo Live", isOn: Binding(
                    get: { model.preferences.liveMode },
                    set: { value in model.updatePreferences { $0.liveMode = value } }
                ))
                Toggle("Performance Lock", isOn: Binding(
                    get: { model.preferences.performanceLock },
                    set: { value in model.updatePreferences { $0.performanceLock = value } }
                ))
                Text("Performance Lock deja visibles solamente Live, Mixer y Ajustes.")
                    .font(.footnote).foregroundStyle(.secondary)
            }

            Section("Audio") {
                Toggle("Click", isOn: Binding(
                    get: { audio.clickEnabled },
                    set: { value in audio.clickEnabled = value; model.updatePreferences { $0.clickEnabled = value } }
                ))
                Toggle("Guía", isOn: Binding(
                    get: { audio.guideEnabled },
                    set: { value in audio.guideEnabled = value; model.updatePreferences { $0.guideEnabled = value } }
                ))
                Picker("Subdivisión", selection: Binding(
                    get: { audio.clickSubdivision },
                    set: { value in audio.clickSubdivision = value; model.updatePreferences { $0.clickSubdivision = value } }
                )) {
                    ForEach(StageClickSubdivision.allCases) { Text($0.label).tag($0) }
                }
                Picker("Conteo", selection: Binding(
                    get: { model.preferences.countInBars },
                    set: { value in model.updatePreferences { $0.countInBars = value } }
                )) {
                    Text("Off").tag(0)
                    Text("1 compás").tag(1)
                    Text("2 compases").tag(2)
                }
                Picker("Idioma de cues", selection: Binding(
                    get: { model.preferences.guideLanguage },
                    set: { value in model.updatePreferences { $0.guideLanguage = value } }
                )) {
                    Text("Auto").tag("auto")
                    Text("Español").tag("es")
                    Text("English").tag("en")
                    Text("Français").tag("fr")
                    Text("Português").tag("pt")
                }
            }

            Section("Guide Pack") {
                if guidePacks.status.installed {
                    LabeledContent("Estado", value: "Instalado")
                    LabeledContent("Samples", value: "\(guidePacks.status.sampleCount)")
                    LabeledContent("Idiomas", value: guidePacks.status.languages.joined(separator: ", ").uppercased())
                    if let source = guidePacks.status.sourceName { Text(source).font(.caption).foregroundStyle(.secondary) }
                } else {
                    Text("Instala el mismo ZIP de Guide Pack que usas en Android para Cue Auto y Native Guide.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Button(guidePacks.status.installed ? "Reemplazar Guide Pack" : "Instalar Guide Pack") { guideImporter = true }
            }

            Section("Salida de audio") {
                Picker("Canales solicitados", selection: Binding(
                    get: { model.preferences.resolvedOutputBusCount * 2 },
                    set: { channels in
                        model.updatePreferences { $0.preferredOutputBusCount = channels / 2 }
                        audio.requestOutputChannels(channels)
                    }
                )) {
                    Text("2 · estéreo").tag(2)
                    Text("4 · Out 1–4").tag(4)
                    Text("6 · Out 1–6").tag(6)
                    Text("8 · Out 1–8").tag(8)
                }
                LabeledContent("Ruta", value: audio.outputName)
                LabeledContent("Canales concedidos", value: "\(audio.outputChannelCount)")
                LabeledContent("Máximo de la ruta", value: "\(max(2, AVAudioSession.sharedInstance().maximumOutputNumberOfChannels))")
                LabeledContent("Sample rate", value: "\(Int(AVAudioSession.sharedInstance().sampleRate)) Hz")
                LabeledContent("Buffer", value: String(format: "%.1f ms", AVAudioSession.sharedInstance().ioBufferDuration * 1000))
                if audio.outputChannelCount < model.preferences.resolvedOutputBusCount * 2 {
                    Text("La interfaz no concedió todos los canales solicitados. Los buses no disponibles muestran fallback a 1/2.")
                        .font(.footnote).foregroundStyle(.orange)
                } else if audio.outputChannelCount >= 4 {
                    Text("La ruta expone audio multicanal a StageGrid. Valida el patch físico de cada salida antes del evento.")
                        .font(.footnote).foregroundStyle(Color.sgMint)
                }
                if let error = audio.errorMessage { Text(error).font(.footnote).foregroundStyle(.red) }
            }

            midiSection
            backupSection

            Section("Versión") {
                LabeledContent("StageGrid iOS/iPadOS", value: "0.7.0-alpha02")
                Text("Port nativo SwiftUI + AVFoundation/CoreMIDI basado en la Android 0.7.0-alpha05.4 validada.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .scrollContentBackground(.hidden)
        .background(Color.sgCanvas)
        .navigationTitle("Ajustes")
        .onAppear { audio.requestOutputChannels(model.preferences.resolvedOutputBusCount * 2) }
        .fileImporter(isPresented: $guideImporter, allowedContentTypes: [.zip], allowsMultipleSelection: false) { result in
            switch result {
            case .success(let urls):
                if let url = urls.first { model.installGuidePack(url); message = model.nativeGuideError ?? "Guide Pack instalado." }
            case .failure(let error): message = error.localizedDescription
            }
        }
        .fileImporter(isPresented: $restoreImporter, allowedContentTypes: [.zip, .data], allowsMultipleSelection: false) { result in
            switch result {
            case .success(let urls):
                guard let url = urls.first else { return }
                Task {
                    let ok = await backup.restoreBackup(from: url, library: model.library)
                    message = ok ? "Biblioteca restaurada." : backup.lastError
                }
            case .failure(let error): message = error.localizedDescription
            }
        }
        .alert("StageGrid", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button("Cerrar", role: .cancel) { message = nil }
        } message: { Text(message ?? "") }
    }

    private var midiSection: some View {
        Section("MIDI") {
            if midi.sources.isEmpty {
                Text("No hay entradas MIDI detectadas.").foregroundStyle(.secondary)
            } else {
                ForEach(midi.sources) { source in Label(source.name, systemImage: "pianokeys") }
            }
            if !midi.destinations.isEmpty {
                Picker("Clock OUT", selection: Binding(
                    get: { midi.selectedDestinationID },
                    set: { midi.selectedDestinationID = $0 }
                )) {
                    Text("Sin destino").tag(Optional<UInt32>.none)
                    ForEach(midi.destinations) { destination in Text(destination.name).tag(Optional(destination.id)) }
                }
            }
            Toggle("Enviar MIDI Clock", isOn: Binding(
                get: { model.preferences.resolvedMidiClockOutputEnabled },
                set: { value in
                    model.updatePreferences { $0.midiClockOutputEnabled = value }
                    if value, let song = audio.song, audio.isPlaying { midi.startClock(bpm: song.bpm * Double(audio.tempoRatio)) }
                    else if !value { midi.stopClock() }
                }
            ))
            if let last = midi.lastMessage { LabeledContent("Último", value: last.display) }
            LabeledContent("Mensajes", value: "\(midi.messageCount)")
            LabeledContent("Clock RX", value: "\(midi.clockCount)")

            DisclosureGroup("MIDI Learn") {
                midiLearnRow("Play / Pause", action: .playPause)
                midiLearnRow("Stop", action: .stop)
                midiLearnRow("Stop All", action: .stopAll)
                midiLearnRow("Siguiente canción", action: .nextSong)
                midiLearnRow("Canción anterior", action: .previousSong)
                midiLearnRow("Click On/Off", action: .clickToggle)
                midiLearnRow("Guía On/Off", action: .guideToggle)
                if let song = audio.song {
                    ForEach(song.sections) { section in midiLearnRow("Sección · \(section.name)", action: .section(section.id)) }
                    ForEach(song.tracks) { track in
                        midiLearnRow("Mute · \(track.name)", action: .trackMute(track.id))
                        midiLearnRow("Solo · \(track.name)", action: .trackSolo(track.id))
                    }
                }
            }

            if !midi.bindings.isEmpty {
                DisclosureGroup("Mappings guardados (\(midi.bindings.count))") {
                    ForEach(midi.bindings) { binding in
                        HStack {
                            VStack(alignment: .leading) {
                                Text(binding.action.id).font(.caption.bold())
                                Text("\(binding.kind.rawValue) · CH \(binding.channel + 1) · \(binding.number)")
                                    .font(.caption2).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button(role: .destructive) { midi.removeBinding(binding) } label: { Image(systemName: "trash") }
                        }
                    }
                }
            }
            Button("Actualizar dispositivos") { midi.refreshEndpoints() }
        }
    }

    private func midiLearnRow(_ title: String, action: StageMidiAction) -> some View {
        HStack {
            Text(title)
            Spacer()
            if midi.learningAction?.id == action.id {
                Button("Esperando MIDI…") { midi.cancelLearn() }.foregroundStyle(.orange)
            } else {
                Button("Learn") { model.beginMidiLearn(action) }
            }
        }
    }

    private var backupSection: some View {
        Section("Backup / Restore") {
            if backup.running {
                ProgressView(value: backup.progress)
                Text(backup.stage).font(.caption).foregroundStyle(.secondary)
            } else {
                Button("Crear .stagebackup") {
                    Task { backupURL = await backup.createBackup(library: model.library) }
                }
                if let backupURL {
                    ShareLink(item: backupURL) { Label("Compartir backup", systemImage: "square.and.arrow.up") }
                }
                Button("Restaurar backup") { restoreImporter = true }
            }
            if let error = backup.lastError { Text(error).font(.footnote).foregroundStyle(.red) }
        }
    }
}
