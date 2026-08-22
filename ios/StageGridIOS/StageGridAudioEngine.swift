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
    @Published var masterVolume: Float = 1 { didSet { engine.mainMixerNode.outputVolume = masterVolume } }
    @Published private(set) var errorMessage: String?

    private struct Channel {
        let track: StageTrack
        let file: AVAudioFile
        let player: AVAudioPlayerNode
        let timePitch: AVAudioUnitTimePitch
    }

    private let engine = AVAudioEngine()
    private var channels: [UUID: Channel] = [:]
    private let clickPlayer = AVAudioPlayerNode()
    private let clickPitch = AVAudioUnitTimePitch()
    private var clickBuffer: AVAudioPCMBuffer?
    private var timer: Timer?
    private var basePosition: TimeInterval = 0
    private var wallClockStarted: CFTimeInterval?
    private var currentLibrary: LibraryStore?

    init() {
        configureSession()
        engine.attach(clickPlayer)
        engine.attach(clickPitch)
        let format = AVAudioFormat(standardFormatWithSampleRate: 48_000, channels: 2)!
        engine.connect(clickPlayer, to: clickPitch, format: format)
        engine.connect(clickPitch, to: engine.mainMixerNode, format: format)
        clickPitch.pitch = 0
        engine.mainMixerNode.outputVolume = masterVolume
    }

    deinit { timer?.invalidate() }

    func load(song: StageSong, library: LibraryStore, startAt: TimeInterval = 0) {
        stop(unload: true)
        currentLibrary = library
        do {
            for track in song.tracks {
                let url = library.trackURL(song: song, track: track)
                let file = try AVAudioFile(forReading: url)
                let player = AVAudioPlayerNode()
                let processor = AVAudioUnitTimePitch()
                engine.attach(player)
                engine.attach(processor)
                engine.connect(player, to: processor, format: file.processingFormat)
                engine.connect(processor, to: engine.mainMixerNode, format: nil)
                channels[track.id] = Channel(track: track, file: file, player: player, timePitch: processor)
            }
            self.song = song
            duration = song.duration
            tempoRatio = 1
            pitchSemitones = 0
            position = min(max(0, startAt), duration)
            basePosition = position
            try startEngineIfNeeded()
            applyDSP()
            applyMixState()
            scheduleAll(at: position, autoPlay: false)
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
            self.song = nil
        }
    }

    func play() {
        guard song != nil, !isPlaying else { return }
        do {
            try startEngineIfNeeded()
            scheduleAll(at: position, autoPlay: true)
            isPlaying = true
            basePosition = position
            wallClockStarted = CACurrentMediaTime()
            startTimer()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func pause() {
        guard isPlaying else { return }
        captureCurrentPosition()
        channels.values.forEach { $0.player.pause() }
        clickPlayer.pause()
        isPlaying = false
        wallClockStarted = nil
    }

    func stop(unload: Bool = false) {
        channels.values.forEach { $0.player.stop() }
        clickPlayer.stop()
        isPlaying = false
        wallClockStarted = nil
        timer?.invalidate()
        timer = nil
        position = 0
        basePosition = 0
        if unload {
            for channel in channels.values {
                engine.disconnectNodeOutput(channel.player)
                engine.disconnectNodeOutput(channel.timePitch)
                engine.detach(channel.player)
                engine.detach(channel.timePitch)
            }
            channels.removeAll()
            song = nil
            duration = 0
        }
    }

    func seek(to seconds: TimeInterval, autoPlay: Bool? = nil) {
        guard song != nil else { return }
        let shouldPlay = autoPlay ?? isPlaying
        channels.values.forEach { $0.player.stop() }
        clickPlayer.stop()
        position = min(max(0, seconds), duration)
        basePosition = position
        wallClockStarted = nil
        scheduleAll(at: position, autoPlay: shouldPlay)
        isPlaying = shouldPlay
        if shouldPlay {
            wallClockStarted = CACurrentMediaTime()
            startTimer()
        }
    }

    func setTempoRatio(_ ratio: Float) {
        captureCurrentPosition()
        tempoRatio = min(max(ratio, 0.75), 1.5)
        applyDSP()
        if isPlaying { wallClockStarted = CACurrentMediaTime(); basePosition = position }
    }

    func setPitchSemitones(_ semitones: Float) {
        pitchSemitones = min(max(semitones, -12), 12)
        applyDSP()
    }

    func setTrackVolume(_ trackID: UUID, volume: Float) {
        guard var loadedSong = song, let index = loadedSong.tracks.firstIndex(where: { $0.id == trackID }) else { return }
        loadedSong.tracks[index].volume = min(max(volume, 0), 1.5)
        song = loadedSong
        applyMixState()
        currentLibrary?.update(loadedSong)
    }

    func setTrackMute(_ trackID: UUID, muted: Bool) {
        guard var loadedSong = song, let index = loadedSong.tracks.firstIndex(where: { $0.id == trackID }) else { return }
        loadedSong.tracks[index].muted = muted
        song = loadedSong
        applyMixState()
        currentLibrary?.update(loadedSong)
    }

    func setTrackSolo(_ trackID: UUID, solo: Bool) {
        guard var loadedSong = song, let index = loadedSong.tracks.firstIndex(where: { $0.id == trackID }) else { return }
        loadedSong.tracks[index].solo = solo
        song = loadedSong
        applyMixState()
        currentLibrary?.update(loadedSong)
    }

    func setTrackPan(_ trackID: UUID, pan: Float) {
        guard var loadedSong = song, let index = loadedSong.tracks.firstIndex(where: { $0.id == trackID }) else { return }
        loadedSong.tracks[index].pan = min(max(pan, -1), 1)
        song = loadedSong
        applyMixState()
        currentLibrary?.update(loadedSong)
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

    private func startEngineIfNeeded() throws {
        if !engine.isRunning {
            engine.prepare()
            try engine.start()
        }
    }

    private func commonStartTime() -> AVAudioTime {
        let lead = AVAudioTime.hostTime(forSeconds: 0.075)
        return AVAudioTime(hostTime: mach_absolute_time() + lead)
    }

    private func scheduleAll(at sourceSeconds: TimeInterval, autoPlay: Bool) {
        guard let song else { return }
        let startTime = commonStartTime()
        for channel in channels.values {
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
            if autoPlay { channel.player.play(at: startTime) }
        }
        scheduleClick(song: song, sourceSeconds: sourceSeconds, at: startTime, autoPlay: autoPlay)
    }

    private func scheduleClick(song: StageSong, sourceSeconds: TimeInterval, at startTime: AVAudioTime, autoPlay: Bool) {
        clickPlayer.stop()
        guard let buffer = makeClickBar(song: song, sourceSeconds: sourceSeconds) else { return }
        clickBuffer = buffer
        clickPlayer.scheduleBuffer(buffer, at: nil, options: .loops)
        clickPlayer.volume = clickEnabled ? 0.72 : 0
        if autoPlay { clickPlayer.play(at: startTime) }
    }

    private func makeClickBar(song: StageSong, sourceSeconds: TimeInterval) -> AVAudioPCMBuffer? {
        let sampleRate = 48_000.0
        let beats = max(1, song.beatsPerBar)
        let beatDuration = 60.0 / max(20, song.bpm)
        let barDuration = beatDuration * Double(beats)
        let frames = AVAudioFrameCount(max(1, Int(barDuration * sampleRate)))
        guard let format = AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 2),
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames) else { return nil }
        buffer.frameLength = frames
        guard let left = buffer.floatChannelData?[0], let right = buffer.floatChannelData?[1] else { return nil }
        for i in 0..<Int(frames) { left[i] = 0; right[i] = 0 }

        let phase = sourceSeconds.truncatingRemainder(dividingBy: barDuration)
        for beat in 0..<beats {
            var offset = Double(beat) * beatDuration - phase
            if offset < 0 { offset += barDuration }
            let start = Int(offset * sampleRate)
            let burst = min(Int(sampleRate * 0.022), Int(frames) - start)
            if burst <= 0 { continue }
            let frequency = beat == 0 ? 1900.0 : 1350.0
            for n in 0..<burst {
                let t = Double(n) / sampleRate
                let sample = Float(sin(2 * Double.pi * frequency * t) * exp(-t * 105) * (beat == 0 ? 0.52 : 0.40))
                left[start + n] = sample
                right[start + n] = sample
            }
        }
        return buffer
    }

    private func applyDSP() {
        for channel in channels.values {
            channel.timePitch.rate = tempoRatio
            channel.timePitch.pitch = (channel.track.kind == .guide || channel.track.kind == .click) ? 0 : pitchSemitones * 100
        }
        clickPitch.rate = tempoRatio
        clickPitch.pitch = 0
    }

    private func applyMixState() {
        guard let song else { return }
        let anySolo = song.tracks.contains(where: { $0.solo })
        for track in song.tracks {
            guard let channel = channels[track.id] else { continue }
            let hiddenBySolo = anySolo && !track.solo
            let hiddenGuide = track.kind == .guide && !guideEnabled
            let hiddenClick = track.kind == .click && !clickEnabled
            channel.player.volume = (track.muted || hiddenBySolo || hiddenGuide || hiddenClick) ? 0 : track.volume
            channel.player.pan = track.pan
        }
        clickPlayer.volume = clickEnabled ? 0.72 : 0
    }

    private func captureCurrentPosition() {
        guard isPlaying, let started = wallClockStarted else { return }
        let elapsed = CACurrentMediaTime() - started
        position = min(duration, basePosition + elapsed * Double(tempoRatio))
        basePosition = position
    }

    private func startTimer() {
        if timer != nil { return }
        timer = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.isPlaying, let started = self.wallClockStarted else { return }
                self.position = min(self.duration, self.basePosition + (CACurrentMediaTime() - started) * Double(self.tempoRatio))
                if self.position >= self.duration {
                    self.stop(unload: false)
                }
            }
        }
    }
}
