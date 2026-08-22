import Foundation
import ZIPFoundation

enum GuideCueKind: String, Codable, CaseIterable {
    case section
    case count
    case dynamic
}

struct GuideSample: Identifiable, Hashable {
    var id: String { "\(language):\(kind.rawValue):\(key)" }
    let language: String
    let key: String
    let kind: GuideCueKind
    let url: URL
}

@MainActor
final class GuidePackStore: ObservableObject {
    struct Status: Equatable {
        var installed = false
        var sampleCount = 0
        var languages: [String] = []
        var sourceName: String?
    }

    @Published private(set) var status = Status()

    private let fm = FileManager.default
    private var cache: [GuideSample]?

    init() { refresh() }

    private var baseURL: URL {
        let docs = fm.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let url = docs.appendingPathComponent("StageGridLibrary/GuidePacks", isDirectory: true)
        try? fm.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    private var currentURL: URL { baseURL.appendingPathComponent("current", isDirectory: true) }

    func refresh() {
        cache = nil
        let samples = listSamples()
        status = Status(
            installed: !samples.isEmpty,
            sampleCount: samples.count,
            languages: Array(Set(samples.map(\.language))).sorted(),
            sourceName: try? String(contentsOf: currentURL.appendingPathComponent("source-name.txt"), encoding: .utf8)
                .trimmingCharacters(in: .whitespacesAndNewlines)
        )
    }

    @discardableResult
    func install(zipURL: URL) throws -> Status {
        let accessed = zipURL.startAccessingSecurityScopedResource()
        defer { if accessed { zipURL.stopAccessingSecurityScopedResource() } }

        let staging = baseURL.appendingPathComponent("staging-\(UUID().uuidString)", isDirectory: true)
        let extracted = baseURL.appendingPathComponent("extract-\(UUID().uuidString)", isDirectory: true)
        defer {
            try? fm.removeItem(at: staging)
            try? fm.removeItem(at: extracted)
        }
        try fm.createDirectory(at: staging, withIntermediateDirectories: true)
        try fm.createDirectory(at: extracted, withIntermediateDirectories: true)
        try fm.unzipItem(at: zipURL, to: extracted)

        var installed = 0
        let resourceKeys: [URLResourceKey] = [.isRegularFileKey, .fileSizeKey]
        guard let enumerator = fm.enumerator(at: extracted, includingPropertiesForKeys: resourceKeys) else {
            throw GuidePackError.invalidPack
        }

        while let file = enumerator.nextObject() as? URL {
            let values = try file.resourceValues(forKeys: Set(resourceKeys))
            guard values.isRegularFile == true, file.pathExtension.lowercased() == "wav" else { continue }
            guard let parsed = parse(relativePath: file.path.replacingOccurrences(of: extracted.path + "/", with: "")) else { continue }
            let destination = staging
                .appendingPathComponent(parsed.language, isDirectory: true)
                .appendingPathComponent(parsed.kind.rawValue, isDirectory: true)
                .appendingPathComponent("\(parsed.key).wav")
            try fm.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
            if fm.fileExists(atPath: destination.path) { try fm.removeItem(at: destination) }
            try fm.copyItem(at: file, to: destination)
            installed += 1
            if installed > 2_048 { throw GuidePackError.tooManySamples }
        }

        guard installed > 0 else { throw GuidePackError.noCompatibleSamples }
        try zipURL.lastPathComponent.write(to: staging.appendingPathComponent("source-name.txt"), atomically: true, encoding: .utf8)

        let previous = baseURL.appendingPathComponent("previous", isDirectory: true)
        try? fm.removeItem(at: previous)
        if fm.fileExists(atPath: currentURL.path) {
            try fm.moveItem(at: currentURL, to: previous)
        }
        do {
            try fm.moveItem(at: staging, to: currentURL)
            try? fm.removeItem(at: previous)
        } catch {
            if fm.fileExists(atPath: previous.path) { try? fm.moveItem(at: previous, to: currentURL) }
            throw error
        }

        refresh()
        return status
    }

    func listSamples() -> [GuideSample] {
        if let cache { return cache }
        guard fm.fileExists(atPath: currentURL.path),
              let enumerator = fm.enumerator(at: currentURL, includingPropertiesForKeys: [.isRegularFileKey]) else {
            cache = []
            return []
        }
        var result: [GuideSample] = []
        while let file = enumerator.nextObject() as? URL {
            guard file.pathExtension.lowercased() == "wav" else { continue }
            let relative = file.path.replacingOccurrences(of: currentURL.path + "/", with: "").split(separator: "/").map(String.init)
            guard relative.count >= 3,
                  let kind = GuideCueKind(rawValue: relative[1]) else { continue }
            result.append(GuideSample(language: relative[0], key: file.deletingPathExtension().lastPathComponent, kind: kind, url: file))
        }
        result.sort { ($0.language, $0.kind.rawValue, $0.key) < ($1.language, $1.kind.rawValue, $1.key) }
        cache = result
        return result
    }

    func resolveLanguage(preferred: String, detected: String? = nil) -> String? {
        let available = status.languages
        guard !available.isEmpty else { return nil }
        if preferred != "auto", available.contains(preferred) { return preferred }
        if let detected, available.contains(detected) { return detected }
        let device = Locale.current.language.languageCode?.identifier.lowercased()
        return available.first(where: { $0 == device }) ?? available.first(where: { $0 == "en" }) ?? available.first
    }

    func sample(language: String, key: String, kind: GuideCueKind? = nil) -> GuideSample? {
        let canonical = canonicalKey(key)
        let samples = listSamples()
        if let exact = samples.first(where: { $0.language == language && $0.key == canonical && (kind == nil || $0.kind == kind) }) {
            return exact
        }
        if let anyKind = samples.first(where: { $0.language == language && $0.key == canonical }) { return anyKind }
        let family = canonical.replacingOccurrences(of: "_[0-9]+$", with: "", options: .regularExpression)
        guard family != canonical else { return nil }
        return samples.first(where: { $0.language == language && $0.key == family && (kind == nil || $0.kind == kind) })
            ?? samples.first(where: { $0.language == language && $0.key == family })
    }

    func canonicalKey(_ value: String) -> String {
        let folded = value.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "en_US_POSIX"))
            .lowercased()
            .replacingOccurrences(of: "a capella", with: "acapella")
        let raw = folded.replacingOccurrences(of: "[^a-z0-9]+", with: "_", options: .regularExpression)
            .trimmingCharacters(in: CharacterSet(charactersIn: "_"))
        let match = raw.range(of: "_[0-9]+$", options: .regularExpression)
        let suffix = match.map { String(raw[$0]) } ?? ""
        let base = match.map { String(raw[..<$0.lowerBound]) } ?? raw
        let mapped: String
        switch base {
        case "introduccion", "introducao": mapped = "intro"
        case "verso": mapped = "verse"
        case "pre_coro", "precoro", "pre_refrain": mapped = "pre_chorus"
        case "coro", "refrao", "refrain": mapped = "chorus"
        case "puente", "ponte": mapped = "bridge"
        case "final", "fim": mapped = "outro"
        default: mapped = base
        }
        return mapped + suffix
    }

    private struct Parsed { let language: String; let key: String; let kind: GuideCueKind }

    private func parse(relativePath: String) -> Parsed? {
        let normalized = relativePath.replacingOccurrences(of: "\\", with: "/")
        let lower = normalized.lowercased()
        guard lower.hasSuffix(".wav"), !lower.hasPrefix("__macosx/"), lower.contains("guides/") else { return nil }
        let language: String
        if lower.contains("spanish guides/") { language = "es" }
        else if lower.contains("english guides/") { language = "en" }
        else if lower.contains("french guides/") { language = "fr" }
        else if lower.contains("portugese guides/") || lower.contains("portuguese guides/") { language = "pt" }
        else { return nil }

        let name = URL(fileURLWithPath: normalized).deletingPathExtension().lastPathComponent
        let label = name.components(separatedBy: " - ").last?.trimmingCharacters(in: .whitespacesAndNewlines) ?? name
        let isSection = lower.contains("/song sections/")
        let canonicalSource: String
        if language == "es", isSection,
           let open = label.lastIndex(of: "("), let close = label.lastIndex(of: ")"), open < close {
            canonicalSource = String(label[label.index(after: open)..<close])
        } else {
            canonicalSource = label.components(separatedBy: " (").first ?? label
        }
        let key = canonicalKey(canonicalSource)
        guard !key.isEmpty else { return nil }
        let kind: GuideCueKind
        if isSection { kind = Int(key) != nil ? .count : .section }
        else if lower.contains("/dynamic cues/") || lower.contains("/guide cues/") { kind = .dynamic }
        else { return nil }
        return Parsed(language: language, key: key, kind: kind)
    }

    enum GuidePackError: LocalizedError {
        case invalidPack, noCompatibleSamples, tooManySamples
        var errorDescription: String? {
            switch self {
            case .invalidPack: "No se pudo leer el Guide Pack."
            case .noCompatibleSamples: "El ZIP no contiene WAV de Guide compatibles."
            case .tooManySamples: "El Guide Pack supera el límite de 2048 samples."
            }
        }
    }
}
