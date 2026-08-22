import Foundation
import OSLog

enum StageGridLog {
    private static let subsystem = "dev.stagegrid.ios"
    private static let general = Logger(subsystem: subsystem, category: "StageGrid")

    static func action(_ category: String, _ message: String) {
        general.notice("[ACTION][\(category, privacy: .public)] \(message, privacy: .public)")
    }

    static func state(_ category: String, _ message: String) {
        general.info("[STATE][\(category, privacy: .public)] \(message, privacy: .public)")
    }

    static func warning(_ category: String, _ message: String) {
        general.warning("[WARN][\(category, privacy: .public)] \(message, privacy: .public)")
    }

    static func error(_ category: String, _ message: String) {
        general.error("[ERROR][\(category, privacy: .public)] \(message, privacy: .public)")
    }
}
