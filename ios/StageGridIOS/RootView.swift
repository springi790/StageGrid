import SwiftUI

struct RootView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        TabView(selection: $model.selectedTab) {
            NavigationStack { LibraryView() }
                .tabItem { Label("Biblioteca", systemImage: "music.note.list") }
                .tag(AppModel.Tab.library)

            NavigationStack { SetlistsView() }
                .tabItem { Label("Setlists", systemImage: "list.bullet.rectangle") }
                .tag(AppModel.Tab.setlists)

            NavigationStack { LiveView() }
                .tabItem { Label("Live", systemImage: "play.square.stack") }
                .tag(AppModel.Tab.live)

            NavigationStack { MixerView() }
                .tabItem { Label("Mixer", systemImage: "slider.horizontal.3") }
                .tag(AppModel.Tab.mixer)

            NavigationStack { SettingsView() }
                .tabItem { Label("Ajustes", systemImage: "gearshape") }
                .tag(AppModel.Tab.settings)
        }
        .tint(.sgBlue)
        .background(Color.sgCanvas)
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
