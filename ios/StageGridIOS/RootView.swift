import SwiftUI

struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var audio: StageGridAudioEngine

    var body: some View {
        TabView(selection: $model.selectedTab) {
            if !model.preferences.performanceLock {
                NavigationStack { LibraryView() }
                    .tabItem { Label("Biblioteca", systemImage: "music.note.list") }
                    .tag(AppModel.Tab.library)

                NavigationStack { SetlistsView() }
                    .tabItem { Label("Setlists", systemImage: "list.bullet.rectangle") }
                    .tag(AppModel.Tab.setlists)
            }

            NavigationStack { LiveView() }
                .tabItem { Label("Live", systemImage: "play.square.stack") }
                .tag(AppModel.Tab.live)

            NavigationStack { MixerView() }
                .tabItem { Label("Mixer", systemImage: "slider.horizontal.3") }
                .tag(AppModel.Tab.mixer)

            if !model.preferences.performanceLock {
                NavigationStack { AdvancedView() }
                    .tabItem { Label("Advanced", systemImage: "waveform.path.ecg.rectangle") }
                    .tag(AppModel.Tab.advanced)
            }

            NavigationStack { SettingsView() }
                .tabItem { Label("Ajustes", systemImage: "gearshape") }
                .tag(AppModel.Tab.settings)
        }
        .tint(.sgBlue)
        .background(Color.sgCanvas)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if let song = audio.song, model.selectedTab != .live {
                MiniTransport(song: song)
            }
        }
        .onChange(of: model.preferences.performanceLock) { _, locked in
            if locked, ![AppModel.Tab.live, .mixer, .settings].contains(model.selectedTab) { model.selectedTab = .live }
        }
    }
}

private struct MiniTransport: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var audio: StageGridAudioEngine
    let song: StageSong

    var body: some View {
        HStack(spacing: 10) {
            Button {
                model.selectedTab = .live
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text(song.title).font(.subheadline.bold()).lineLimit(1)
                    Text("\(stageClock(audio.position)) / \(stageClock(audio.duration))")
                        .font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.plain)
            Button { audio.isPlaying ? audio.pause() : audio.play() } label: {
                Image(systemName: audio.isPlaying ? "pause.fill" : "play.fill").frame(width: 32, height: 32)
            }
            .buttonStyle(.borderedProminent).tint(.sgBlue)
            Button { audio.stop(unload: false) } label: { Image(systemName: "stop.fill") }
                .buttonStyle(.bordered)
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(.ultraThinMaterial)
        .overlay(alignment: .top) { Divider() }
    }
}

struct EmptyStageView: View {
    let title: String
    let subtitle: String
    var systemImage = "waveform"

    var body: some View {
        ContentUnavailableView {
            Label(title, systemImage: systemImage)
        } description: {
            Text(subtitle)
        }
    }
}
