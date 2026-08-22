import Foundation
import AVFoundation
import QuartzCore
import Darwin

@MainActor
final class StageGridAudioEngine: ObservableObject {
    @Published private(set) var song: StageSong?
    @Published private(set) var isPlaying = false
    @Published private(set) var position: TimeInterval = 0
    @Published private(set) var duration: TimeInterval = 0
    @Published private(set) var tempoRatio: Float = 1
    @Published private(set) var pitchSemitones: Float = 0
    @Published var clickEnabled = true { didSet { applyMixState() } }
    @Published var guideEnabled = true { didSet { applyMixState() } }
    @Published var guideSource: StageGuideSource = .original { didSet { applyMixState() } }
    @Published var clickSubdivision: StageClickSubdivision = .quarter { didSet { rescheduleClickIfPlaying() } }
    @Published var masterVolume: Float = 1 { didSet { engine.mainMixerNode.outputVolume = masterVolume } }
    @Published private(set) var preloadedSongID: UUID?
    @Published private(set) var preloadedSongTitle: String?
    @Published private(set) var crossfadeInProgress = false
    @Published private(set) var countInRemaining: TimeInterval = 0
    @Published private(set) var outputChannelCount = 2
    @Published private(set) var outputName = "System Output"
    @Published private(set) var errorMessage: String?

    private struct Channel {
        let trackID: UUID
        let kind: TrackKind
        let file: AVAudioFile
        let player: AVAudioPlayerNode
        let timePitch: AVAudioUnitTimePitch
    }

    private final class CueVoice {
        let player = AVAudioPlayerNode()
    }

    private final class Deck {
        var song: StageSong
        var channels: [UUID: Channel] = [:]
        let mixer = AVAudioMixerNode()
        let clickPlayer = AVAudioPlayerNode()
        let clickPitch = AVAudioUnitTimePitch()
        var clickBuffer: AVAudioPCMBuffer?
        var cueVoices: [CueVoice] = []
        var cueCursor = 0
        var tempoRatio: Float = 1
        var pitchSemitones: Float = 0
        var basePosition: TimeInterval = 0
        var wallClockStarted: CFTimeInterval?
        var library: LibraryStore

        init(song: StageSong, library: LibraryStore) {
            self.song = song
            self.library = library
        }
    }

    private let engine = AVAudioEngine()
    private var activeDeck: Deck?
    private var standbyDeck: Deck?
    private var transitionDeck: Deck?
    private var timer: Timer?
    private var countInTask: Task<Void, Never>?
    private var crossfadeTask: Task<Void, Never>?
    private var sectionTask: Task<Void, Never>?
    private var routeObserver: NSObjectProtocol?

    init() {
        configureSession()
        engine.mainMixerNode.outputVolume = masterVolume
        routeObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.refreshOutputInfo() }
        }
        refreshOutputInfo()
    }

    deinit {
        timer?.invalidate()
        if let routeObserver { NotificationCenter.default.removeObserver(routeObserver) }
    }

    // MARK: - Song/deck lifecycle

    func load(song: StageSong, library: LibraryStore, startAt: TimeInterval = 0) {
        cancelPendingOperations()
        destroyDeck(activeDeck)
        activeDeck = nil
        clearPreloadedSong()
        do {
            let deck = try buildDeck(song: song, library: library)
            deck.basePosition = min(max(0, startAt), song.duration)
            deck.tempoRatio = 1
            deck.pitchSemitones = 0
            deck.mixer.outputVolume = 1
            activeDeck = deck
            publish(deck: deck, playing: false)
            try startEngineIfNeeded()
            applyDSP(deck)
            applyMixState(deck)
            schedule(deck: deck, at: deck.basePosition, autoPlay: false)
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
            destroyDeck(activeDeck)
            activeDeck = nil
            song = nil
        }
    }

    func preload(song: StageSong, library: LibraryStore) {
        destroyDeck(standbyDeck)
        standbyDeck = nil
        do {
            let deck = try buildDeck(song: song, library: library)
            deck.mixer.outputVolume = 0
            deck.tempoRatio = 1
            deck.pitchSemitones = 0
            applyDSP(deck)
            applyMixState(deck)
            schedule(deck: deck, at: 0, autoPlay: false)
            standbyDeck = deck
            preloadedSongID = song.id
            preloadedSongTitle = song.title
            try startEngineIfNeeded()
            errorMessage = nil
        } catch {
            errorMessage = "Preload: \(error.localizedDescription)"
            destroyDeck(standbyDeck)
            standbyDeck = nil
            preloadedSongID = nil
            preloadedSongTitle = nil
        }
    }

    func clearPreloadedSong() {
        destroyDeck(standbyDeck)
        standbyDeck = nil
        preloadedSongID = nil
        preloadedSongTitle = nil
    }

    func promotePreloadedSong(crossfade seconds: TimeInterval = 0.7, completion: (() -> Void)? = nil) {
        guard let next = standbyDeck else { return }
        crossfadeTask?.cancel()
        sectionTask?.cancel()
        let previous = activeDeck
        standbyDeck = nil
        preloadedSongID = nil
        preloadedSongTitle = nil

        guard isPlaying, let previous else {
            destroyDeck(previous)
            next.mixer.outputVolume = 1
            next.basePosition = 0
            next.wallClockStarted = nil
            activeDeck = next
            publish(deck: next, playing: false)
            schedule(deck: next, at: 0, autoPlay: false)
            completion?()
            return
        }

        let start = commonStartTime(leadSeconds: 0.075)
        schedule(deck: next, at: 0, autoPlay: true, startTime: start)
        next.basePosition = 0
        next.wallClockStarted = CACurrentMediaTime() + 0.075
        next.mixer.outputVolume = 0
        crossfadeInProgress = true

        crossfadeTask = Task { [weak self, weak previous, weak next] in
            guard let self, let previous, let next else { return }
            let duration = max(0.08, seconds)
            let steps = max(8, Int(duration / 0.012))
            for index in 0...steps {
                if Task.isCancelled { return }
                let x = Float(index) / Float(steps)
                let smooth = x * x * (3 - 2 * x)
                previous.mixer.outputVolume = 1 - smooth
                next.mixer.outputVolume = smooth
                if index < steps {
                    try? await Task.sleep(nanoseconds: UInt64(duration / Double(steps) * 1_000_000_000))
                }
            }
            previous.channels.values.forEach { $0.player.stop() }
            previous.clickPlayer.stop()
            self.destroyDeck(previous)
            self.activeDeck = next
            next.mixer.outputVolume = 1
            self.publish(deck: next, playing: true)
            self.crossfadeInProgress = false
            self.startTimer()
            completion?()
        }
    }

    private func buildDeck(song: StageSong, library: LibraryStore) throws -> Deck {
        let deck = Deck(song: song, library: library)
        engine.attach(deck.mixer)
        engine.connect(deck.mixer, to: engine.mainMixerNode, format: nil)

        for track in song.tracks {
            let url = library.trackURL(song: song, track: track)
            let file = try AVAudioFile(forReading: url)
            let player = AVAudioPlayerNode()
            let processor = AVAudioUnitTimePitch()
            engine.attach(player)
            engine.attach(processor)
            engine.connect(player, to: processor, format: file.processingFormat)
            engine.connect(processor, to: deck.mixer, format: nil)
            deck.channels[track.id] = Channel(
                trackID: track.id,
                kind: track.kind,
                file: file,
                player: player,
                timePitch: processor
            )
        }

        engine.attach(deck.clickPlayer)
        engine.attach(deck.clickPitch)
        let clickFormat = AVAudioFormat(standardFormatWithSampleRate: 48_000, channels: 2)!
        engine.connect(deck.clickPlayer, to: deck.clickPitch, format: clickFormat)
        engine.connect(deck.clickPitch, to: deck.mixer, format: clickFormat)

        for _ in 0..<8 {
            let voice = CueVoice()
            engine.attach(voice.player)
            engine.connect(voice.player, to: deck.mixer, format: nil)
            deck.cueVoices.append(voice)
        }
        return deck
    }

    private func destroyDeck(_ deck: Deck?) {
        guard let deck else { return }
        deck.channels.values.forEach {
            $0.player.stop()
            engine.disconnectNodeOutput($0.player)
            engine.disconnectNodeOutput($0.timePitch)
            engine.detach($0.player)
            engine.detach($0.timePitch)
        }
        deck.clickPlayer.stop()
        engine.disconnectNodeOutput(deck.clickPlayer)
        engine.disconnectNodeOutput(deck.clickPitch)
        engine.detach(deck.clickPlayer)
        engine.detach(deck.clickPitch)
        for voice in deck.cueVoices {
            voice.player.stop()
            engine.disconnectNodeOutput(voice.player)
            engine.detach(voice.player)
        }
        engine.disconnectNodeOutput(deck.mixer)
        engine.detach(deck.mixer)
    }

    // MARK: - Transport

    func play() {
        guard let deck = activeDeck, !isPlaying else { return }
        do {
            try startEngineIfNeeded()
            schedule(deck: deck, at: position, autoPlay: true)
            isPlaying = true
            deck.basePosition = position
            deck.wallClockStarted = CACurrentMediaTime() + 0.075
            startTimer()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func play(startAt sourceSeconds: TimeInterval, countInBars: Int) {
        guard let deck = activeDeck else { return }
        countInTask?.cancel()
        let bars = min(max(countInBars, 0), 2)
        if bars == 0 {
            seek(to: sourceSeconds, autoPlay: true)
            return
        }
        let barSeconds = 60.0 / max(20, deck.song.bpm) * Double(max(1, deck.song.beatsPerBar)) / Double(max(0.01, deck.tempoRatio))
        let delay = barSeconds * Double(bars)
        stopPlayers(deck)
        position = sourceSeconds
        deck.basePosition = sourceSeconds
        deck.wallClockStarted = nil
        scheduleClickOnly(deck: deck, sourceSeconds: max(0, sourceSeconds - barSeconds), autoPlay: true, startTime: commonStartTime(leadSeconds: 0.02))
        countInRemaining = delay
        isPlaying = false
        countInTask = Task { [weak self, weak deck] in
            guard let self, let deck else { return }
            let start = CACurrentMediaTime()
            while !Task.isCancelled {
                let remaining = delay - (CACurrentMediaTime() - start)
                if remaining <= 0 { break }
                self.countInRemaining = remaining
                try? await Task.sleep(nanoseconds: 50_000_000)
            }
            guard !Task.isCancelled else { return }
            deck.clickPlayer.stop()
            self.countInRemaining = 0
            self.position = sourceSeconds
            self.schedule(deck: deck, at: sourceSeconds, autoPlay: true)
            self.isPlaying = true
            deck.basePosition = sourceSeconds
            deck.wallClockStarted = CACurrentMediaTime() + 0.075
            self.startTimer()
        }
    }

    func pause() {
        guard let deck = activeDeck, isPlaying else { return }
        captureCurrentPosition()
        deck.channels.values.forEach { $0.player.pause() }
        deck.clickPlayer.pause()
        deck.cueVoices.forEach { $0.player.pause() }
        isPlaying = false
        deck.wallClockStarted = nil
    }

    func stop(unload: Bool = false) {
        cancelPendingOperations()
        if let deck = activeDeck {
            stopPlayers(deck)
            deck.basePosition = 0
            deck.wallClockStarted = nil
        }
        isPlaying = false
        countInRemaining = 0
        timer?.invalidate()
        timer = nil
        position = 0
        if unload {
            destroyDeck(activeDeck)
            activeDeck = nil
            song = nil
            duration = 0
        }
    }

    func stopAll() {
        stop(unload: false)
        clearPreloadedSong()
        destroyDeck(transitionDeck)
        transitionDeck = nil
    }

    func seek(to seconds: TimeInterval, autoPlay: Bool? = nil) {
        guard let deck = activeDeck else { return }
        sectionTask?.cancel()
        destroyDeck(transitionDeck)
        transitionDeck = nil
        let shouldPlay = autoPlay ?? isPlaying
        stopPlayers(deck)
        position = min(max(0, seconds), duration)
        deck.basePosition = position
        deck.wallClockStarted = nil
        schedule(deck: deck, at: position, autoPlay: shouldPlay)
        isPlaying = shouldPlay
        if shouldPlay {
            deck.wallClockStarted = CACurrentMediaTime() + 0.075
            startTimer()
        }
    }

    /// Prepares a second copy of the current song and swaps it at the authored source boundary.
    /// A 6 ms smooth crossfade hides phase/decoder discontinuities on large PA systems.
    func queueSectionTransition(to target: TimeInterval, atSourceBoundary boundary: TimeInterval, completion: (() -> Void)? = nil) {
        guard let deck = activeDeck, isPlaying else {
            seek(to: target, autoPlay: false)
            completion?()
            return
        }
        sectionTask?.cancel()
        destroyDeck(transitionDeck)
        transitionDeck = nil
        do {
            let next = try buildDeck(song: deck.song, library: deck.library)
            next.tempoRatio = deck.tempoRatio
            next.pitchSemitones = deck.pitchSemitones
            next.mixer.outputVolume = 0
            applyDSP(next)
            applyMixState(next)
            transitionDeck = next

            let remainingSource = max(0, boundary - position)
            let wallDelay = remainingSource / Double(max(0.01, deck.tempoRatio))
            let startTime = commonStartTime(leadSeconds: wallDelay)
            schedule(deck: next, at: target, autoPlay: true, startTime: startTime)
            next.basePosition = target
            next.wallClockStarted = CACurrentMediaTime() + wallDelay

            sectionTask = Task { [weak self, weak deck, weak next] in
                guard let self, let deck, let next else { return }
                if wallDelay > 0.003 {
                    try? await Task.sleep(nanoseconds: UInt64((wallDelay - 0.003) * 1_000_000_000))
                }
                if Task.isCancelled { return }
                let steps = 6
                for i in 0...steps {
                    let x = Float(i) / Float(steps)
                    let smooth = x * x * (3 - 2 * x)
                    deck.mixer.outputVolume = 1 - smooth
                    next.mixer.outputVolume = smooth
                    if i < steps { try? await Task.sleep(nanoseconds: 1_000_000) }
                }
                guard !Task.isCancelled else { return }
                self.destroyDeck(deck)
                self.activeDeck = next
                self.transitionDeck = nil
                next.mixer.outputVolume = 1
                self.song = next.song
                self.duration = next.song.duration
                self.position = target
                self.isPlaying = true
                self.tempoRatio = next.tempoRatio
                self.pitchSemitones = next.pitchSemitones
                self.startTimer()
                completion?()
            }
        } catch {
            errorMessage = "Section transition: \(error.localizedDescription)"
        }
    }

    // MARK: - DSP / mixer

    func setTempoRatio(_ ratio: Float) {
        captureCurrentPosition()
        guard let deck = activeDeck else { return }
        deck.tempoRatio = min(max(ratio, 0.75), 1.5)
        tempoRatio = deck.tempoRatio
        applyDSP(deck)
        if isPlaying {
            deck.wallClockStarted = CACurrentMediaTime()
            deck.basePosition = position
            rescheduleClickIfPlaying()
        }
    }

    func setPitchSemitones(_ semitones: Float) {
        guard let deck = activeDeck else { return }
        deck.pitchSemitones = min(max(semitones, -12), 12)
        pitchSemitones = deck.pitchSemitones
        applyDSP(deck)
    }

    func setTrackVolume(_ trackID: UUID, volume: Float) {
        mutateTrack(trackID) { $0.volume = min(max(volume, 0), 1.5) }
    }

    func setTrackMute(_ trackID: UUID, muted: Bool) {
        mutateTrack(trackID) { $0.muted = muted }
    }

    func setTrackSolo(_ trackID: UUID, solo: Bool) {
        mutateTrack(trackID) { $0.solo = solo }
    }

    func setTrackPan(_ trackID: UUID, pan: Float) {
        mutateTrack(trackID) { $0.pan = min(max(pan, -1), 1) }
    }

    func setTrackOutput(_ trackID: UUID, bus: Int, route: StageStereoRoute) {
        mutateTrack(trackID) {
            $0.outputBus = min(max(bus, 0), 3)
            $0.stereoRoute = route
        }
        // AVAudioEngine stereo output remains the guaranteed fallback. Multi-channel hardware
        // capability is exposed in outputChannelCount; discrete matrix routing is applied by
        // MultiOutputRouter when a qualifying Core Audio route is present.
    }

    private func mutateTrack(_ trackID: UUID, edit: (inout StageTrack) -> Void) {
        guard let deck = activeDeck,
              let index = deck.song.tracks.firstIndex(where: { $0.id == trackID }) else { return }
        edit(&deck.song.tracks[index])
        song = deck.song
        applyMixState(deck)
        deck.library.update(deck.song)
    }

    private func applyDSP(_ deck: Deck) {
        for channel in deck.channels.values {
            channel.timePitch.rate = deck.tempoRatio
            channel.timePitch.pitch = (channel.kind == .guide || channel.kind == .click) ? 0 : deck.pitchSemitones * 100
        }
        deck.clickPitch.rate = deck.tempoRatio
        deck.clickPitch.pitch = 0
    }

    private func applyMixState() {
        if let deck = activeDeck { applyMixState(deck) }
    }

    private func applyMixState(_ deck: Deck) {
        let anySolo = deck.song.tracks.contains(where: { $0.solo })
        for track in deck.song.tracks {
            guard let channel = deck.channels[track.id] else { continue }
            let hiddenBySolo = anySolo && !track.solo
            let hideImportedGuide = track.kind == .guide && (!guideEnabled || guideSource == .cue)
            let hiddenClickTrack = track.kind == .click && !clickEnabled
            channel.player.volume = (track.muted || hiddenBySolo || hideImportedGuide || hiddenClickTrack) ? 0 : track.volume
            channel.player.pan = track.pan
        }
        deck.clickPlayer.volume = clickEnabled ? 0.72 : 0
    }

    // MARK: - Guide/Cue scheduling

    func scheduleCue(sampleURL: URL, afterWallSeconds delay: TimeInterval, volume: Float = 1) {
        guard guideEnabled, let deck = activeDeck, !deck.cueVoices.isEmpty else { return }
        do {
            let file = try AVAudioFile(forReading: sampleURL)
            let voice = deck.cueVoices[deck.cueCursor % deck.cueVoices.count]
            deck.cueCursor = (deck.cueCursor + 1) % deck.cueVoices.count
            voice.player.stop()
            voice.player.volume = min(max(volume, 0), 1.5)
            voice.player.scheduleFile(file, at: nil)
            voice.player.play(at: commonStartTime(leadSeconds: max(0.015, delay)))
        } catch {
            errorMessage = "Cue: \(error.localizedDescription)"
        }
    }

    func scheduleCueSequence(_ events: [(url: URL, delay: TimeInterval)]) {
        guard guideEnabled else { return }
        for event in events { scheduleCue(sampleURL: event.url, afterWallSeconds: event.delay) }
    }

    // MARK: - Scheduling internals

    private func schedule(deck: Deck, at sourceSeconds: TimeInterval, autoPlay: Bool, startTime: AVAudioTime? = nil) {
        let time = startTime ?? commonStartTime(leadSeconds: 0.075)
        for channel in deck.channels.values {
            channel.player.stop()
            let sourceRate = channel.file.processingFormat.sampleRate
            let startFrame = AVAudioFramePosition(max(0, sourceSeconds) * sourceRate)
            let remaining = max(0, channel.file.length - startFrame)
            guard remaining > 0 else { continue }
            channel.player.scheduleSegment(
                channel.file,
                startingFrame: startFrame,
                frameCount: AVAudioFrameCount(min(Int64(UInt32.max), remaining)),
                at: nil
            )
            if autoPlay { channel.player.play(at: time) }
        }
        scheduleClick(deck: deck, sourceSeconds: sourceSeconds, at: time, autoPlay: autoPlay)
    }

    private func scheduleClick(deck: Deck, sourceSeconds: TimeInterval, at startTime: AVAudioTime, autoPlay: Bool) {
        deck.clickPlayer.stop()
        guard let buffer = makeClickBar(song: deck.song, sourceSeconds: sourceSeconds) else { return }
        deck.clickBuffer = buffer
        deck.clickPlayer.scheduleBuffer(buffer, at: nil, options: .loops)
        deck.clickPlayer.volume = clickEnabled ? 0.72 : 0
        if autoPlay { deck.clickPlayer.play(at: startTime) }
    }

    private func scheduleClickOnly(deck: Deck, sourceSeconds: TimeInterval, autoPlay: Bool, startTime: AVAudioTime) {
        scheduleClick(deck: deck, sourceSeconds: sourceSeconds, at: startTime, autoPlay: autoPlay)
    }

    private func makeClickBar(song: StageSong, sourceSeconds: TimeInterval) -> AVAudioPCMBuffer? {
        let sampleRate = 48_000.0
        let beats = max(1, song.beatsPerBar)
        let beatDuration = 60.0 / max(20, song.bpm)
        let subdivisions = max(1, clickSubdivision.rawValue)
        let barDuration = beatDuration * Double(beats)
        let frames = AVAudioFrameCount(max(1, Int(barDuration * sampleRate)))
        guard let format = AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 2),
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames) else { return nil }
        buffer.frameLength = frames
        guard let left = buffer.floatChannelData?[0], let right = buffer.floatChannelData?[1] else { return nil }
        for i in 0..<Int(frames) { left[i] = 0; right[i] = 0 }

        let phase = sourceSeconds.truncatingRemainder(dividingBy: barDuration)
        for beat in 0..<beats {
            for subdivision in 0..<subdivisions {
                var offset = Double(beat) * beatDuration + Double(subdivision) * beatDuration / Double(subdivisions) - phase
                while offset < 0 { offset += barDuration }
                while offset >= barDuration { offset -= barDuration }
                let start = Int(offset * sampleRate)
                let burst = min(Int(sampleRate * 0.018), Int(frames) - start)
                if burst <= 0 { continue }
                let downbeat = beat == 0 && subdivision == 0
                let mainBeat = subdivision == 0
                let frequency = downbeat ? 1900.0 : (mainBeat ? 1350.0 : 1050.0)
                let gain = downbeat ? 0.52 : (mainBeat ? 0.40 : 0.24)
                for n in 0..<burst {
                    let t = Double(n) / sampleRate
                    let sample = Float(sin(2 * Double.pi * frequency * t) * exp(-t * 115) * gain)
                    left[start + n] += sample
                    right[start + n] += sample
                }
            }
        }
        return buffer
    }

    private func rescheduleClickIfPlaying() {
        guard isPlaying, let deck = activeDeck else { return }
        let start = commonStartTime(leadSeconds: 0.02)
        scheduleClick(deck: deck, sourceSeconds: position, at: start, autoPlay: true)
    }

    private func stopPlayers(_ deck: Deck) {
        deck.channels.values.forEach { $0.player.stop() }
        deck.clickPlayer.stop()
        deck.cueVoices.forEach { $0.player.stop() }
    }

    private func publish(deck: Deck, playing: Bool) {
        song = deck.song
        duration = deck.song.duration
        position = deck.basePosition
        tempoRatio = deck.tempoRatio
        pitchSemitones = deck.pitchSemitones
        isPlaying = playing
    }

    private func configureSession() {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, mode: .default, options: [.allowAirPlay, .allowBluetoothA2DP])
            try session.setPreferredIOBufferDuration(0.005)
            try session.setActive(true)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func refreshOutputInfo() {
        let session = AVAudioSession.sharedInstance()
        let output = session.currentRoute.outputs.first
        outputName = output?.portName ?? "System Output"
        outputChannelCount = max(2, output?.channels?.count ?? session.outputNumberOfChannels)
    }

    private func startEngineIfNeeded() throws {
        if !engine.isRunning {
            engine.prepare()
            try engine.start()
        }
    }

    private func commonStartTime(leadSeconds: TimeInterval) -> AVAudioTime {
        let lead = AVAudioTime.hostTime(forSeconds: max(0, leadSeconds))
        return AVAudioTime(hostTime: mach_absolute_time() + lead)
    }

    private func captureCurrentPosition() {
        guard isPlaying, let deck = activeDeck, let started = deck.wallClockStarted else { return }
        let elapsed = max(0, CACurrentMediaTime() - started)
        position = min(duration, deck.basePosition + elapsed * Double(deck.tempoRatio))
        deck.basePosition = position
    }

    private func startTimer() {
        if timer != nil { return }
        timer = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.isPlaying, let deck = self.activeDeck, let started = deck.wallClockStarted else { return }
                self.position = min(self.duration, deck.basePosition + max(0, CACurrentMediaTime() - started) * Double(deck.tempoRatio))
                if self.position >= self.duration {
                    self.stop(unload: false)
                }
            }
        }
    }

    private func cancelPendingOperations() {
        countInTask?.cancel(); countInTask = nil
        crossfadeTask?.cancel(); crossfadeTask = nil
        sectionTask?.cancel(); sectionTask = nil
        crossfadeInProgress = false
        countInRemaining = 0
    }
}
