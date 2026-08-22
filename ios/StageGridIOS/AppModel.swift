import Foundation

@MainActor
final class AppModel: ObservableObject {
    enum Tab: Hashable { case library, setlists, live, mixer, advanced, settings }

    struct ArrangementState: Equatable {
        var active = false
        var activeNodeID: UUID?
        var queuedNodeID: UUID?
        var iteration = 1
        var exitRequested = false
    }

    struct SetlistLiveState: Equatable {
        var active = false
        var setlistID: UUID?
        var currentIndex = -1
        var nextReady = false
        var preloading = false
        var error: String?
    }

    @Published var selectedTab: Tab = .library
    @Published var preferences: StagePreferences
    @Published var queuedSectionID: UUID?
    @Published private(set) var arrangementState = ArrangementState()
    @Published private(set) var setlistLiveState = SetlistLiveState()
    @Published private(set) var nativeGuideProgress: NativeGuideProgress?
    @Published private(set) var nativeGuideRunning = false
    @Published private(set) var nativeGuideError: String?

    let library = LibraryStore()
    let audio = StageGridAudioEngine()
    let guidePacks = GuidePackStore()
    let midi = MidiManager()
    let backup = BackupManager()

    private let nativeGuideAnalyzer = NativeGuideAnalyzer()
    private var playbackMonitor: Task<Void, Never>?
    private var arrangementBoundaryScheduled: TimeInterval?
    private var autoCuedSectionID: UUID?
    private var scheduledNativeEvents = Set<UUID>()
    private let defaultsKey = "stagegrid.ios.preferences.v1"

    init() {
        if let data = UserDefaults.standard.data(forKey: defaultsKey),
           let saved = try? JSONDecoder().decode(StagePreferences.self, from: data) {
            preferences = saved
        } else {
            preferences = StagePreferences()
        }
        applyPreferencesToAudio()
        midi.onAction = { [weak self] action in self?.performMidiAction(action) }
        playbackMonitor = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 70_000_000)
                guard let self else { return }
                self.monitorPlayback()
            }
        }
    }

    deinit { playbackMonitor?.cancel() }

    // MARK: - Preferences / startup

    func finishSetup(_ value: StagePreferences) {
        preferences = value
        persistPreferences()
        applyPreferencesToAudio()
    }

    func updatePreferences(_ mutate: (inout StagePreferences) -> Void) {
        var copy = preferences
        mutate(&copy)
        preferences = copy
        persistPreferences()
        applyPreferencesToAudio()
    }

    private func applyPreferencesToAudio() {
        audio.clickEnabled = preferences.clickEnabled
        audio.guideEnabled = preferences.guideEnabled
        audio.clickSubdivision = preferences.resolvedClickSubdivision
        if audio.song == nil { audio.guideSource = preferences.resolvedGuideSource }
    }

    // MARK: - Library / loaded song

    func load(_ song: StageSong) {
        resetSongSessionState()
        audio.guideSource = song.guideSource ?? preferences.resolvedGuideSource
        audio.load(song: song, library: library)
        applyPreferencesToAudio()
        audio.guideSource = song.guideSource ?? preferences.resolvedGuideSource
        selectedTab = .live
        scheduledNativeEvents.removeAll()
        autoCuedSectionID = nil
    }

    func saveLoadedSong(_ edit: (inout StageSong) -> Void) {
        guard var song = audio.song else { return }
        edit(&song)
        song.sections.sort { $0.start < $1.start }
        for index in song.sections.indices {
            song.sections[index].end = index + 1 < song.sections.count ? song.sections[index + 1].start : song.duration
        }
        library.update(song)
        let keepPlaying = audio.isPlaying
        let oldPosition = audio.position
        audio.load(song: song, library: library, startAt: oldPosition)
        applyPreferencesToAudio()
        audio.guideSource = song.guideSource ?? preferences.resolvedGuideSource
        if keepPlaying { audio.play() }
    }

    func setGuideSource(_ source: StageGuideSource) {
        audio.guideSource = source
        if var song = audio.song {
            song.guideSource = source
            library.update(song)
        }
        scheduledNativeEvents.removeAll()
        autoCuedSectionID = nil
    }

    // MARK: - Manual sections / Cue Auto

    func selectSection(_ section: StageSection) {
        guard let song = audio.song else { return }
        if !audio.isPlaying {
            audio.seek(to: section.start, autoPlay: false)
            queuedSectionID = nil
            return
        }
        let current = currentSection(song)
        let boundary = max(audio.position, current?.end ?? audio.position)
        queuedSectionID = section.id
        scheduleCue(for: section, transitionBoundary: boundary)
        audio.queueSectionTransition(to: section.start, atSourceBoundary: boundary) { [weak self] in
            self?.queuedSectionID = nil
            self?.autoCuedSectionID = section.id
        }
    }

    func cancelQueuedSection() {
        // The native transition deck is intentionally not exposed as a public cancellation API once
        // its host-time start is imminent. Clearing the visual queue prevents further replacement;
        // selecting another section replaces the prepared transition safely.
        queuedSectionID = nil
    }

    func playSectionWithCountIn(_ section: StageSection) {
        audio.play(startAt: section.start, countInBars: preferences.countInBars)
    }

    private func scheduleCue(for section: StageSection, transitionBoundary: TimeInterval) {
        guard audio.guideEnabled, audio.guideSource == .cue,
              let song = audio.song,
              let language = guidePacks.resolveLanguage(preferred: song.resolvedNativeGuideLanguage == "auto" ? preferences.guideLanguage : song.resolvedNativeGuideLanguage) else { return }
        let beatSource = 60.0 / max(20, song.bpm)
        let barSource = beatSource * Double(max(1, song.beatsPerBar))
        let cueBarStart = transitionBoundary - barSource
        var scheduled: [(URL, TimeInterval)] = []

        if let sample = guidePacks.sample(language: language, key: section.name, kind: .section) {
            let delay = max(0.015, (cueBarStart - audio.position) / Double(max(0.01, audio.tempoRatio)))
            scheduled.append((sample.url, delay))
        }
        if section.addCount, song.beatsPerBar >= 2 {
            for beat in 2...song.beatsPerBar {
                if let count = guidePacks.sample(language: language, key: "\(beat)", kind: .count) {
                    let sourceTime = cueBarStart + Double(beat - 1) * beatSource
                    let delay = max(0.015, (sourceTime - audio.position) / Double(max(0.01, audio.tempoRatio)))
                    scheduled.append((count.url, delay))
                }
            }
        }
        audio.scheduleCueSequence(scheduled.map { (url: $0.0, delay: $0.1) })
    }

    // MARK: - Arrangement

    func startArrangement() {
        guard let song = audio.song, !song.resolvedArrangement.isEmpty else { return }
        let nodes = song.resolvedArrangement
        let currentID = currentSection(song)?.id
        let index = nodes.firstIndex(where: { $0.sectionID == currentID }) ?? 0
        let node = nodes[index]
        arrangementState = ArrangementState(active: true, activeNodeID: node.id, queuedNodeID: nil, iteration: 1, exitRequested: false)
        arrangementBoundaryScheduled = nil
        if !audio.isPlaying,
           let section = song.sections.first(where: { $0.id == node.sectionID }) {
            audio.play(startAt: section.start, countInBars: node.preRollBars)
        }
    }

    func stopArrangement() {
        arrangementState = ArrangementState()
        arrangementBoundaryScheduled = nil
    }

    func exitArrangementLoop() { arrangementState.exitRequested = true }

    func selectArrangementNode(_ nodeID: UUID) {
        guard let song = audio.song,
              let node = song.resolvedArrangement.first(where: { $0.id == nodeID }),
              let section = song.sections.first(where: { $0.id == node.sectionID }) else { return }
        arrangementState.queuedNodeID = nodeID
        selectSection(section)
    }

    func updateArrangement(_ nodes: [StageArrangementNode]) {
        saveLoadedSong { $0.arrangement = nodes }
    }

    // MARK: - Setlist Live

    func startSetlistLive(_ setlist: StageSetlist) {
        guard !setlist.songIDs.isEmpty else { return }
        let loadedID = audio.song?.id
        let index = loadedID.flatMap { setlist.songIDs.firstIndex(of: $0) } ?? 0
        setlistLiveState = .init(active: true, setlistID: setlist.id, currentIndex: index, nextReady: false, preloading: false, error: nil)
        if audio.song?.id != setlist.songIDs[index], let song = library.songs.first(where: { $0.id == setlist.songIDs[index] }) { load(song) }
        scheduleNextPreload()
    }

    func setlistNext() {
        guard let list = currentLiveSetlist(), setlistLiveState.active else { return }
        let nextIndex = setlistLiveState.currentIndex + 1
        guard list.songIDs.indices.contains(nextIndex),
              let nextSong = library.songs.first(where: { $0.id == list.songIDs[nextIndex] }) else { return }

        if audio.preloadedSongID == nextSong.id {
            setlistLiveState.nextReady = false
            audio.promotePreloadedSong(crossfade: 0.7) { [weak self] in
                guard let self else { return }
                self.setlistLiveState.currentIndex = nextIndex
                self.scheduledNativeEvents.removeAll()
                self.autoCuedSectionID = nil
                self.scheduleNextPreload()
            }
        } else {
            load(nextSong)
            setlistLiveState = .init(active: true, setlistID: list.id, currentIndex: nextIndex, nextReady: false, preloading: false, error: nil)
            scheduleNextPreload()
        }
    }

    func setlistPrevious() {
        guard let list = currentLiveSetlist(), setlistLiveState.active else { return }
        let previousIndex = setlistLiveState.currentIndex - 1
        guard list.songIDs.indices.contains(previousIndex),
              let previous = library.songs.first(where: { $0.id == list.songIDs[previousIndex] }) else { return }
        load(previous)
        setlistLiveState = .init(active: true, setlistID: list.id, currentIndex: previousIndex, nextReady: false, preloading: false, error: nil)
        scheduleNextPreload()
    }

    func exitSetlistLive() {
        audio.clearPreloadedSong()
        setlistLiveState = SetlistLiveState()
    }

    private func scheduleNextPreload() {
        guard let list = currentLiveSetlist() else { return }
        let nextIndex = setlistLiveState.currentIndex + 1
        guard list.songIDs.indices.contains(nextIndex),
              let song = library.songs.first(where: { $0.id == list.songIDs[nextIndex] }) else {
            audio.clearPreloadedSong()
            setlistLiveState.preloading = false
            setlistLiveState.nextReady = false
            return
        }
        setlistLiveState.preloading = true
        audio.preload(song: song, library: library)
        setlistLiveState.preloading = false
        setlistLiveState.nextReady = audio.preloadedSongID == song.id
        if !setlistLiveState.nextReady { setlistLiveState.error = audio.errorMessage }
    }

    private func currentLiveSetlist() -> StageSetlist? {
        guard let id = setlistLiveState.setlistID else { return nil }
        return library.setlists.first(where: { $0.id == id })
    }

    // MARK: - Native Guide

    func installGuidePack(_ url: URL) {
        do { _ = try guidePacks.install(zipURL: url); nativeGuideError = nil }
        catch { nativeGuideError = error.localizedDescription }
    }

    func reanalyzeNativeGuide() {
        guard !nativeGuideRunning, var song = audio.song,
              let guideTrack = song.tracks.first(where: { $0.kind == .guide }),
              guidePacks.status.installed else { return }
        let reference = library.trackURL(song: song, track: guideTrack)
        let samples = guidePacks.listSamples()
        nativeGuideRunning = true
        nativeGuideError = nil
        nativeGuideProgress = .init(fraction: 0, stage: "Preparando", detail: nil)
        let preferred = song.resolvedNativeGuideLanguage == "auto" ? preferences.guideLanguage : song.resolvedNativeGuideLanguage
        Task { [weak self] in
            guard let self else { return }
            do {
                let result = try await self.nativeGuideAnalyzer.analyze(
                    referenceURL: reference,
                    samples: samples,
                    preferredLanguage: preferred,
                    bpm: song.bpm,
                    beatsPerBar: song.beatsPerBar,
                    gridOffsetMs: song.resolvedGridOffsetMs
                ) { progress in
                    Task { @MainActor [weak self] in self?.nativeGuideProgress = progress }
                }
                song.nativeGuideAnalysis = result.0
                if song.sections.count <= 1, song.sections.first?.name == "Song", !result.1.isEmpty {
                    var inferred = result.1
                    if let lastIndex = inferred.indices.last { inferred[lastIndex].end = song.duration }
                    song.sections = inferred
                    song.arrangement = nil
                }
                self.library.update(song)
                let wasPlaying = self.audio.isPlaying
                let pos = self.audio.position
                self.audio.load(song: song, library: self.library, startAt: pos)
                self.applyPreferencesToAudio()
                self.audio.guideSource = song.guideSource ?? self.preferences.resolvedGuideSource
                if wasPlaying { self.audio.play() }
                self.nativeGuideProgress = .init(fraction: 1, stage: "Listo", detail: "\(result.0.events.count) cues")
            } catch {
                self.nativeGuideError = error.localizedDescription
            }
            self.nativeGuideRunning = false
        }
    }

    // MARK: - MIDI Learn/actions

    func beginMidiLearn(_ action: StageMidiAction) { midi.beginLearn(action) }

    private func performMidiAction(_ action: StageMidiAction) {
        switch action {
        case .playPause: audio.isPlaying ? audio.pause() : audio.play()
        case .stop: audio.stop(unload: false)
        case .stopAll: audio.stopAll()
        case .nextSong: setlistNext()
        case .previousSong: setlistPrevious()
        case .clickToggle:
            audio.clickEnabled.toggle()
            updatePreferences { $0.clickEnabled = audio.clickEnabled }
        case .guideToggle:
            audio.guideEnabled.toggle()
            updatePreferences { $0.guideEnabled = audio.guideEnabled }
        case .section(let id):
            if let section = audio.song?.sections.first(where: { $0.id == id }) { selectSection(section) }
        case .trackMute(let id):
            if let track = audio.song?.tracks.first(where: { $0.id == id }) { audio.setTrackMute(id, muted: !track.muted) }
        case .trackSolo(let id):
            if let track = audio.song?.tracks.first(where: { $0.id == id }) { audio.setTrackSolo(id, solo: !track.solo) }
        }
    }

    // MARK: - Playback monitor (auto cue + arrangement + Native Guide)

    private func monitorPlayback() {
        guard audio.isPlaying, let song = audio.song else { return }
        monitorAutoCue(song)
        monitorArrangement(song)
        monitorNativeGuide(song)
        if preferences.resolvedMidiClockOutputEnabled {
            // Clock is started/stopped by transport transitions. Here we only retune it when BPM changes.
            midi.updateClockBPM(song.bpm * Double(audio.tempoRatio))
        }
    }

    private func monitorAutoCue(_ song: StageSong) {
        guard audio.guideEnabled, audio.guideSource == .cue, queuedSectionID == nil else { return }
        let sorted = song.sections.sorted { $0.start < $1.start }
        guard let next = sorted.first(where: { $0.start > audio.position }) else { return }
        let bar = 60.0 / max(20, song.bpm) * Double(max(1, song.beatsPerBar))
        let distance = next.start - audio.position
        if distance <= bar + 0.10, distance > max(0.02, bar - 0.20), autoCuedSectionID != next.id {
            scheduleCue(for: next, transitionBoundary: next.start)
            autoCuedSectionID = next.id
        }
        if distance > bar * 1.5, autoCuedSectionID == next.id { autoCuedSectionID = nil }
    }

    private func monitorNativeGuide(_ song: StageSong) {
        guard audio.guideEnabled, audio.guideSource == .cue,
              let analysis = song.nativeGuideAnalysis,
              let language = guidePacks.resolveLanguage(preferred: song.resolvedNativeGuideLanguage, detected: analysis.detectedLanguage) else { return }
        let horizon: TimeInterval = 0.9
        for event in analysis.events where !scheduledNativeEvents.contains(event.id) {
            let delta = event.cueTime - audio.position
            guard delta >= 0, delta <= horizon else { continue }
            let kind = GuideCueKind(rawValue: event.kind) ?? .dynamic
            guard let sample = guidePacks.sample(language: language, key: event.key, kind: kind) else { continue }
            audio.scheduleCue(sampleURL: sample.url, afterWallSeconds: max(0.015, delta / Double(max(0.01, audio.tempoRatio))))
            scheduledNativeEvents.insert(event.id)
        }
        if audio.position < 0.25 { scheduledNativeEvents.removeAll() }
    }

    private func monitorArrangement(_ song: StageSong) {
        guard arrangementState.active else { return }
        let nodes = song.resolvedArrangement
        guard let activeID = arrangementState.activeNodeID,
              let activeIndex = nodes.firstIndex(where: { $0.id == activeID }),
              let section = song.sections.first(where: { $0.id == nodes[activeIndex].sectionID }) else { return }
        let boundary = section.end
        let remaining = boundary - audio.position
        guard remaining > 0, remaining <= 0.65 else { return }
        if arrangementBoundaryScheduled.map({ abs($0 - boundary) < 0.05 }) == true { return }
        arrangementBoundaryScheduled = boundary

        let node = nodes[activeIndex]
        var destinationNode: StageArrangementNode?
        var nextIteration = arrangementState.iteration
        if node.repeatCount < 0 && !arrangementState.exitRequested {
            destinationNode = node
            nextIteration += 1
        } else if node.repeatCount > 1 && arrangementState.iteration < min(16, node.repeatCount) {
            destinationNode = node
            nextIteration += 1
        } else if nodes.indices.contains(activeIndex + 1) {
            destinationNode = nodes[activeIndex + 1]
            nextIteration = 1
        }

        guard let destinationNode,
              let destination = song.sections.first(where: { $0.id == destinationNode.sectionID }) else {
            stopArrangement()
            return
        }
        arrangementState.queuedNodeID = destinationNode.id
        if destinationNode.guideEnabled { scheduleCue(for: destination, transitionBoundary: boundary) }
        audio.queueSectionTransition(to: destination.start, atSourceBoundary: boundary) { [weak self] in
            guard let self else { return }
            self.arrangementState.activeNodeID = destinationNode.id
            self.arrangementState.queuedNodeID = nil
            self.arrangementState.iteration = nextIteration
            self.arrangementState.exitRequested = false
            self.arrangementBoundaryScheduled = nil
        }
    }

    private func currentSection(_ song: StageSong) -> StageSection? {
        song.sections.sorted(by: { $0.start < $1.start }).last(where: { audio.position >= $0.start && audio.position < $0.end })
    }

    private func resetSongSessionState() {
        queuedSectionID = nil
        arrangementState = ArrangementState()
        arrangementBoundaryScheduled = nil
        scheduledNativeEvents.removeAll()
        autoCuedSectionID = nil
    }

    private func persistPreferences() {
        if let data = try? JSONEncoder().encode(preferences) {
            UserDefaults.standard.set(data, forKey: defaultsKey)
        }
    }
}
