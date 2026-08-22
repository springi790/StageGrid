import SwiftUI
import AVFoundation

struct StageWaveformView: View {
    let song: StageSong
    let position: TimeInterval
    let currentSectionID: UUID?
    let onSeek: (TimeInterval) -> Void

    @EnvironmentObject private var library: LibraryStore
    @State private var peaks: [Float] = []

    var body: some View {
        GeometryReader { proxy in
            Canvas { context, size in
                let center = size.height / 2
                let count = max(1, peaks.count)
                let width = size.width / CGFloat(count)
                for (index, peak) in peaks.enumerated() {
                    let x = CGFloat(index) * width
                    let h = max(1, CGFloat(peak) * (size.height * 0.42))
                    let progress = song.duration > 0 ? min(1, max(0, position / song.duration)) : 0
                    let played = CGFloat(index) / CGFloat(count) <= progress
                    let color = played ? Color.sgBlue : Color.white.opacity(0.28)
                    context.fill(Path(CGRect(x: x, y: center - h, width: max(1, width * 0.72), height: h * 2)), with: .color(color))
                }
                for section in song.sections where song.duration > 0 {
                    let x = CGFloat(section.start / song.duration) * size.width
                    var path = Path()
                    path.move(to: CGPoint(x: x, y: 0))
                    path.addLine(to: CGPoint(x: x, y: size.height))
                    context.stroke(path, with: .color(section.id == currentSectionID ? .sgMint : .white.opacity(0.24)), lineWidth: section.id == currentSectionID ? 2 : 1)
                }
                if song.duration > 0 {
                    let x = CGFloat(position / song.duration) * size.width
                    var playhead = Path()
                    playhead.move(to: CGPoint(x: x, y: 0))
                    playhead.addLine(to: CGPoint(x: x, y: size.height))
                    context.stroke(playhead, with: .color(.white), lineWidth: 2)
                }
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onEnded { value in
                guard song.duration > 0, proxy.size.width > 0 else { return }
                let fraction = min(1, max(0, value.location.x / proxy.size.width))
                onSeek(Double(fraction) * song.duration)
            })
        }
        .frame(height: 94)
        .background(Color.white.opacity(0.035), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .task(id: song.id) { await loadPeaks() }
    }

    private func loadPeaks() async {
        if let cached = try? Data(contentsOf: cacheURL()),
           let values = try? JSONDecoder().decode([Float].self, from: cached), !values.isEmpty {
            peaks = values
            return
        }
        guard let track = song.tracks.first(where: { $0.kind != .click && $0.kind != .guide }) ?? song.tracks.first else { return }
        let url = library.trackURL(song: song, track: track)
        let values = await Task.detached(priority: .utility) { computePeaks(url: url, buckets: 520) }.value
        peaks = values
        if let data = try? JSONEncoder().encode(values) { try? data.write(to: cacheURL(), options: .atomic) }
    }

    private func cacheURL() -> URL { library.songFolder(song).appendingPathComponent("waveform-peaks-v1.json") }
}

private func computePeaks(url: URL, buckets: Int) -> [Float] {
    guard let file = try? AVAudioFile(forReading: url), file.length > 0 else { return [] }
    let format = file.processingFormat
    let totalFrames = max(1, file.length)
    var peaks = [Float](repeating: 0, count: buckets)
    let chunkSize: AVAudioFrameCount = 16_384
    guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: chunkSize) else { return [] }
    var globalFrame: AVAudioFramePosition = 0
    while file.framePosition < file.length {
        buffer.frameLength = 0
        do { try file.read(into: buffer, frameCount: chunkSize) } catch { break }
        guard buffer.frameLength > 0, let channels = buffer.floatChannelData else { break }
        let channelCount = Int(format.channelCount)
        for frame in 0..<Int(buffer.frameLength) {
            let bucket = min(buckets - 1, Int(Double(globalFrame + AVAudioFramePosition(frame)) / Double(totalFrames) * Double(buckets)))
            var value: Float = 0
            for channel in 0..<channelCount { value = max(value, abs(channels[channel][frame])) }
            peaks[bucket] = max(peaks[bucket], value)
        }
        globalFrame += AVAudioFramePosition(buffer.frameLength)
    }
    let maxPeak = peaks.max() ?? 0
    return maxPeak > 0 ? peaks.map { min(1, $0 / maxPeak) } : peaks
}
