#include "OfflineMixRenderer.h"
#include "WavReader.h"
#include "signalsmith-stretch.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>
#include <memory>
#include <vector>

namespace {
constexpr int kOutputSampleRate = 48000;
constexpr int kOutputChannels = 2;
constexpr int kBitsPerSample = 16;
constexpr int kBlockFrames = 1024;
constexpr int kTrackClick = 9;
constexpr int kTrackGuide = 10;
constexpr int kRouteBoth = 0;
constexpr int kRouteLeft = 1;
constexpr int kRouteRight = 2;
constexpr float kMinTempo = 0.75f;
constexpr float kMaxTempo = 1.50f;
constexpr float kMinPitch = -12.0f;
constexpr float kMaxPitch = 12.0f;
constexpr double kPi = 3.14159265358979323846;

using Stretch = signalsmith::stretch::SignalsmithStretch<float>;

inline bool dspIdentity(float tempo, float pitch) noexcept {
    return std::abs(tempo - 1.0f) <= 0.0001f && std::abs(pitch) <= 0.0001f;
}

inline float clampUnit(float value) noexcept {
    return std::clamp(value, -1.0f, 1.0f);
}

void writeU16(FILE *file, uint16_t value) {
    const uint8_t bytes[2] = {
        static_cast<uint8_t>(value & 0xffu),
        static_cast<uint8_t>((value >> 8u) & 0xffu),
    };
    std::fwrite(bytes, 1, sizeof(bytes), file);
}

void writeU32(FILE *file, uint32_t value) {
    const uint8_t bytes[4] = {
        static_cast<uint8_t>(value & 0xffu),
        static_cast<uint8_t>((value >> 8u) & 0xffu),
        static_cast<uint8_t>((value >> 16u) & 0xffu),
        static_cast<uint8_t>((value >> 24u) & 0xffu),
    };
    std::fwrite(bytes, 1, sizeof(bytes), file);
}

bool writeWavHeader(FILE *file, uint32_t dataBytes) {
    if (!file) return false;
    std::rewind(file);
    std::fwrite("RIFF", 1, 4, file);
    writeU32(file, 36u + dataBytes);
    std::fwrite("WAVE", 1, 4, file);
    std::fwrite("fmt ", 1, 4, file);
    writeU32(file, 16u);
    writeU16(file, 1u);
    writeU16(file, static_cast<uint16_t>(kOutputChannels));
    writeU32(file, static_cast<uint32_t>(kOutputSampleRate));
    const uint32_t byteRate = static_cast<uint32_t>(kOutputSampleRate * kOutputChannels * (kBitsPerSample / 8));
    writeU32(file, byteRate);
    writeU16(file, static_cast<uint16_t>(kOutputChannels * (kBitsPerSample / 8)));
    writeU16(file, static_cast<uint16_t>(kBitsPerSample));
    std::fwrite("data", 1, 4, file);
    writeU32(file, dataBytes);
    return std::ferror(file) == 0;
}

struct TrackRenderer {
    explicit TrackRenderer(const OfflineMixTrackConfig &configIn)
        : config(configIn), reader(std::make_unique<WavReader>(config.path)) {}

    OfflineMixTrackConfig config;
    std::unique_ptr<WavReader> reader;
    Stretch stretch;
    bool useDsp{false};
    int outputLatencyFrames{0};
    int64_t cursor{0};
    double inputRemainder{0.0};
    std::vector<float> inputLeft;
    std::vector<float> inputRight;
    std::vector<float> outputLeft;
    std::vector<float> outputRight;
};

void addStereoFoldDown(
    std::vector<float> &mixLeft,
    std::vector<float> &mixRight,
    int frameIndex,
    float left,
    float right,
    float pan,
    float volume,
    int route
) {
    if (route == kRouteLeft || route == kRouteRight) {
        const float mono = (left + right) * 0.5f * volume;
        if (route == kRouteLeft) mixLeft[static_cast<size_t>(frameIndex)] += mono;
        else mixRight[static_cast<size_t>(frameIndex)] += mono;
        return;
    }

    const float safePan = std::clamp(pan, -1.0f, 1.0f);
    const float leftGain = std::sqrt(0.5f * (1.0f - safePan)) * 1.41421356f;
    const float rightGain = std::sqrt(0.5f * (1.0f + safePan)) * 1.41421356f;
    mixLeft[static_cast<size_t>(frameIndex)] += left * volume * leftGain;
    mixRight[static_cast<size_t>(frameIndex)] += right * volume * rightGain;
}

float clickSample(
    double timelineFrame,
    float tempo,
    double bpm,
    int beatsPerBar,
    int subdivision,
    int64_t gridOffsetFrame
) noexcept {
    if (bpm <= 0.0) return 0.0f;
    const int safeSubdivision = std::max(1, subdivision);
    const double framesPerBeat = kOutputSampleRate * 60.0 / bpm;
    const double framesPerTick = framesPerBeat / safeSubdivision;
    if (framesPerTick < 1.0) return 0.0f;

    const double relative = timelineFrame - static_cast<double>(gridOffsetFrame);
    const int64_t tickIndex = static_cast<int64_t>(std::floor(relative / framesPerTick));
    const double tickStart = tickIndex * framesPerTick;
    const double sourceOffset = relative - tickStart;
    const double safeTempo = std::max(0.01, static_cast<double>(tempo));
    const double outputOffset = sourceOffset / safeTempo;
    const double clickFrames = kOutputSampleRate * 0.022;
    if (outputOffset < 0.0 || outputOffset >= clickFrames) return 0.0f;

    const bool onBeat = (tickIndex % safeSubdivision) == 0;
    const int64_t beatIndex = tickIndex / safeSubdivision;
    const bool barAccent = onBeat && beatIndex % std::max(1, beatsPerBar) == 0;
    const double frequency = barAccent ? 1900.0 : (onBeat ? 1350.0 : 950.0);
    const double gain = barAccent ? 1.0 : (onBeat ? 0.82 : 0.58);
    const double t = outputOffset / kOutputSampleRate;
    const double envelope = std::exp(-t * 105.0);
    return static_cast<float>(std::sin(2.0 * kPi * frequency * t) * envelope * gain * 0.75);
}

void addClickFoldDown(
    std::vector<float> &mixLeft,
    std::vector<float> &mixRight,
    int frameIndex,
    float sample,
    int route
) {
    if (route == kRouteLeft) mixLeft[static_cast<size_t>(frameIndex)] += sample;
    else if (route == kRouteRight) mixRight[static_cast<size_t>(frameIndex)] += sample;
    else {
        mixLeft[static_cast<size_t>(frameIndex)] += sample;
        mixRight[static_cast<size_t>(frameIndex)] += sample;
    }
}
} // namespace

bool renderOfflineMix(const OfflineMixRequest &request, std::string &error) {
    error.clear();
    if (request.outputPath.empty()) {
        error = "Export path is empty.";
        return false;
    }
    if (request.tracks.empty()) {
        error = "No tracks are available to export.";
        return false;
    }

    const float tempo = std::clamp(request.tempoRatio, kMinTempo, kMaxTempo);
    const float pitch = std::clamp(request.pitchSemitones, kMinPitch, kMaxPitch);
    const bool globalDsp = !dspIdentity(tempo, pitch);
    const bool anySolo = std::any_of(request.tracks.begin(), request.tracks.end(), [](const auto &track) {
        return track.solo;
    });

    std::vector<std::unique_ptr<TrackRenderer>> renderers;
    renderers.reserve(request.tracks.size());
    double maxDurationSeconds = 0.0;
    int maxOutputLatencyFrames = 0;

    for (const auto &trackConfig : request.tracks) {
        auto renderer = std::make_unique<TrackRenderer>(trackConfig);
        if (!renderer->reader->valid()) {
            error = trackConfig.path + ": " + renderer->reader->error();
            return false;
        }
        maxDurationSeconds = std::max(maxDurationSeconds, renderer->reader->durationSeconds());
        renderer->useDsp = globalDsp && trackConfig.type != kTrackClick;
        if (renderer->useDsp) {
            renderer->stretch.presetCheaper(2, static_cast<float>(kOutputSampleRate), true);
            renderer->stretch.setTransposeSemitones(trackConfig.type == kTrackGuide ? 0.0f : pitch);
            renderer->stretch.reset();
            const int primeFrames = std::max(0, renderer->stretch.inputLatency());
            if (primeFrames > 0) {
                std::vector<float> primeLeft(static_cast<size_t>(primeFrames), 0.0f);
                std::vector<float> primeRight(static_cast<size_t>(primeFrames), 0.0f);
                std::array<float *, 2> primeInputs{primeLeft.data(), primeRight.data()};
                renderer->stretch.seek(primeInputs, primeFrames, tempo);
            }
            renderer->outputLatencyFrames = std::max(0, renderer->stretch.outputLatency());
            maxOutputLatencyFrames = std::max(maxOutputLatencyFrames, renderer->outputLatencyFrames);
            renderer->inputLeft.resize(static_cast<size_t>(kBlockFrames * 2), 0.0f);
            renderer->inputRight.resize(static_cast<size_t>(kBlockFrames * 2), 0.0f);
            renderer->outputLeft.resize(static_cast<size_t>(kBlockFrames), 0.0f);
            renderer->outputRight.resize(static_cast<size_t>(kBlockFrames), 0.0f);
        }
        renderers.push_back(std::move(renderer));
    }

    const int64_t sourceDurationFrames = static_cast<int64_t>(std::ceil(maxDurationSeconds * kOutputSampleRate));
    if (sourceDurationFrames <= 0) {
        error = "The imported tracks have no audio duration.";
        return false;
    }
    const int64_t outputFramesTotal = std::max<int64_t>(1, static_cast<int64_t>(std::ceil(sourceDurationFrames / tempo)));
    const uint64_t expectedDataBytes = static_cast<uint64_t>(outputFramesTotal) * kOutputChannels * sizeof(int16_t);
    if (expectedDataBytes > std::numeric_limits<uint32_t>::max()) {
        error = "The rendered WAV would exceed the 4 GiB PCM limit.";
        return false;
    }

    FILE *file = std::fopen(request.outputPath.c_str(), "wb+");
    if (!file) {
        error = "Could not create the export file.";
        return false;
    }

    bool success = false;
    do {
        if (!writeWavHeader(file, 0u)) {
            error = "Could not write the WAV header.";
            break;
        }

        std::vector<float> mixLeft(static_cast<size_t>(kBlockFrames), 0.0f);
        std::vector<float> mixRight(static_cast<size_t>(kBlockFrames), 0.0f);
        std::vector<int16_t> pcm(static_cast<size_t>(kBlockFrames * kOutputChannels), 0);
        const float master = std::clamp(request.masterVolume, 0.0f, 1.25f);
        const int64_t gridOffsetFrame = std::max<int64_t>(0, request.gridOffsetMs) * kOutputSampleRate / 1000;
        uint64_t writtenDataBytes = 0;

        for (int64_t outputStart = 0; outputStart < outputFramesTotal; outputStart += kBlockFrames) {
            const int blockFrames = static_cast<int>(std::min<int64_t>(kBlockFrames, outputFramesTotal - outputStart));
            std::fill(mixLeft.begin(), mixLeft.begin() + blockFrames, 0.0f);
            std::fill(mixRight.begin(), mixRight.begin() + blockFrames, 0.0f);

            for (auto &rendererPtr : renderers) {
                auto &renderer = *rendererPtr;
                const auto &config = renderer.config;
                if (config.type == kTrackClick) continue;
                if (config.muted) continue;
                if (anySolo && !config.solo) continue;
                if (config.type == kTrackGuide && !request.guideEnabled) continue;

                if (!renderer.useDsp) {
                    for (int i = 0; i < blockFrames; ++i) {
                        const int64_t timelineFrame = outputStart + i;
                        float left = 0.0f;
                        float right = 0.0f;
                        if (timelineFrame >= 0 && timelineFrame < sourceDurationFrames) {
                            const double sourceFrame = static_cast<double>(timelineFrame) *
                                renderer.reader->sampleRate() / kOutputSampleRate;
                            renderer.reader->sampleAt(sourceFrame, left, right);
                        }
                        addStereoFoldDown(
                            mixLeft, mixRight, i, left, right,
                            config.pan, std::clamp(config.volume, 0.0f, 1.5f),
                            std::clamp(config.route, kRouteBoth, kRouteRight)
                        );
                    }
                    continue;
                }

                const double desiredInput = static_cast<double>(blockFrames) * tempo + renderer.inputRemainder;
                int inputFrames = std::max(1, static_cast<int>(std::floor(desiredInput)));
                renderer.inputRemainder = desiredInput - inputFrames;
                inputFrames = std::min(inputFrames, static_cast<int>(renderer.inputLeft.size()));

                for (int i = 0; i < inputFrames; ++i) {
                    float left = 0.0f;
                    float right = 0.0f;
                    if (renderer.cursor >= 0 && renderer.cursor < sourceDurationFrames) {
                        const double sourceFrame = static_cast<double>(renderer.cursor) *
                            renderer.reader->sampleRate() / kOutputSampleRate;
                        renderer.reader->sampleAt(sourceFrame, left, right);
                    }
                    renderer.inputLeft[static_cast<size_t>(i)] = left;
                    renderer.inputRight[static_cast<size_t>(i)] = right;
                    renderer.cursor++;
                }

                renderer.stretch.setTransposeSemitones(config.type == kTrackGuide ? 0.0f : pitch);
                std::array<float *, 2> inputs{renderer.inputLeft.data(), renderer.inputRight.data()};
                std::array<float *, 2> outputs{renderer.outputLeft.data(), renderer.outputRight.data()};
                renderer.stretch.process(inputs, inputFrames, outputs, blockFrames);
                for (int i = 0; i < blockFrames; ++i) {
                    addStereoFoldDown(
                        mixLeft, mixRight, i,
                        renderer.outputLeft[static_cast<size_t>(i)],
                        renderer.outputRight[static_cast<size_t>(i)],
                        config.pan, std::clamp(config.volume, 0.0f, 1.5f),
                        std::clamp(config.route, kRouteBoth, kRouteRight)
                    );
                }
            }

            if (request.clickEnabled) {
                const double latencySourceFrames = globalDsp
                    ? static_cast<double>(maxOutputLatencyFrames) * tempo
                    : 0.0;
                for (int i = 0; i < blockFrames; ++i) {
                    const double timelinePosition = static_cast<double>(outputStart + i) * tempo;
                    const double audibleTimeline = timelinePosition - latencySourceFrames;
                    const float click = clickSample(
                        audibleTimeline,
                        tempo,
                        request.bpm,
                        std::max(1, request.beatsPerBar),
                        request.clickSubdivision,
                        gridOffsetFrame
                    );
                    addClickFoldDown(
                        mixLeft,
                        mixRight,
                        i,
                        click,
                        std::clamp(request.clickRoute, kRouteBoth, kRouteRight)
                    );
                }
            }

            for (int i = 0; i < blockFrames; ++i) {
                const float left = clampUnit(mixLeft[static_cast<size_t>(i)] * master);
                const float right = clampUnit(mixRight[static_cast<size_t>(i)] * master);
                pcm[static_cast<size_t>(i * 2)] = static_cast<int16_t>(std::lrint(left * 32767.0f));
                pcm[static_cast<size_t>(i * 2 + 1)] = static_cast<int16_t>(std::lrint(right * 32767.0f));
            }

            const size_t samplesToWrite = static_cast<size_t>(blockFrames * kOutputChannels);
            if (std::fwrite(pcm.data(), sizeof(int16_t), samplesToWrite, file) != samplesToWrite) {
                error = "Could not write the rendered audio file.";
                break;
            }
            writtenDataBytes += samplesToWrite * sizeof(int16_t);
        }

        if (!error.empty()) break;
        if (writtenDataBytes > std::numeric_limits<uint32_t>::max()) {
            error = "Rendered audio exceeded the WAV size limit.";
            break;
        }
        if (!writeWavHeader(file, static_cast<uint32_t>(writtenDataBytes))) {
            error = "Could not finalize the WAV file.";
            break;
        }
        std::fflush(file);
        success = std::ferror(file) == 0;
        if (!success && error.empty()) error = "Could not finalize the export file.";
    } while (false);

    std::fclose(file);
    if (!success) std::remove(request.outputPath.c_str());
    return success;
}
