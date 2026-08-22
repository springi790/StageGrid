import SwiftUI
import AVFoundation

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var audio: StageGridAudioEngine

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
            }

            Section("Audio") {
                Toggle("Click", isOn: Binding(
                    get: { audio.clickEnabled },
                    set: { value in
                        audio.clickEnabled = value
                        model.updatePreferences { $0.clickEnabled = value }
                    }
                ))
                Toggle("Guía", isOn: Binding(
                    get: { audio.guideEnabled },
                    set: { value in
                        audio.guideEnabled = value
                        model.updatePreferences { $0.guideEnabled = value }
                    }
                ))
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

            Section("Salida actual") {
                LabeledContent("Sample rate", value: "\(Int(AVAudioSession.sharedInstance().sampleRate)) Hz")
                LabeledContent("Buffer", value: String(format: "%.1f ms", AVAudioSession.sharedInstance().ioBufferDuration * 1000))
                LabeledContent("Ruta", value: AVAudioSession.sharedInstance().currentRoute.outputs.first?.portName ?? "Sistema")
            }

            Section("Versión") {
                LabeledContent("StageGrid iOS/iPadOS", value: "0.7.0-alpha01")
                Text("Port nativo con SwiftUI + AVFoundation. La biblioteca iOS es local e independiente de la instalación Android.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .scrollContentBackground(.hidden)
        .background(Color.sgCanvas)
        .navigationTitle("Ajustes")
    }
}
