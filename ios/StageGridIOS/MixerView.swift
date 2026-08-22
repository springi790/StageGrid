import SwiftUI

struct MixerView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var audio: StageGridAudioEngine

    var body: some View {
        Group {
            if let song = audio.song {
                ScrollView {
                    VStack(spacing: 12) {
                        HStack(spacing: 10) {
                            toggleCard(title: "CLICK", isOn: audio.clickEnabled) {
                                audio.clickEnabled.toggle()
                                model.updatePreferences { $0.clickEnabled = audio.clickEnabled }
                            }
                            toggleCard(title: "GUÍA", isOn: audio.guideEnabled) {
                                audio.guideEnabled.toggle()
                                model.updatePreferences { $0.guideEnabled = audio.guideEnabled }
                            }
                        }
                        guideSourceCard
                        masterCard
                        outputCard
                        ForEach(song.tracks) { track in TrackStrip(track: track) }
                    }
                    .padding(14)
                }
                .background(Color.sgCanvas)
                .navigationTitle("Mixer")
            } else {
                EmptyStageView(title: "Mixer sin canción", subtitle: "Carga una canción para mezclar stems.", systemImage: "slider.horizontal.3")
                    .background(Color.sgCanvas)
            }
        }
    }

    private var guideSourceCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("FUENTE DE GUÍA").font(.caption.bold()).foregroundStyle(.secondary)
            Picker("Fuente", selection: Binding(
                get: { audio.guideSource },
                set: { model.setGuideSource($0) }
            )) {
                Text("Off · Guía").tag(StageGuideSource.original)
                Text("On · Cue Auto").tag(StageGuideSource.cue)
            }
            .pickerStyle(.segmented)
            Text(audio.guideSource == .cue ? "Los cues se generan desde las secciones y el Guide Pack." : "Se reproduce la pista Guide original de la canción.")
                .font(.caption).foregroundStyle(.secondary)
        }
        .padding(14)
        .background(Color.sgSurface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }

    private var masterCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("MASTER").font(.caption.bold()).foregroundStyle(.secondary)
                Spacer()
                Text("\(Int(audio.masterVolume * 100))%").font(.headline.monospacedDigit()).foregroundStyle(Color.sgBlue)
            }
            Slider(value: Binding(get: { audio.masterVolume }, set: { audio.masterVolume = $0 }), in: 0...1.25).tint(.sgBlue)
        }
        .padding(14)
        .background(Color.sgSurface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }

    private var outputCard: some View {
        HStack {
            VStack(alignment: .leading, spacing: 3) {
                Text("SALIDA").font(.caption.bold()).foregroundStyle(.secondary)
                Text(audio.outputName).font(.headline).lineLimit(1)
                Text("\(audio.outputChannelCount) canales disponibles").font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if audio.outputChannelCount < 4 {
                Text("STEREO").font(.caption2.bold()).foregroundStyle(.orange).padding(.horizontal, 8).padding(.vertical, 5).background(Color.orange.opacity(0.12), in: Capsule())
            } else {
                Text("MULTI-OUT").font(.caption2.bold()).foregroundStyle(Color.sgMint).padding(.horizontal, 8).padding(.vertical, 5).background(Color.sgMint.opacity(0.12), in: Capsule())
            }
        }
        .padding(14)
        .background(Color.sgSurface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }

    private func toggleCard(title: String, isOn: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                VStack(alignment: .leading) {
                    Text(title).font(.caption.bold()).foregroundStyle(.secondary)
                    Text(isOn ? "On" : "Off").font(.headline.bold())
                }
                Spacer()
                Image(systemName: isOn ? "checkmark.circle.fill" : "circle").foregroundStyle(isOn ? Color.sgMint : .secondary)
            }
            .padding(14).frame(maxWidth: .infinity)
            .background(Color.sgSurface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}

private struct TrackStrip: View {
    @EnvironmentObject private var audio: StageGridAudioEngine
    let track: StageTrack

    private var current: StageTrack { audio.song?.tracks.first(where: { $0.id == track.id }) ?? track }
    private var availableBuses: [Int] { Array(0..<max(1, min(4, audio.outputChannelCount / 2))) }

    var body: some View {
        VStack(spacing: 9) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(current.name).font(.headline.bold()).lineLimit(1)
                    Text(current.kind.rawValue.uppercased()).font(.caption2).foregroundStyle(.secondary)
                }
                Spacer()
                Button("M") { audio.setTrackMute(track.id, muted: !current.muted) }
                    .buttonStyle(.borderedProminent).tint(current.muted ? .red : .gray.opacity(0.35))
                Button("S") { audio.setTrackSolo(track.id, solo: !current.solo) }
                    .buttonStyle(.borderedProminent).tint(current.solo ? .yellow : .gray.opacity(0.35))
            }
            HStack {
                Text("VOL").font(.caption2).foregroundStyle(.secondary)
                Slider(value: Binding(get: { current.volume }, set: { audio.setTrackVolume(track.id, volume: $0) }), in: 0...1.5)
                Text("\(Int(current.volume * 100))").font(.caption.monospacedDigit()).frame(width: 34)
            }
            HStack {
                Text("PAN").font(.caption2).foregroundStyle(.secondary)
                Slider(value: Binding(get: { current.pan }, set: { audio.setTrackPan(track.id, pan: $0) }), in: -1...1)
                Text(current.pan < -0.05 ? "L" : current.pan > 0.05 ? "R" : "C").font(.caption.bold()).frame(width: 24)
            }
            HStack {
                Text("OUT").font(.caption2).foregroundStyle(.secondary)
                Menu {
                    ForEach(availableBuses, id: \.self) { bus in
                        Button("Out \(bus * 2 + 1)/\(bus * 2 + 2)") { audio.setTrackOutput(track.id, bus: bus, route: current.resolvedStereoRoute) }
                    }
                } label: { Text("\(current.resolvedOutputBus * 2 + 1)/\(current.resolvedOutputBus * 2 + 2)").font(.caption.bold()) }
                Menu {
                    ForEach(StageStereoRoute.allCases) { route in
                        Button(route.rawValue) { audio.setTrackOutput(track.id, bus: current.resolvedOutputBus, route: route) }
                    }
                } label: { Text(current.resolvedStereoRoute.rawValue).font(.caption.bold()) }
                Spacer()
                if current.resolvedOutputBus > 0 && audio.outputChannelCount < (current.resolvedOutputBus + 1) * 2 {
                    Text("Fallback 1/2").font(.caption2).foregroundStyle(.orange)
                }
            }
        }
        .padding(14)
        .background(Color.sgSurface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(Color.white.opacity(0.06)))
    }
}
