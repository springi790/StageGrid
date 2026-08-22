import Foundation

private let stageSessionStore = SessionStore()

@MainActor
extension AppModel {
    func persistSession() {
        guard let song = audio.song, !audio.crossfadeInProgress else { return }
        let snapshot = StageSessionSnapshot(
            songID: song.id,
            position: min(max(0, audio.position), song.duration),
            clickEnabled: audio.clickEnabled,
            guideEnabled: audio.guideEnabled,
            guideSource: audio.guideSource,
            tempoRatio: audio.tempoRatio,
            pitchSemitones: audio.pitchSemitones,
            setlistID: setlistLiveState.active ? setlistLiveState.setlistID : nil,
            setlistIndex: setlistLiveState.active ? setlistLiveState.currentIndex : nil,
            savedAt: Date()
        )
        stageSessionStore.write(snapshot)
    }

    func restoreSessionIfNeeded() {
        guard preferences.setupComplete,
              audio.song == nil,
              let snapshot = stageSessionStore.read(),
              let song = library.songs.first(where: { $0.id == snapshot.songID }) else { return }

        load(song)
        audio.clickEnabled = snapshot.clickEnabled
        audio.guideEnabled = snapshot.guideEnabled
        audio.guideSource = snapshot.guideSource
        audio.setTempoRatio(snapshot.tempoRatio)
        audio.setPitchSemitones(snapshot.pitchSemitones)
        audio.seek(to: min(max(0, snapshot.position), song.duration), autoPlay: false)

        if let setlistID = snapshot.setlistID,
           let setlist = library.setlists.first(where: { $0.id == setlistID }),
           setlist.songIDs.contains(song.id) {
            startSetlistLive(setlist)
            audio.seek(to: min(max(0, snapshot.position), song.duration), autoPlay: false)
        }
        selectedTab = .live
    }

    func clearSavedSession() { stageSessionStore.clear() }
}
