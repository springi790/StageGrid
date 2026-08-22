import SwiftUI
import AVFoundation
import UniformTypeIdentifiers

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var audio: StageGridAudioEngine
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
                if model.guidePacks.status.installed {
                    LabeledContent("Estado", value: "Instalado")
                    LabeledContent("Samples", value: "\(model.guidePacks.status.sampleCount)")
                    LabeledContent("Idiomas", value: model.guidePacks.status.languages.joined(separator: ", ").uppercased())
                    if let source = model.guidePacks.status.sourceName { Text(source).font(.caption).foregroundStyle(.secondary) }
                } else {
                    Text("Instala el mismo ZIP de Guide Pack que usas en Android para Cue Auto y Native Guide.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Button(model.guidePacks.status.installed ? "Reemplazar Guide Pack" : "Instalar Guide Pack") { guideImporter = true }
            }

            Section("Salida actual") {
                LabeledContent("Ruta", value: audio.outputName)
                LabeledContent("Canales", value: "\(audio.outputChannelCount)")
                LabeledContent("Sample rate", value: "\(Int(AVAudioSession.sharedInstance().sampleRate)) Hz")
                LabeledContent("Buffer", value: String(format: "%.1f ms", AVAudioSession.sharedInstance().ioBufferDuration * 1000))
                if audio.outputChannelCount < 4 {
                    Text("Sin una interfaz multicanal compatible, los buses superiores usan fallback estéreo 1/2.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
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
                    let ok = await model.backup.restoreBackup(from: url, library: model.library)
                    message = ok ? "Biblioteca restaurada." : model.backup.lastError
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
            if model.midi.sources.isEmpty {
                Text("No hay entradas MIDI detectadas.").foregroundStyle(.secondary)
            } else {
                ForEach(model.midi.sources) { source in Label(source.name, systemImage: "pianokeys") }
            }
            if !model.midi.destinations.isEmpty {
                Picker("Clock OUT", selection: Binding(
                    get: { model.midi.selectedDestinationID },
                    set: { model.midi.selectedDestinationID = $0 }
                )) {
                    Text("Sin destino").tag(Optional<UInt32>.none)
                    ForEach(model.midi.destinations) { destination in Text(destination.name).tag(Optional(destination.id)) }
                }
            }
            Toggle("Enviar MIDI Clock", isOn: Binding(
                get: { model.preferences.resolvedMidiClockOutputEnabled },
                set: { value in
                    model.updatePreferences { $0.midiClockOutputEnabled = value }
                    if value, let song = audio.song, audio.isPlaying { model.midi.startClock(bpm: song.bpm * Double(audio.tempoRatio)) }
                    else if !value { model.midi.stopClock() }
                }
            ))
            if let last = model.midi.lastMessage {
                LabeledContent("Último", value: last.display)
            }
            LabeledContent("Mensajes", value: "\(model.midi.messageCount)")
            LabeledContent("Clock RX", value: "\(model.midi.clockCount)")

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
                }
            }

            if !model.midi.bindings.isEmpty {
                DisclosureGroup("Mappings guardados (\(model.midi.bindings.count))") {
                    ForEach(model.midi.bindings) { binding in
                        HStack {
                            VStack(alignment: .leading) {
                                Text(binding.action.id).font(.caption.bold())
                                Text("\(binding.kind.rawValue) · CH \(binding.channel + 1) · \(binding.number)")
                                    .font(.caption2).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button(role: .destructive) { model.midi.removeBinding(binding) } label: { Image(systemName: "trash") }
                        }
                    }
                }
            }
            Button("Actualizar dispositivos") { model.midi.refreshEndpoints() }
        }
    }

    private func midiLearnRow(_ title: String, action: StageMidiAction) -> some View {
        HStack {
            Text(title)
            Spacer()
            if model.midi.learningAction?.id == action.id {
                Button("Esperando MIDI…") { model.midi.cancelLearn() }.foregroundStyle(.orange)
            } else {
                Button("Learn") { model.beginMidiLearn(action) }
            }
        }
    }

    private var backupSection: some View {
        Section("Backup / Restore") {
            if model.backup.running {
                ProgressView(value: model.backup.progress)
                Text(model.backup.stage).font(.caption).foregroundStyle(.secondary)
            } else {
                Button("Crear .stagebackup") {
                    Task { backupURL = await model.backup.createBackup(library: model.library) }
                }
                if let backupURL {
                    ShareLink(item: backupURL) { Label("Compartir backup", systemImage: "square.and.arrow.up") }
                }
                Button("Restaurar backup") { restoreImporter = true }
            }
            if let error = model.backup.lastError { Text(error).font(.footnote).foregroundStyle(.red) }
        }
    }
}
