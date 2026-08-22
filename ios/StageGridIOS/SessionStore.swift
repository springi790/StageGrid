import Foundation

struct StageSessionSnapshot: Codable {
    var songID: UUID
    var position: TimeInterval
    var clickEnabled: Bool
    var guideEnabled: Bool
    var guideSource: StageGuideSource
    var tempoRatio: Float
    var pitchSemitones: Float
    var setlistID: UUID?
    var setlistIndex: Int?
    var savedAt: Date
}

final class SessionStore {
    private let key = "stagegrid.ios.session.v1"

    func read() -> StageSessionSnapshot? {
        guard let data = UserDefaults.standard.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(StageSessionSnapshot.self, from: data)
    }

    func write(_ snapshot: StageSessionSnapshot) {
        guard let data = try? JSONEncoder().encode(snapshot) else { return }
        UserDefaults.standard.set(data, forKey: key)
    }

    func clear() { UserDefaults.standard.removeObject(forKey: key) }
}
