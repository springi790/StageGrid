import Foundation
import AVFoundation

struct NativeGuideProgress: Sendable {
    var fraction: Double
    var stage: String
    var detail: String?
}

actor NativeGuideAnalyzer {
    private let windowMs = 10.0

    func analyze(
        referenceURL: URL,
        samples: [GuideSample],
        preferredLanguage: String,
        bpm: Double,
        beatsPerBar: Int,
        gridOffsetMs: Double,
        progress: @escaping @Sendable (NativeGuideProgress) -> Void
    ) async throws -> (StageNativeGuideAnalysis, [StageSection]) {
        progress(.init(fraction: 0.02, stage: "Guide", detail: "Leyendo referencia"))
        let reference = try fingerprint(referenceURL) { value in
            progress(.init(fraction: 0.02 + value * 0.26, stage: "Guide", detail: "Fingerprint de la canción"))
        }
        let candidates = detectCandidates(reference)
        guard !candidates.isEmpty else { throw AnalyzerError.noSpeechCandidates }

        progress(.init(fraction: 0.30, stage: "Templates", detail: "Preparando Guide Pack"))
        var templates: [Template] = []
        for (index, sample) in samples.enumerated() {
            try Task.checkCancellation()
            let fp = try fingerprint(sample.url, onProgress: nil)
            let trimmed = trim(fp)
            if trimmed.count >= 3 {
                templates.append(Template(sample: sample, envelope: normalized(trimmed)))
            }
            if index % max(1, samples.count / 20) == 0 {
                progress(.init(
                    fraction: 0.30 + 0.22 * Double(index + 1) / Double(max(1, samples.count)),
                    stage: "Templates",
                    detail: "\(index + 1)/\(samples.count)"
                ))
            }
        }
        guard !templates.isEmpty else { throw AnalyzerError.noTemplates }

        let detected = detectLanguage(candidates: candidates, reference: reference, templates: templates)
        let outputLanguage: String = {
            if preferredLanguage != "auto", templates.contains(where: { $0.sample.language == preferredLanguage }) { return preferredLanguage }
            if let detected, templates.contains(where: { $0.sample.language == detected }) { return detected }
            return templates.first?.sample.language ?? "en"
        }()
        let matchingTemplates = templates.filter { $0.sample.language == outputLanguage }
        progress(.init(fraction: 0.54, stage: "Matching", detail: outputLanguage.uppercased()))

        var events: [StageNativeGuideEvent] = []
        for (index, candidate) in candidates.enumerated() {
            try Task.checkCancellation()
            let vector = normalized(Array(reference[candidate]))
            var best: (Template, Double)?
            var second = -1.0
            for template in matchingTemplates {
                let score = similarity(vector, template.envelope)
                if score > (best?.1 ?? -1) {
                    second = best?.1 ?? -1
                    best = (template, score)
                } else if score > second {
                    second = score
                }
            }
            if let best, best.1 >= threshold(for: best.0.sample.kind), best.1 - second >= 0.025 {
                let cueWindow = candidate.lowerBound
                events.append(StageNativeGuideEvent(
                    key: best.0.sample.key,
                    kind: best.0.sample.kind.rawValue,
                    language: best.0.sample.language,
                    cueTime: Double(cueWindow) * windowMs / 1000.0,
                    confidence: min(1, max(0, best.1))
                ))
            }
            if index % max(1, candidates.count / 25) == 0 {
                progress(.init(
                    fraction: 0.54 + 0.36 * Double(index + 1) / Double(max(1, candidates.count)),
                    stage: "Matching",
                    detail: "\(index + 1)/\(candidates.count)"
                ))
            }
        }

        let deduped = deduplicate(events)
        let analysis = StageNativeGuideAnalysis(
            detectedLanguage: detected,
            outputLanguage: outputLanguage,
            events: deduped,
            analyzedAt: Date()
        )
        let sections = inferSections(
            events: deduped,
            bpm: bpm,
            beatsPerBar: beatsPerBar,
            gridOffsetMs: gridOffsetMs
        )
        progress(.init(fraction: 1, stage: "Listo", detail: "\(deduped.count) cues"))
        return (analysis, sections)
    }

    private struct Template {
        let sample: GuideSample
        let envelope: [Float]
    }

    private func fingerprint(_ url: URL, onProgress: ((Double) -> Void)?) throws -> [Float] {
        let file = try AVAudioFile(forReading: url)
        let format = file.processingFormat
        guard format.sampleRate > 0 else { throw AnalyzerError.invalidAudio }
        let windowFrames = max(1, Int(format.sampleRate * windowMs / 1000.0))
        let chunkFrames: AVAudioFrameCount = 16_384
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: chunkFrames) else { throw AnalyzerError.invalidAudio }

        var envelope: [Float] = []
        var windowEnergy = [Double](repeating: 0, count: Int(format.channelCount))
        var framesInWindow = 0
        var readFrames: AVAudioFramePosition = 0
        let total = max(1, file.length)

        while file.framePosition < file.length {
            buffer.frameLength = 0
            try file.read(into: buffer, frameCount: chunkFrames)
            guard buffer.frameLength > 0 else { break }
            guard let channels = buffer.floatChannelData else { throw AnalyzerError.unsupportedPCM }
            let channelCount = Int(format.channelCount)
            for frame in 0..<Int(buffer.frameLength) {
                for channel in 0..<channelCount {
                    let value = Double(channels[channel][frame])
                    windowEnergy[channel] += value * value
                }
                framesInWindow += 1
                if framesInWindow >= windowFrames {
                    let channelRms = windowEnergy.map { sqrt($0 / Double(framesInWindow)) }
                    // Per-channel energy before combining avoids stereo phase cancellation.
                    envelope.append(Float(channelRms.reduce(0, +) / Double(max(1, channelRms.count))))
                    windowEnergy = [Double](repeating: 0, count: channelCount)
                    framesInWindow = 0
                }
            }
            readFrames += AVAudioFramePosition(buffer.frameLength)
            onProgress?(min(1, Double(readFrames) / Double(total)))
        }
        if framesInWindow > 0 {
            let channelRms = windowEnergy.map { sqrt($0 / Double(framesInWindow)) }
            envelope.append(Float(channelRms.reduce(0, +) / Double(max(1, channelRms.count))))
        }
        return envelope
    }

    private func detectCandidates(_ raw: [Float]) -> [Range<Int>] {
        guard raw.count > 4 else { return [] }
        let env = normalizePeak(raw)
        let sorted = env.sorted()
        let median = sorted[sorted.count / 2]
        let threshold = max(0.055, min(0.24, median * 4.0 + 0.025))
        let active = env.map { $0 >= threshold }
        var regions: [Range<Int>] = []
        var start: Int?
        var lastActive: Int?
        let mergeGap = 24 // 240 ms

        for i in active.indices {
            if active[i] {
                if start == nil { start = i }
                lastActive = i
            } else if let s = start, let last = lastActive, i - last > mergeGap {
                let lo = max(0, s - 3)
                let hi = min(env.count, last + 7)
                if hi - lo >= 3 { regions.append(lo..<min(hi, lo + 240)) }
                start = nil
                lastActive = nil
                if regions.count >= 2400 { break }
            }
        }
        if let s = start, let last = lastActive, regions.count < 2400 {
            let lo = max(0, s - 3)
            let hi = min(env.count, last + 7)
            if hi - lo >= 3 { regions.append(lo..<min(hi, lo + 240)) }
        }
        return regions
    }

    private func detectLanguage(candidates: [Range<Int>], reference: [Float], templates: [Template]) -> String? {
        let sectionTemplates = templates.filter { $0.sample.kind == .section }
        guard !sectionTemplates.isEmpty else { return nil }
        let sampleCandidates: [Range<Int>] = {
            if candidates.count <= 14 { return candidates }
            return (0..<14).map { candidates[min(candidates.count - 1, $0 * (candidates.count - 1) / 13)] }
        }()
        var scores: [String: [Double]] = [:]
        for candidate in sampleCandidates {
            let vector = normalized(Array(reference[candidate]))
            for template in sectionTemplates {
                scores[template.sample.language, default: []].append(similarity(vector, template.envelope))
            }
        }
        let ranked = scores.map { language, values -> (String, Double) in
            let top = values.sorted(by: >).prefix(6)
            return (language, top.reduce(0, +) / Double(max(1, top.count)))
        }.sorted { $0.1 > $1.1 }
        guard let first = ranked.first, first.1 >= 0.48 else { return nil }
        if ranked.count > 1, first.1 - ranked[1].1 < 0.035 { return nil }
        return first.0
    }

    private func threshold(for kind: GuideCueKind) -> Double {
        switch kind {
        case .count: 0.76
        case .section: 0.67
        case .dynamic: 0.71
        }
    }

    private func similarity(_ a: [Float], _ b: [Float]) -> Double {
        guard a.count >= 2, b.count >= 2 else { return -1 }
        let target = max(8, min(160, max(a.count, b.count)))
        let aa = resample(a, count: target)
        let bb = resample(b, count: target)
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for i in 0..<target {
            let x = Double(aa[i])
            let y = Double(bb[i])
            dot += x * y
            na += x * x
            nb += y * y
        }
        guard na > 1e-8, nb > 1e-8 else { return -1 }
        return dot / sqrt(na * nb)
    }

    private func trim(_ values: [Float]) -> [Float] {
        let peak = values.max() ?? 0
        guard peak > 0 else { return [] }
        let threshold = peak * 0.07
        guard let first = values.firstIndex(where: { $0 >= threshold }),
              let last = values.lastIndex(where: { $0 >= threshold }) else { return [] }
        return Array(values[max(0, first - 2)...min(values.count - 1, last + 3)])
    }

    private func normalizePeak(_ values: [Float]) -> [Float] {
        let peak = values.max() ?? 0
        guard peak > 1e-8 else { return values.map { _ in 0 } }
        return values.map { $0 / peak }
    }

    private func normalized(_ values: [Float]) -> [Float] {
        guard !values.isEmpty else { return [] }
        let mean = values.reduce(0, +) / Float(values.count)
        let centered = values.map { $0 - mean }
        let norm = sqrt(centered.reduce(Float(0)) { $0 + $1 * $1 })
        guard norm > 1e-7 else { return normalizePeak(values) }
        return centered.map { $0 / norm }
    }

    private func resample(_ values: [Float], count: Int) -> [Float] {
        guard count > 0, !values.isEmpty else { return [] }
        if values.count == 1 { return [Float](repeating: values[0], count: count) }
        if count == 1 { return [values[0]] }
        let scale = Double(values.count - 1) / Double(count - 1)
        return (0..<count).map { i in
            let position = Double(i) * scale
            let lo = Int(position.rounded(.down))
            let hi = min(values.count - 1, lo + 1)
            let f = Float(position - Double(lo))
            return values[lo] * (1 - f) + values[hi] * f
        }
    }

    private func deduplicate(_ events: [StageNativeGuideEvent]) -> [StageNativeGuideEvent] {
        var output: [StageNativeGuideEvent] = []
        for event in events.sorted(by: { $0.cueTime < $1.cueTime }) {
            if let last = output.last, abs(last.cueTime - event.cueTime) < 0.28 {
                if event.confidence > last.confidence { output[output.count - 1] = event }
            } else {
                output.append(event)
            }
        }
        return output
    }

    private func inferSections(events: [StageNativeGuideEvent], bpm: Double, beatsPerBar: Int, gridOffsetMs: Double) -> [StageSection] {
        guard bpm > 0 else { return [] }
        let beat = 60.0 / bpm
        let bar = beat * Double(max(1, beatsPerBar))
        let offset = gridOffsetMs / 1000.0
        let sectionEvents = events.filter { $0.kind == GuideCueKind.section.rawValue }
        var starts: [(String, TimeInterval)] = []
        for event in sectionEvents {
            let target = event.cueTime + bar
            let barIndex = ((target - offset) / bar).rounded()
            let snapped = max(0, offset + barIndex * bar)
            if starts.last.map({ abs($0.1 - snapped) < beat }) == true { continue }
            starts.append((displayName(for: event.key), snapped))
        }
        return starts.enumerated().map { index, item in
            StageSection(
                name: item.0,
                start: item.1,
                end: starts.indices.contains(index + 1) ? starts[index + 1].1 : item.1 + bar * 8,
                addCount: false,
                colorHex: sectionColor(index)
            )
        }
    }

    private func displayName(for key: String) -> String {
        let base = key.replacingOccurrences(of: "_", with: " ")
        return base.split(separator: " ").map { $0.capitalized }.joined(separator: " ")
    }

    private func sectionColor(_ index: Int) -> String {
        let colors = ["#5B8CFF", "#2FBF9F", "#9C6CFF", "#F39C55", "#E85D75", "#4FB6E9"]
        return colors[index % colors.count]
    }

    enum AnalyzerError: LocalizedError {
        case invalidAudio, unsupportedPCM, noSpeechCandidates, noTemplates
        var errorDescription: String? {
            switch self {
            case .invalidAudio: "El archivo Guide no es válido."
            case .unsupportedPCM: "No se pudo convertir el Guide a PCM para análisis."
            case .noSpeechCandidates: "No se detectaron cues candidatos en la Guide."
            case .noTemplates: "El Guide Pack no contiene templates utilizables."
            }
        }
    }
}
