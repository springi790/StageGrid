import Foundation

@MainActor
final class AppModel: ObservableObject {
    enum Tab: Hashable { case library, setlists, live, mixer, settings }

    @Published var selectedTab: Tab = .library
    @Published var preferences: StagePreferences
    @Published var queuedSectionID: UUID?

    let library = LibraryStore()
    let audio = StageGridAudioEngine()

    private var sectionTask: Task<Void, Never>?
    private let defaultsKey = "stagegrid.ios.preferences.v1"

    init() {
        if let data = UserDefaults.standard.data(forKey: defaultsKey),
           let saved = try? JSONDecoder().decode(StagePreferences.self, from: data) {
            preferences = saved
        } else {
            preferences = StagePreferences()
        }
        audio.clickEnabled = preferences.clickEnabled
        audio.guideEnabled = preferences.guideEnabled
    }

    func finishSetup(_ value: StagePreferences) {
        preferences = value
        persistPreferences()
        audio.clickEnabled = value.clickEnabled
        audio.guideEnabled = value.guideEnabled
    }

    func updatePreferences(_ mutate: (inout StagePreferences) -> Void) {
        var copy = preferences
        mutate(&copy)
        preferences = copy
        persistPreferences()
        audio.clickEnabled = copy.clickEnabled
        audio.guideEnabled = copy.guideEnabled
    }

    func load(_ song: StageSong) {
        sectionTask?.cancel()
        queuedSectionID = nil
        audio.load(song: song, library: library)
        audio.clickEnabled = preferences.clickEnabled
        audio.guideEnabled = preferences.guideEnabled
        selectedTab = .live
    }

    func selectSection(_ section: StageSection) {
        sectionTask?.cancel()
        guard let song = audio.song else { return }
        if !audio.isPlaying {
            audio.seek(to: section.start, autoPlay: false)
            return
        }

        let current = song.sections.last(where: { audio.position >= $0.start && audio.position < $0.end })
        let boundary = current?.end ?? audio.position
        let sourceRemaining = max(0, boundary - audio.position)
        let wallDelay = sourceRemaining / Double(max(0.01, audio.tempoRatio))
        queuedSectionID = section.id

        sectionTask = Task { [weak self] in
            let ns = UInt64(max(0, wallDelay) * 1_000_000_000)
            try? await Task.sleep(nanoseconds: ns)
            guard !Task.isCancelled, let self else { return }
            self.audio.seek(to: section.start, autoPlay: true)
            self.queuedSectionID = nil
        }
    }

    func cancelQueuedSection() {
        sectionTask?.cancel()
        sectionTask = nil
        queuedSectionID = nil
    }

    func saveLoadedSong(_ edit: (inout StageSong) -> Void) {
        guard var song = audio.song else { return }
        edit(&song)
        library.update(song)
        let keepPlaying = audio.isPlaying
        let oldPosition = audio.position
        audio.load(song: song, library: library, startAt: oldPosition)
        if keepPlaying { audio.play() }
    }

    private func persistPreferences() {
        if let data = try? JSONEncoder().encode(preferences) {
            UserDefaults.standard.set(data, forKey: defaultsKey)
        }
    }
}
