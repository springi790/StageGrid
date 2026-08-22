import Foundation

@MainActor
extension AppModel {
    func playPause() {
        if audio.isPlaying {
            audio.pause()
            if preferences.resolvedMidiClockOutputEnabled { midi.stopClock(sendStop: true) }
        } else {
            audio.play()
            if preferences.resolvedMidiClockOutputEnabled, let song = audio.song {
                midi.startClock(bpm: song.bpm * Double(audio.tempoRatio))
            }
        }
    }

    func stopTransport() {
        audio.stop(unload: false)
        if preferences.resolvedMidiClockOutputEnabled { midi.stopClock(sendStop: true) }
    }

    func stopAllTransport() {
        audio.stopAll()
        midi.stopClock(sendStop: true)
    }
}
