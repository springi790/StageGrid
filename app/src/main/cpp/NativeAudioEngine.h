#pragma once
#include <oboe/Oboe.h>
#include "SpscRingBuffer.h"
#include "WavReader.h"
#include <atomic>
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
    void setMasterVolume(float volume);
    void setClickEnabled(bool enabled);
    void setGuideEnabled(bool enabled);
    void setClickVolume(float volume);
    void setClickSubdivision(int subdivisionsPerBeat);
    void setClickRoute(int route);
    void setLoop(bool enabled, int64_t startMs, int64_t endMs);
    void scheduleJump(int64_t atMs, int64_t targetMs, bool disableLoopAfterJump);
    void clearScheduledJump();
    bool prepareCountIn(int64_t targetMs, int bars);
    int64_t countInRemainingMs() const;
    bool setOutputDevice(int32_t deviceId);

    int64_t positionMs() const;
    int64_t durationMs() const;
    bool isPlaying() const noexcept { return playing_.load(std::memory_order_acquire); }
    int sampleRate() const noexcept { return outputSampleRate_.load(std::memory_order_acquire); }
    int framesPerBurst() const noexcept { return framesPerBurst_.load(std::memory_order_acquire); }
    int64_t underruns() const noexcept { return underruns_.load(std::memory_order_acquire); }
    float cpuLoad() const noexcept { return cpuLoad_.load(std::memory_order_acquire); }
    int loadedTracks() const noexcept { return static_cast<int>(tracks_.size()); }
    std::string lastError() const;

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    struct TrackState {
        explicit TrackState(std::unique_ptr<WavReader> readerIn, int typeIn)
            : reader(std::move(readerIn)), type(typeIn), ring(kRingCapacitySamples) {}
        ~TrackState();
        static constexpr size_t kRingCapacitySamples = 192000;
        std::unique_ptr<WavReader> reader;
        int type{OTHER};
        SpscRingBuffer ring;
        std::atomic<float> volume{1.0f};
        std::atomic<float> pan{0.0f};
        std::atomic<int> outputRoute{BOTH};
        std::atomic<bool> mute{false};
        std::atomic<bool> solo{false};
        std::atomic<bool> alive{true};
        std::atomic<uint64_t> readyGeneration{0};
        std::thread decoderThread;
    };

    bool openStreamLocked();
    void closeStreamLocked();
    void startDecoderThreads();
    void stopDecoderThreads();
    void decoderLoop(TrackState *track);
    int64_t nextTimelineFrame(int64_t current, bool &jumpConsumed, bool &loopDisabledLocally) const noexcept;
    int64_t msToFrames(int64_t ms) const noexcept;
    int64_t framesToMs(int64_t frames) const noexcept;
    void requestPathReset(int64_t frame);
    bool waitForPreload(uint64_t generation, int timeoutMs);
    float generatedClickSample(int64_t timelineFrame) const noexcept;
    void setLastError(std::string message);

    mutable std::mutex controlMutex_;
    std::shared_ptr<oboe::AudioStream> stream_;
    std::vector<std::unique_ptr<TrackState>> tracks_;

    std::atomic<int32_t> outputSampleRate_{48000};
    std::atomic<int32_t> framesPerBurst_{0};
    std::atomic<int32_t> outputDeviceId_{oboe::kUnspecified};
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
    std::atomic<double> bpm_{0.0};
    std::atomic<int> beatsPerBar_{4};
    std::atomic<int64_t> gridOffsetFrame_{0};

    std::atomic<bool> loopEnabled_{false};
    std::atomic<int64_t> loopStartFrame_{0};
    std::atomic<int64_t> loopEndFrame_{0};
    std::atomic<int64_t> jumpAtFrame_{-1};
    std::atomic<int64_t> jumpTargetFrame_{-1};
    std::atomic<bool> disableLoopAfterJump_{false};
    std::atomic<int64_t> trackGateUntilFrame_{-1};

    std::atomic<uint64_t> pathGeneration_{1};
    std::atomic<int64_t> resetFrame_{0};
    uint64_t callbackGeneration_{0};
    bool callbackJumpConsumed_{false};
    bool callbackLoopDisabledLocally_{false};

    std::atomic<int64_t> underruns_{0};
    std::atomic<float> cpuLoad_{0.0f};
    mutable std::mutex errorMutex_;
    std::string lastError_;
};
