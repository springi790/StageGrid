import Foundation
import ZIPFoundation

@MainActor
final class BackupManager: ObservableObject {
    @Published private(set) var running = false
    @Published private(set) var progress: Double = 0
    @Published private(set) var stage = ""
    @Published var lastError: String?

    private let fm = FileManager.default

    func createBackup(library: LibraryStore) async -> URL? {
        guard !running else { return nil }
        running = true
        progress = 0.05
        stage = "Preparando biblioteca"
        defer { running = false }
        do {
            try library.save()
            let destination = fm.temporaryDirectory.appendingPathComponent("StageGrid-\(timestamp()).stagebackup")
            try? fm.removeItem(at: destination)
            progress = 0.25
            stage = "Comprimiendo"
            try fm.zipItem(at: library.rootURL, to: destination, shouldKeepParent: true, compressionMethod: .deflate)
            progress = 1
            stage = "Listo"
            lastError = nil
            return destination
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    func restoreBackup(from source: URL, library: LibraryStore) async -> Bool {
        guard !running else { return false }
        running = true
        progress = 0.05
        stage = "Validando backup"
        defer { running = false }
        let accessed = source.startAccessingSecurityScopedResource()
        defer { if accessed { source.stopAccessingSecurityScopedResource() } }
        let staging = fm.temporaryDirectory.appendingPathComponent("stagegrid-restore-\(UUID().uuidString)", isDirectory: true)
        defer { try? fm.removeItem(at: staging) }
        do {
            try fm.createDirectory(at: staging, withIntermediateDirectories: true)
            try fm.unzipItem(at: source, to: staging)
            progress = 0.45
            stage = "Comprobando biblioteca"
            let candidates = try fm.contentsOfDirectory(at: staging, includingPropertiesForKeys: [.isDirectoryKey])
            let restoredRoot = candidates.first(where: { $0.lastPathComponent == "StageGridLibrary" }) ?? staging
            guard fm.fileExists(atPath: restoredRoot.appendingPathComponent("library.json").path) else {
                throw BackupError.invalidArchive
            }

            let target = library.rootURL
            let previous = target.deletingLastPathComponent().appendingPathComponent("StageGridLibrary.previous", isDirectory: true)
            try? fm.removeItem(at: previous)
            progress = 0.65
            stage = "Restaurando"
            if fm.fileExists(atPath: target.path) { try fm.moveItem(at: target, to: previous) }
            do {
                try fm.copyItem(at: restoredRoot, to: target)
                try? fm.removeItem(at: previous)
            } catch {
                try? fm.removeItem(at: target)
                if fm.fileExists(atPath: previous.path) { try? fm.moveItem(at: previous, to: target) }
                throw error
            }
            library.reload()
            progress = 1
            stage = "Restaurado"
            lastError = nil
            return true
        } catch {
            lastError = error.localizedDescription
            return false
        }
    }

    private func timestamp() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMMdd-HHmmss"
        return formatter.string(from: Date())
    }

    enum BackupError: LocalizedError {
        case invalidArchive
        var errorDescription: String? { "El archivo no contiene una biblioteca StageGrid válida." }
    }
}
