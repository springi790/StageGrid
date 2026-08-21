#pragma once
#include <oboe/Oboe.h>
#include "SpscRingBuffer.h"
#include "WavReader.h"
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

class NativeAudioEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    enum TrackType : int { OTHER=0, DRUMS=1, BASS=2, GUITAR=3, KEYS=4, SYNTH=5, STRINGS=6, VOCALS=7, PERCUSSION=8, CLICK=9, GUIDE=10, PAD=11 };
    enum StereoRoute : int { BOTH=0, LEFT=1, RIGHT=2 };

    NativeAudioEngine();
    ~NativeAudioEngine() override;

    bool loadSong(const std::vector<std::string>& paths, const std::vector<int>& types, double bpm, int beatsPerBar, int64_t gridOffsetMs);
    void unloadSong();
    bool play();
    void pause();
    void stop();
    void seekToMs(int64_t ms);
    void setTrackVolume(int index, float volume);
    void setTrackMute(int index, bool muted);
    void setTrackSolo(int index, bool solo);
    void setTrackPan(int index, float pan);
    void setTrackOutputRoute(int index, int route);
    void setTrackOutputBus(int index, int bus);
    void setMasterVolume(float volume);
    void setClickEnabled(bool enabled);
    void setGuideEnabled(bool enabled);
    void setClickVolume(float volume);
    void setClickSubdivision(int subdivisionsPerBeat);
    void setClickRoute(int route);
    void setClickOutputBus(int bus);
    void setTempoRatio(float ratio);
    void setPitchSemitones(float semitones);
    void setLoop(bool enabled, int64_t startMs, int64_t endMs);
    void scheduleJump(int64_t atMs, int64_t targetMs, bool disableLoopAfterJump);
    void clearScheduledJump();
    bool scheduleGuideCue(const std::vector<float>& monoSamples, int64_t atMs, int64_t suppressUntilMs, int route, int bus, float volume);
    void clearGuideCue() noexcept;
    bool prepareCountIn(int64_t targetMs, int bars);
    int64_t countInRemainingMs() const;
    bool setOutputDevice(int32_t deviceId, int requestedChannels);
    bool startOutputTest(int channelIndex, int durationMs);

    int64_t positionMs() const;
    int64_t durationMs() const;
    bool isPlaying() const noexcept { return playing_.load(std::memory_order_acquire); }
    int sampleRate() const noexcept { return outputSampleRate_.load(std::memory_order_acquire); }
    int framesPerBurst() const noexcept { return framesPerBurst_.load(std::memory_order_acquire); }
    int outputChannelCount() const noexcept { return outputChannelCount_.load(std::memory_order_acquire); }
    int requestedOutputChannelCount() const noexcept { return requestedOutputChannelCount_.load(std::memory_order_acquire); }
    bool multichannelFallback() const noexcept { return multichannelFallback_.load(std::memory_order_acquire); }
    int outputDeviceId() const noexcept { return outputDeviceId_.load(std::memory_order_acquire); }
    int64_t underruns() const noexcept { return underruns_.load(std::memory_order_acquire); }
    float cpuLoad() const noexcept { return cpuLoad_.load(std::memory_order_acquire); }
    int loadedTracks() const noexcept { return static_cast<int>(tracks_.size()); }
    int64_t pathSwaps() const noexcept { return pathSwaps_.load(std::memory_order_acquire); }
    int64_t pathSwapMisses() const noexcept { return pathSwapMisses_.load(std::memory_order_acquire); }
    bool pathChangePending() const noexcept {
        const auto generation = pendingGeneration_.load(std::memory_order_acquire);
        return generation != 0 && generation != kPathClaimedGeneration;
    }
    float tempoRatio() const noexcept { return tempoRatio_.load(std::memory_order_acquire); }
    float pitchSemitones() const noexcept { return pitchSemitones_.load(std::memory_order_acquire); }
    bool dspActive() const noexcept {
        return std::abs(tempoRatio_.load(std::memory_order_acquire) - 1.0f) > 0.0001f ||
            std::abs(pitchSemitones_.load(std::memory_order_acquire)) > 0.0001f;
    }
    float dspCpuLoad() const noexcept { return dspCpuLoad_.load(std::memory_order_acquire); }
    int dspLatencyMs() const noexcept {
        const int sr = std::max(1, outputSampleRate_.load(std::memory_order_acquire));
        return static_cast<int>(dspLatencyFrames_.load(std::memory_order_acquire) * 1000LL / sr);
    }
    std::string lastError() const;

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    struct PathState {
        bool loopEnabled{false};
        int64_t loopStartFrame{0};
        int64_t loopEndFrame{0};
        int64_t jumpAtFrame{-1};
        int64_t jumpTargetFrame{-1};
        bool disableLoopAfterJump{false};
    };

    struct AtomicPathState {
        std::atomic<bool> loopEnabled{false};
        std::atomic<int64_t> loopStartFrame{0};
        std::atomic<int64_t> loopEndFrame{0};
        std::atomic<int64_t> jumpAtFrame{-1};
        std::atomic<int64_t> jumpTargetFrame{-1};
        std::atomic<bool> disableLoopAfterJump{false};
    };

    struct GuideCueData {
        std::vector<float> monoSamples;
        int64_t startFrame{0};
        int64_t suppressUntilFrame{0};
        int route{BOTH};
        int bus{0};
        float volume{1.0f};
        std::atomic<int64_t> playbackIndex{-1};
        std::atomic<bool> consumed{false};
    };

    struct TrackState {
        explicit TrackState(std::unique_ptr<WavReader> readerIn, int typeIn)
            : reader(std::move(readerIn)),
              type(typeIn),
              rings{std::make_unique<SpscRingBuffer>(kRingCapacitySamples),
                    std::make_unique<SpscRingBuffer>(kRingCapacitySamples)} {}
        ~TrackState();

        static constexpr size_t kRingCapacitySamples = 96000;
        std::unique_ptr<WavReader> reader;
        int type{OTHER};
        std::array<std::unique_ptr<SpscRingBuffer>, 2> rings;
        std::array<std::atomic<uint64_t>, 2> readyGeneration{};
        std::atomic<float> volume{1.0f};
        std::atomic<float> pan{0.0f};
        std::atomic<int> outputRoute{BOTH};
        std::atomic<int> outputBus{0};
        std::atomic<bool> mute{false};
        std::atomic<bool> solo{false};
        std::atomic<bool> alive{true};
        std::thread decoderThread;
    };

    bool openStreamLocked();
    bool tryOpenStreamLocked(int channelCount, oboe::SharingMode sharingMode);
    void closeStreamLocked();
    void startDecoderThreads();
    void stopDecoderThreads();
    void decoderLoop(TrackState *track);
    void reprimeDspFromControl();

    PathState loadPathState(int bank) const noexcept;
    void storePathState(int bank, const PathState &state) noexcept;
    int64_t nextTimelineFrame(int64_t current, const PathState &path, bool &jumpConsumed, bool &loopDisabledLocally) const noexcept;
    int64_t firstDivergenceFrames(const PathState &current, const PathState &pending, int64_t startFrame) const noexcept;
    void cancelPendingPathFromControl() noexcept;
    uint64_t requestHardPathReset(int64_t frame, const PathState &path);
    void stagePathChange(const PathState &path);
    bool waitForActivePreload(uint64_t generation, int timeoutMs);
    bool tryActivatePendingPath(int32_t numFrames) noexcept;

    int64_t msToFrames(int64_t ms) const noexcept;
    int64_t framesToMs(int64_t frames) const noexcept;
    float generatedClickSample(double timelineFrame, float tempoRatio) const noexcept;
    int normalizedBus(int bus, int channelCount) const noexcept;
    void routeStereoPair(std::array<float, 8> &mix, int channelCount, int bus, int route, float l, float r, float pan, float volume) const noexcept;
    void routeMono(std::array<float, 8> &mix, int channelCount, int bus, int route, float sample) const noexcept;
    void setLastError(std::string message);

    mutable std::mutex controlMutex_;
    std::shared_ptr<oboe::AudioStream> stream_;
    std::vector<std::unique_ptr<TrackState>> tracks_;

    std::atomic<int32_t> outputSampleRate_{48000};
    std::atomic<int32_t> framesPerBurst_{0};
    std::atomic<int32_t> outputDeviceId_{oboe::kUnspecified};
    std::atomic<int32_t> requestedOutputChannelCount_{2};
    std::atomic<int32_t> outputChannelCount_{2};
    std::atomic<bool> multichannelFallback_{false};
    std::atomic<bool> playing_{false};
    std::atomic<bool> streamStarted_{false};
    std::atomic<int64_t> playheadFrame_{0};
    std::atomic<int64_t> durationFrames_{0};
    std::atomic<float> masterVolume_{1.0f};
    std::atomic<bool> clickEnabled_{true};
    std::atomic<bool> guideEnabled_{true};
    std::atomic<float> clickVolume_{0.75f};
    std::atomic<int> clickSubdivision_{1};
    std::atomic<int> clickRoute_{BOTH};
    std::atomic<int> clickOutputBus_{0};
    std::atomic<double> bpm_{0.0};
    std::atomic<int> beatsPerBar_{4};
    std::atomic<int64_t> gridOffsetFrame_{0};
    std::atomic<int64_t> trackGateUntilFrame_{-1};

    // DSP is bypassed bit-for-bit at 100% tempo / 0 semitones. When active, each stem owns an
    // independent Signalsmith processor but all processors consume the same authoritative source
    // timeline ratio/generation, preventing stem drift while keeping per-track routing intact.
    std::atomic<float> tempoRatio_{1.0f};
    std::atomic<float> pitchSemitones_{0.0f};
    std::atomic<double> timelineFraction_{0.0};
    std::atomic<float> dspCpuLoad_{0.0f};
    std::atomic<int> dspLatencyFrames_{0};

    std::atomic<int> outputTestChannel_{-1};
    std::atomic<int64_t> outputTestRemainingFrames_{0};
    std::atomic<int64_t> outputTestFrame_{0};

    // Dynamic section-call PCM is built on a background thread and published as an immutable cue.
    // Ownership handoff hardening is tracked separately from the callback's audio rendering logic.
    std::shared_ptr<GuideCueData> guideCue_;
    std::mutex guideCueControlMutex_;
    std::atomic<bool> guideCueReaderActive_{false};

    static constexpr uint64_t kPathClaimedGeneration = ~uint64_t{0};
    std::array<AtomicPathState, 2> pathStates_{};
    std::array<std::atomic<uint64_t>, 2> bankGenerations_{};
    std::array<std::atomic<int64_t>, 2> bankStartFrames_{};
    std::atomic<uint64_t> generationCounter_{1};
    std::atomic<int> activeBank_{0};
    std::atomic<uint64_t> pendingGeneration_{0};
    std::atomic<int> pendingBank_{-1};
    std::atomic<uint64_t> pendingStartOutputFrame_{0};
    std::atomic<int64_t> pendingSafeOutputFrames_{-1};
    std::atomic<uint64_t> outputFrameCounter_{0};
    uint64_t callbackGeneration_{0};
    bool callbackJumpConsumed_{false};
    bool callbackLoopDisabledLocally_{false};

    std::atomic<int64_t> pathSwaps_{0};
    std::atomic<int64_t> pathSwapMisses_{0};
    std::atomic<int64_t> underruns_{0};
    std::atomic<float> cpuLoad_{0.0f};
    mutable std::mutex errorMutex_;
    std::string lastError_;
};
