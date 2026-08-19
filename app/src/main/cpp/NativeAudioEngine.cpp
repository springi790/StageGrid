#include "NativeAudioEngine.h"
#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <limits>
#include <thread>

namespace {
constexpr const char *TAG = "StageGridAudio";
constexpr double PI = 3.14159265358979323846;
inline float clampUnit(float x) noexcept { return std::clamp(x, -1.0f, 1.0f); }
}

NativeAudioEngine::TrackState::~TrackState() {
    alive.store(false, std::memory_order_release);
    if (decoderThread.joinable()) decoderThread.join();
}

NativeAudioEngine::NativeAudioEngine() {
    bankGenerations_[0].store(1, std::memory_order_relaxed);
    bankGenerations_[1].store(0, std::memory_order_relaxed);
    bankStartFrames_[0].store(0, std::memory_order_relaxed);
    bankStartFrames_[1].store(0, std::memory_order_relaxed);
    storePathState(0, PathState{});
    storePathState(1, PathState{});
}

NativeAudioEngine::~NativeAudioEngine() {
    std::lock_guard<std::mutex> lock(controlMutex_);
    playing_.store(false, std::memory_order_release);
    stopDecoderThreads();
    closeStreamLocked();
    tracks_.clear();
}

bool NativeAudioEngine::openStreamLocked() {
    closeStreamLocked();
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(2)
        ->setDataCallback(this)
        ->setErrorCallback(this);
    const int32_t requestedDevice = outputDeviceId_.load(std::memory_order_acquire);
    if (requestedDevice != oboe::kUnspecified) builder.setDeviceId(requestedDevice);

    auto result = builder.openStream(stream_);
    if (result != oboe::Result::OK) {
        builder.setSharingMode(oboe::SharingMode::Shared);
        result = builder.openStream(stream_);
    }
    if (result != oboe::Result::OK || !stream_) {
        setLastError(std::string("Could not open Oboe stream: ") + oboe::convertToText(result));
        return false;
    }
    streamStarted_.store(false, std::memory_order_release);
    outputSampleRate_.store(stream_->getSampleRate(), std::memory_order_release);
    framesPerBurst_.store(stream_->getFramesPerBurst(), std::memory_order_release);
    stream_->setBufferSizeInFrames(std::max(stream_->getFramesPerBurst() * 2, stream_->getBufferSizeInFrames()));
    return true;
}

void NativeAudioEngine::closeStreamLocked() {
    if (stream_) {
        if (streamStarted_.load(std::memory_order_acquire)) stream_->stop();
        stream_->close();
        stream_.reset();
    }
    streamStarted_.store(false, std::memory_order_release);
}

bool NativeAudioEngine::loadSong(const std::vector<std::string>& paths, const std::vector<int>& types, double bpm, int beatsPerBar, int64_t gridOffsetMs) {
    if (paths.empty() || paths.size() != types.size()) {
        setLastError("Invalid track list");
        return false;
    }
    std::lock_guard<std::mutex> lock(controlMutex_);
    playing_.store(false, std::memory_order_release);
    stopDecoderThreads();
    tracks_.clear();
    if (!openStreamLocked()) return false;

    double maxSeconds = 0.0;
    for (size_t i = 0; i < paths.size(); ++i) {
        auto reader = std::make_unique<WavReader>(paths[i]);
        if (!reader->valid()) {
            setLastError(paths[i] + ": " + reader->error());
            tracks_.clear();
            closeStreamLocked();
            return false;
        }
        maxSeconds = std::max(maxSeconds, reader->durationSeconds());
        tracks_.push_back(std::make_unique<TrackState>(std::move(reader), types[i]));
    }

    bpm_.store(bpm > 0.0 ? bpm : 0.0, std::memory_order_release);
    beatsPerBar_.store(std::max(1, beatsPerBar), std::memory_order_release);
    const int sr = outputSampleRate_.load(std::memory_order_acquire);
    gridOffsetFrame_.store(std::max<int64_t>(0, gridOffsetMs) * static_cast<int64_t>(sr) / 1000, std::memory_order_release);
    durationFrames_.store(static_cast<int64_t>(std::ceil(maxSeconds * sr)), std::memory_order_release);
    playheadFrame_.store(0, std::memory_order_release);
    trackGateUntilFrame_.store(-1, std::memory_order_release);
    outputFrameCounter_.store(0, std::memory_order_release);
    pathSwaps_.store(0, std::memory_order_release);
    pathSwapMisses_.store(0, std::memory_order_release);
    activeBank_.store(0, std::memory_order_release);
    pendingBank_.store(-1, std::memory_order_release);
    pendingGeneration_.store(0, std::memory_order_release);
    const uint64_t generation = requestHardPathReset(0, PathState{});
    underruns_.store(0, std::memory_order_release);

    startDecoderThreads();
    waitForActivePreload(generation, 500);
    return true;
}

void NativeAudioEngine::unloadSong() {
    std::lock_guard<std::mutex> lock(controlMutex_);
    playing_.store(false, std::memory_order_release);
    pendingGeneration_.store(0, std::memory_order_release);
    pendingBank_.store(-1, std::memory_order_release);
    stopDecoderThreads();
    tracks_.clear();
    closeStreamLocked();
    playheadFrame_.store(0, std::memory_order_release);
    durationFrames_.store(0, std::memory_order_release);
    trackGateUntilFrame_.store(-1, std::memory_order_release);
}

void NativeAudioEngine::startDecoderThreads() {
    for (auto &track : tracks_) {
        track->alive.store(true, std::memory_order_release);
        track->decoderThread = std::thread([this, ptr = track.get()] { decoderLoop(ptr); });
    }
}

void NativeAudioEngine::stopDecoderThreads() {
    for (auto &track : tracks_) track->alive.store(false, std::memory_order_release);
    for (auto &track : tracks_) {
        if (track->decoderThread.joinable()) track->decoderThread.join();
    }
}

NativeAudioEngine::PathState NativeAudioEngine::loadPathState(int bank) const noexcept {
    const int slot = std::clamp(bank, 0, 1);
    const auto &source = pathStates_[slot];
    PathState state;
    state.loopEnabled = source.loopEnabled.load(std::memory_order_acquire);
    state.loopStartFrame = source.loopStartFrame.load(std::memory_order_acquire);
    state.loopEndFrame = source.loopEndFrame.load(std::memory_order_acquire);
    state.jumpAtFrame = source.jumpAtFrame.load(std::memory_order_acquire);
    state.jumpTargetFrame = source.jumpTargetFrame.load(std::memory_order_acquire);
    state.disableLoopAfterJump = source.disableLoopAfterJump.load(std::memory_order_acquire);
    return state;
}

void NativeAudioEngine::storePathState(int bank, const PathState &state) noexcept {
    const int slot = std::clamp(bank, 0, 1);
    auto &target = pathStates_[slot];
    target.loopEnabled.store(state.loopEnabled, std::memory_order_relaxed);
    target.loopStartFrame.store(state.loopStartFrame, std::memory_order_relaxed);
    target.loopEndFrame.store(state.loopEndFrame, std::memory_order_relaxed);
    target.jumpAtFrame.store(state.jumpAtFrame, std::memory_order_relaxed);
    target.jumpTargetFrame.store(state.jumpTargetFrame, std::memory_order_relaxed);
    target.disableLoopAfterJump.store(state.disableLoopAfterJump, std::memory_order_relaxed);
}

void NativeAudioEngine::decoderLoop(TrackState *track) {
    std::array<uint64_t, 2> localGeneration{0, 0};
    std::array<int64_t, 2> cursor{0, 0};
    std::array<bool, 2> jumpConsumed{false, false};
    std::array<bool, 2> loopDisabledLocally{false, false};
    std::array<PathState, 2> localPath{};

    const auto initializeBank = [&](int slot, uint64_t generation) {
        track->rings[slot]->clear();
        cursor[slot] = bankStartFrames_[slot].load(std::memory_order_acquire);
        jumpConsumed[slot] = false;
        loopDisabledLocally[slot] = false;
        localPath[slot] = loadPathState(slot);
        localGeneration[slot] = generation;
        track->readyGeneration[slot].store(0, std::memory_order_release);
    };

    while (track->alive.load(std::memory_order_acquire)) {
        const int active = activeBank_.load(std::memory_order_acquire);
        const uint64_t activeGeneration = bankGenerations_[active].load(std::memory_order_acquire);
        if (localGeneration[active] != activeGeneration) initializeBank(active, activeGeneration);

        const int pending = pendingBank_.load(std::memory_order_acquire);
        const uint64_t pendingGeneration = pendingGeneration_.load(std::memory_order_acquire);
        const bool hasPending = pendingGeneration != 0 && pending >= 0 && pending <= 1 && pending != active &&
            bankGenerations_[pending].load(std::memory_order_acquire) == pendingGeneration;
        if (hasPending && localGeneration[pending] != pendingGeneration) initializeBank(pending, pendingGeneration);

        const int sr = std::max(1, outputSampleRate_.load(std::memory_order_acquire));
        constexpr size_t bankCapacityFrames = TrackState::kRingCapacitySamples / 2;
        const size_t activeLowWater = std::min<size_t>(bankCapacityFrames / 2, static_cast<size_t>(std::max(1024, sr / 8)));
        const size_t pendingTarget = std::min<size_t>(bankCapacityFrames - 2048, static_cast<size_t>(std::max(4096, sr / 2)));

        int fillSlot = active;
        if (hasPending) {
            const size_t activeAvailable = track->rings[active]->availableFrames();
            const size_t pendingAvailable = track->rings[pending]->availableFrames();
            if (activeAvailable > activeLowWater && pendingAvailable < pendingTarget) fillSlot = pending;
        }

        auto &ring = *track->rings[fillSlot];
        if (ring.freeFrames() < 512) {
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
            continue;
        }

        const int64_t duration = durationFrames_.load(std::memory_order_acquire);
        const size_t targetFrames = std::min<size_t>(2048, ring.freeFrames());
        size_t written = 0;
        const uint64_t expectedGeneration = localGeneration[fillSlot];
        for (; written < targetFrames; ++written) {
            if (expectedGeneration != bankGenerations_[fillSlot].load(std::memory_order_acquire)) break;
            float l = 0.0f, r = 0.0f;
            if (cursor[fillSlot] >= 0 && cursor[fillSlot] < duration) {
                const double sourceFrame = static_cast<double>(cursor[fillSlot]) * track->reader->sampleRate() / sr;
                track->reader->sampleAt(sourceFrame, l, r);
            }
            if (!ring.writeStereo(l, r)) break;
            cursor[fillSlot] = nextTimelineFrame(
                cursor[fillSlot],
                localPath[fillSlot],
                jumpConsumed[fillSlot],
                loopDisabledLocally[fillSlot]
            );
        }

        const size_t readyThreshold = std::min<size_t>(
            bankCapacityFrames / 4,
            static_cast<size_t>(std::max(256, sr / 40))
        );
        if (ring.availableFrames() >= readyThreshold) {
            track->readyGeneration[fillSlot].store(expectedGeneration, std::memory_order_release);
        }
        if (written == 0) std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
}

int64_t NativeAudioEngine::nextTimelineFrame(
    int64_t current,
    const PathState &path,
    bool &jumpConsumed,
    bool &loopDisabledLocally
) const noexcept {
    const int64_t next = current + 1;
    if (!jumpConsumed && path.jumpAtFrame >= 0 && next >= path.jumpAtFrame && path.jumpTargetFrame >= 0) {
        jumpConsumed = true;
        if (path.disableLoopAfterJump) loopDisabledLocally = true;
        return path.jumpTargetFrame;
    }

    if (!loopDisabledLocally && path.loopEnabled && path.loopEndFrame > 0 && next >= path.loopEndFrame) {
        return path.loopStartFrame;
    }
    return next;
}

int64_t NativeAudioEngine::firstDivergenceFrames(
    const PathState &current,
    const PathState &pending,
    int64_t startFrame
) const noexcept {
    int64_t best = -1;
    const auto consider = [&](int64_t transitionFrame) {
        if (transitionFrame < startFrame) return;
        const int64_t delta = transitionFrame - startFrame;
        if (best < 0 || delta < best) best = delta;
    };

    const bool loopDiffers = current.loopEnabled != pending.loopEnabled ||
        current.loopStartFrame != pending.loopStartFrame || current.loopEndFrame != pending.loopEndFrame;
    if (loopDiffers) {
        if (current.loopEnabled) consider(current.loopEndFrame);
        if (pending.loopEnabled) consider(pending.loopEndFrame);
    }

    const bool jumpDiffers = current.jumpAtFrame != pending.jumpAtFrame ||
        current.jumpTargetFrame != pending.jumpTargetFrame ||
        current.disableLoopAfterJump != pending.disableLoopAfterJump;
    if (jumpDiffers) {
        if (current.jumpAtFrame >= 0) consider(current.jumpAtFrame);
        if (pending.jumpAtFrame >= 0) consider(pending.jumpAtFrame);
    }
    return best;
}

uint64_t NativeAudioEngine::requestHardPathReset(int64_t frame, const PathState &path) {
    pendingGeneration_.store(0, std::memory_order_release);
    pendingBank_.store(-1, std::memory_order_release);
    const int active = activeBank_.load(std::memory_order_acquire);
    storePathState(active, path);
    bankStartFrames_[active].store(frame, std::memory_order_release);
    const uint64_t generation = generationCounter_.fetch_add(1, std::memory_order_acq_rel) + 1;
    bankGenerations_[active].store(generation, std::memory_order_release);
    callbackGeneration_ = 0;
    callbackJumpConsumed_ = false;
    callbackLoopDisabledLocally_ = false;
    return generation;
}

void NativeAudioEngine::stagePathChange(const PathState &path) {
    // Invalidate an older pending request before rewriting its inactive bank. This guarantees the
    // callback can never activate a half-published replacement configuration.
    pendingGeneration_.store(0, std::memory_order_release);
    pendingBank_.store(-1, std::memory_order_release);

    const int active = activeBank_.load(std::memory_order_acquire);
    const PathState current = loadPathState(active);
    const bool same = current.loopEnabled == path.loopEnabled &&
        current.loopStartFrame == path.loopStartFrame && current.loopEndFrame == path.loopEndFrame &&
        current.jumpAtFrame == path.jumpAtFrame && current.jumpTargetFrame == path.jumpTargetFrame &&
        current.disableLoopAfterJump == path.disableLoopAfterJump;
    if (same) return;

    const int64_t startFrame = playheadFrame_.load(std::memory_order_acquire);
    if (!playing_.load(std::memory_order_acquire)) {
        requestHardPathReset(startFrame, path);
        return;
    }

    const int inactive = 1 - active;
    storePathState(inactive, path);
    bankStartFrames_[inactive].store(startFrame, std::memory_order_release);
    const uint64_t generation = generationCounter_.fetch_add(1, std::memory_order_acq_rel) + 1;
    bankGenerations_[inactive].store(generation, std::memory_order_release);
    pendingStartOutputFrame_.store(outputFrameCounter_.load(std::memory_order_acquire), std::memory_order_release);
    pendingSafeOutputFrames_.store(firstDivergenceFrames(current, path, startFrame), std::memory_order_release);
    pendingBank_.store(inactive, std::memory_order_release);
    pendingGeneration_.store(generation, std::memory_order_release);
}

bool NativeAudioEngine::waitForActivePreload(uint64_t generation, int timeoutMs) {
    if (tracks_.empty()) return true;
    const int active = activeBank_.load(std::memory_order_acquire);
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
    while (std::chrono::steady_clock::now() < deadline) {
        bool ready = true;
        for (const auto &track : tracks_) {
            if (track->readyGeneration[active].load(std::memory_order_acquire) != generation) {
                ready = false;
                break;
            }
        }
        if (ready) return true;
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
    return false;
}

bool NativeAudioEngine::tryActivatePendingPath(int32_t numFrames) noexcept {
    const uint64_t generation = pendingGeneration_.load(std::memory_order_acquire);
    const int pending = pendingBank_.load(std::memory_order_acquire);
    if (generation == 0 || pending < 0 || pending > 1 || pending == activeBank_.load(std::memory_order_relaxed)) return false;
    if (bankGenerations_[pending].load(std::memory_order_acquire) != generation) return false;

    const uint64_t now = outputFrameCounter_.load(std::memory_order_relaxed);
    const uint64_t started = pendingStartOutputFrame_.load(std::memory_order_relaxed);
    const uint64_t elapsed = now >= started ? now - started : 0;
    const int64_t safeFrames = pendingSafeOutputFrames_.load(std::memory_order_relaxed);

    bool ready = true;
    const size_t needed = static_cast<size_t>(elapsed) + static_cast<size_t>(std::max(0, numFrames)) + 32;
    for (const auto &track : tracks_) {
        if (track->readyGeneration[pending].load(std::memory_order_acquire) != generation ||
            track->rings[pending]->availableFrames() < needed) {
            ready = false;
            break;
        }
    }

    if (!ready) {
        const int sr = std::max(1, outputSampleRate_.load(std::memory_order_relaxed));
        const bool safeWindowExpired = safeFrames >= 0 && elapsed + static_cast<uint64_t>(std::max(0, numFrames)) >= static_cast<uint64_t>(safeFrames);
        const bool preparationTooOld = elapsed > static_cast<uint64_t>(sr / 2);
        if (safeWindowExpired || preparationTooOld) {
            pendingGeneration_.store(0, std::memory_order_release);
            pendingBank_.store(-1, std::memory_order_release);
            pathSwapMisses_.fetch_add(1, std::memory_order_relaxed);
        }
        return false;
    }

    for (const auto &track : tracks_) {
        if (track->rings[pending]->discardFrames(static_cast<size_t>(elapsed)) != static_cast<size_t>(elapsed)) {
            pendingGeneration_.store(0, std::memory_order_release);
            pendingBank_.store(-1, std::memory_order_release);
            pathSwapMisses_.fetch_add(1, std::memory_order_relaxed);
            return false;
        }
    }

    activeBank_.store(pending, std::memory_order_release);
    callbackGeneration_ = generation;
    callbackJumpConsumed_ = false;
    callbackLoopDisabledLocally_ = false;
    pendingGeneration_.store(0, std::memory_order_release);
    pendingBank_.store(-1, std::memory_order_release);
    pathSwaps_.fetch_add(1, std::memory_order_relaxed);
    return true;
}

bool NativeAudioEngine::play() {
    std::lock_guard<std::mutex> lock(controlMutex_);
    if (tracks_.empty() || !stream_) return false;
    if (!streamStarted_.load(std::memory_order_acquire)) {
        const auto result = stream_->requestStart();
        if (result != oboe::Result::OK) {
            setLastError(std::string("Could not start audio stream: ") + oboe::convertToText(result));
            return false;
        }
        streamStarted_.store(true, std::memory_order_release);
    }
    playing_.store(true, std::memory_order_release);
    return true;
}

void NativeAudioEngine::pause() {
    playing_.store(false, std::memory_order_release);
}

void NativeAudioEngine::stop() {
    const bool wasPlaying = playing_.exchange(false, std::memory_order_acq_rel);
    trackGateUntilFrame_.store(-1, std::memory_order_release);
    PathState path = loadPathState(activeBank_.load(std::memory_order_acquire));
    path.loopEnabled = false;
    path.jumpAtFrame = -1;
    path.jumpTargetFrame = -1;
    path.disableLoopAfterJump = false;
    const uint64_t generation = requestHardPathReset(0, path);
    playheadFrame_.store(0, std::memory_order_release);
    outputFrameCounter_.store(0, std::memory_order_release);
    if (wasPlaying) waitForActivePreload(generation, 250);
}

void NativeAudioEngine::seekToMs(int64_t ms) {
    const int64_t target = std::clamp<int64_t>(msToFrames(ms), 0, durationFrames_.load(std::memory_order_acquire));
    const bool resume = playing_.exchange(false, std::memory_order_acq_rel);
    trackGateUntilFrame_.store(-1, std::memory_order_release);
    PathState path = loadPathState(activeBank_.load(std::memory_order_acquire));
    path.jumpAtFrame = -1;
    path.jumpTargetFrame = -1;
    path.disableLoopAfterJump = false;
    const uint64_t generation = requestHardPathReset(target, path);
    playheadFrame_.store(target, std::memory_order_release);
    waitForActivePreload(generation, 350);
    playing_.store(resume, std::memory_order_release);
}

void NativeAudioEngine::setTrackVolume(int index, float volume) {
    if (index >= 0 && index < static_cast<int>(tracks_.size())) tracks_[index]->volume.store(std::clamp(volume, 0.0f, 1.5f));
}
void NativeAudioEngine::setTrackMute(int index, bool muted) {
    if (index >= 0 && index < static_cast<int>(tracks_.size())) tracks_[index]->mute.store(muted);
}
void NativeAudioEngine::setTrackSolo(int index, bool solo) {
    if (index >= 0 && index < static_cast<int>(tracks_.size())) tracks_[index]->solo.store(solo);
}
void NativeAudioEngine::setTrackPan(int index, float pan) {
    if (index >= 0 && index < static_cast<int>(tracks_.size())) tracks_[index]->pan.store(std::clamp(pan, -1.0f, 1.0f));
}
void NativeAudioEngine::setTrackOutputRoute(int index, int route) {
    if (index >= 0 && index < static_cast<int>(tracks_.size())) {
        tracks_[index]->outputRoute.store(std::clamp(route, static_cast<int>(BOTH), static_cast<int>(RIGHT)));
    }
}
void NativeAudioEngine::setMasterVolume(float volume) { masterVolume_.store(std::clamp(volume, 0.0f, 1.25f)); }
void NativeAudioEngine::setClickEnabled(bool enabled) { clickEnabled_.store(enabled); }
void NativeAudioEngine::setGuideEnabled(bool enabled) { guideEnabled_.store(enabled); }
void NativeAudioEngine::setClickVolume(float volume) { clickVolume_.store(std::clamp(volume, 0.0f, 1.0f)); }
void NativeAudioEngine::setClickSubdivision(int subdivisionsPerBeat) {
    const int normalized = subdivisionsPerBeat == 2 || subdivisionsPerBeat == 3 || subdivisionsPerBeat == 4
        ? subdivisionsPerBeat : 1;
    clickSubdivision_.store(normalized, std::memory_order_release);
}
void NativeAudioEngine::setClickRoute(int route) {
    clickRoute_.store(std::clamp(route, static_cast<int>(BOTH), static_cast<int>(RIGHT)), std::memory_order_release);
}

void NativeAudioEngine::setLoop(bool enabled, int64_t startMs, int64_t endMs) {
    trackGateUntilFrame_.store(-1, std::memory_order_release);
    PathState path = loadPathState(activeBank_.load(std::memory_order_acquire));
    path.jumpAtFrame = -1;
    path.jumpTargetFrame = -1;
    path.disableLoopAfterJump = false;

    const int64_t start = msToFrames(startMs);
    const int64_t end = msToFrames(endMs);
    if (!enabled || end <= start + 1) {
        path.loopEnabled = false;
    } else {
        path.loopStartFrame = std::max<int64_t>(0, start);
        path.loopEndFrame = std::min<int64_t>(end, durationFrames_.load(std::memory_order_acquire));
        path.loopEnabled = path.loopEndFrame > path.loopStartFrame + 1;
    }
    stagePathChange(path);
}

void NativeAudioEngine::scheduleJump(int64_t atMs, int64_t targetMs, bool disableLoopAfterJump) {
    trackGateUntilFrame_.store(-1, std::memory_order_release);
    PathState path = loadPathState(activeBank_.load(std::memory_order_acquire));
    path.jumpAtFrame = msToFrames(atMs);
    path.jumpTargetFrame = msToFrames(targetMs);
    path.disableLoopAfterJump = disableLoopAfterJump;
    stagePathChange(path);
}

void NativeAudioEngine::clearScheduledJump() {
    PathState path = loadPathState(activeBank_.load(std::memory_order_acquire));
    path.jumpAtFrame = -1;
    path.jumpTargetFrame = -1;
    path.disableLoopAfterJump = false;
    stagePathChange(path);
}

bool NativeAudioEngine::prepareCountIn(int64_t targetMs, int bars) {
    const double bpm = bpm_.load(std::memory_order_acquire);
    if (bpm <= 0.0 || tracks_.empty()) {
        setLastError("Count-in requires a loaded song with a valid BPM.");
        return false;
    }

    const int safeBars = std::clamp(bars, 1, 2);
    const int sr = std::max(1, outputSampleRate_.load(std::memory_order_acquire));
    const int beatsPerBar = std::max(1, beatsPerBar_.load(std::memory_order_acquire));
    const double framesPerBeat = static_cast<double>(sr) * 60.0 / bpm;
    const int64_t countInFrames = std::max<int64_t>(1, static_cast<int64_t>(std::llround(framesPerBeat * beatsPerBar * safeBars)));
    const int64_t target = std::clamp<int64_t>(msToFrames(targetMs), 0, durationFrames_.load(std::memory_order_acquire));
    const int64_t start = target - countInFrames;

    const bool wasPlaying = playing_.exchange(false, std::memory_order_acq_rel);
    (void)wasPlaying;
    PathState path = loadPathState(activeBank_.load(std::memory_order_acquire));
    path.loopEnabled = false;
    path.jumpAtFrame = -1;
    path.jumpTargetFrame = -1;
    path.disableLoopAfterJump = false;
    trackGateUntilFrame_.store(target, std::memory_order_release);
    const uint64_t generation = requestHardPathReset(start, path);
    playheadFrame_.store(start, std::memory_order_release);
    waitForActivePreload(generation, 350);
    return true;
}

int64_t NativeAudioEngine::countInRemainingMs() const {
    const int64_t gate = trackGateUntilFrame_.load(std::memory_order_acquire);
    const int64_t current = playheadFrame_.load(std::memory_order_acquire);
    if (gate < 0 || current >= gate) return 0;
    return framesToMs(gate - current);
}

bool NativeAudioEngine::setOutputDevice(int32_t deviceId) {
    std::lock_guard<std::mutex> lock(controlMutex_);
    const bool resume = playing_.exchange(false, std::memory_order_acq_rel);
    pendingGeneration_.store(0, std::memory_order_release);
    pendingBank_.store(-1, std::memory_order_release);

    const int active = activeBank_.load(std::memory_order_acquire);
    const PathState oldPath = loadPathState(active);
    const int oldSr = std::max(1, outputSampleRate_.load(std::memory_order_acquire));
    const auto oldToMs = [oldSr](int64_t frame) -> int64_t {
        return frame < 0 ? -1 : (frame * 1000) / oldSr;
    };
    const int64_t rawPositionFrame = playheadFrame_.load(std::memory_order_acquire);
    const int64_t positionMsBefore = (rawPositionFrame * 1000) / oldSr;
    const int64_t loopStartMsBefore = oldToMs(oldPath.loopStartFrame);
    const int64_t loopEndMsBefore = oldToMs(oldPath.loopEndFrame);
    const int64_t jumpAtMsBefore = oldToMs(oldPath.jumpAtFrame);
    const int64_t jumpTargetMsBefore = oldToMs(oldPath.jumpTargetFrame);
    const int64_t gridOffsetMsBefore = oldToMs(gridOffsetFrame_.load(std::memory_order_acquire));
    const int64_t gateUntilMsBefore = oldToMs(trackGateUntilFrame_.load(std::memory_order_acquire));

    outputDeviceId_.store(deviceId, std::memory_order_release);
    if (!openStreamLocked()) {
        playing_.store(false, std::memory_order_release);
        return false;
    }

    const int sr = outputSampleRate_.load(std::memory_order_acquire);
    const auto msToNewFrames = [sr](int64_t ms) -> int64_t {
        return ms < 0 ? -1 : (ms * static_cast<int64_t>(std::max(1, sr))) / 1000;
    };
    const auto signedMsToNewFrames = [sr](int64_t ms) -> int64_t {
        return (ms * static_cast<int64_t>(std::max(1, sr))) / 1000;
    };
    double maxSeconds = 0.0;
    for (const auto &track : tracks_) maxSeconds = std::max(maxSeconds, track->reader->durationSeconds());
    durationFrames_.store(static_cast<int64_t>(std::ceil(maxSeconds * sr)), std::memory_order_release);
    gridOffsetFrame_.store(std::max<int64_t>(0, msToNewFrames(gridOffsetMsBefore)), std::memory_order_release);

    const bool countInWasActive = gateUntilMsBefore >= 0 && rawPositionFrame < trackGateUntilFrame_.load(std::memory_order_acquire);
    const int64_t convertedPosition = signedMsToNewFrames(positionMsBefore);
    const int64_t newPosition = countInWasActive
        ? std::min<int64_t>(convertedPosition, durationFrames_.load(std::memory_order_acquire))
        : std::clamp<int64_t>(convertedPosition, 0, durationFrames_.load(std::memory_order_acquire));
    playheadFrame_.store(newPosition, std::memory_order_release);

    PathState newPath = oldPath;
    if (newPath.loopEnabled) {
        newPath.loopStartFrame = msToNewFrames(loopStartMsBefore);
        newPath.loopEndFrame = msToNewFrames(loopEndMsBefore);
    }
    if (jumpAtMsBefore >= 0 && jumpTargetMsBefore >= 0) {
        newPath.jumpAtFrame = msToNewFrames(jumpAtMsBefore);
        newPath.jumpTargetFrame = msToNewFrames(jumpTargetMsBefore);
    }
    if (countInWasActive && gateUntilMsBefore >= 0) {
        trackGateUntilFrame_.store(msToNewFrames(gateUntilMsBefore), std::memory_order_release);
    } else {
        trackGateUntilFrame_.store(-1, std::memory_order_release);
    }

    const uint64_t generation = requestHardPathReset(newPosition, newPath);
    waitForActivePreload(generation, 350);
    if (resume) {
        const auto startResult = stream_->requestStart();
        if (startResult != oboe::Result::OK) {
            setLastError(std::string("Could not start selected audio device: ") + oboe::convertToText(startResult));
            return false;
        }
        streamStarted_.store(true, std::memory_order_release);
    }
    playing_.store(resume, std::memory_order_release);
    return true;
}

int64_t NativeAudioEngine::msToFrames(int64_t ms) const noexcept {
    return ms * static_cast<int64_t>(std::max(1, outputSampleRate_.load(std::memory_order_relaxed))) / 1000;
}
int64_t NativeAudioEngine::framesToMs(int64_t frames) const noexcept {
    return frames * 1000 / std::max(1, outputSampleRate_.load(std::memory_order_relaxed));
}
int64_t NativeAudioEngine::positionMs() const { return framesToMs(playheadFrame_.load(std::memory_order_acquire)); }
int64_t NativeAudioEngine::durationMs() const { return framesToMs(durationFrames_.load(std::memory_order_acquire)); }

float NativeAudioEngine::generatedClickSample(int64_t timelineFrame) const noexcept {
    if (!clickEnabled_.load(std::memory_order_relaxed)) return 0.0f;
    const double bpm = bpm_.load(std::memory_order_relaxed);
    if (bpm <= 0.0) return 0.0f;
    const int sr = std::max(1, outputSampleRate_.load(std::memory_order_relaxed));
    const int64_t gridOffset = gridOffsetFrame_.load(std::memory_order_relaxed);

    const int subdivision = std::max(1, clickSubdivision_.load(std::memory_order_relaxed));
    const double framesPerBeat = sr * 60.0 / bpm;
    const double framesPerTick = framesPerBeat / subdivision;
    if (framesPerTick < 1.0) return 0.0f;

    const double relative = static_cast<double>(timelineFrame - gridOffset);
    const int64_t tickIndex = static_cast<int64_t>(std::floor(relative / framesPerTick));
    const double tickStart = tickIndex * framesPerTick;
    const double offset = relative - tickStart;
    const double clickFrames = sr * 0.022;
    if (offset < 0.0 || offset >= clickFrames) return 0.0f;

    const bool onBeat = (tickIndex % subdivision) == 0;
    const int64_t beatIndex = tickIndex / subdivision;
    const bool barAccent = onBeat && beatIndex % std::max(1, beatsPerBar_.load(std::memory_order_relaxed)) == 0;
    const double freq = barAccent ? 1900.0 : (onBeat ? 1350.0 : 950.0);
    const double gain = barAccent ? 1.0 : (onBeat ? 0.82 : 0.58);
    const double t = offset / sr;
    const double envelope = std::exp(-t * 105.0);
    return static_cast<float>(std::sin(2.0 * PI * freq * t) * envelope * gain *
                              clickVolume_.load(std::memory_order_relaxed));
}

oboe::DataCallbackResult NativeAudioEngine::onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) {
    auto *out = static_cast<float *>(audioData);
    const auto begin = std::chrono::steady_clock::now();
    std::fill(out, out + numFrames * 2, 0.0f);

    if (!playing_.load(std::memory_order_acquire) || tracks_.empty()) return oboe::DataCallbackResult::Continue;

    tryActivatePendingPath(numFrames);
    const int active = activeBank_.load(std::memory_order_acquire);
    const uint64_t generation = bankGenerations_[active].load(std::memory_order_acquire);
    if (generation != callbackGeneration_) {
        callbackGeneration_ = generation;
        callbackJumpConsumed_ = false;
        callbackLoopDisabledLocally_ = false;
    }
    const PathState callbackPath = loadPathState(active);

    bool anySolo = false;
    for (const auto &track : tracks_) anySolo = anySolo || track->solo.load(std::memory_order_relaxed);

    int64_t frame = playheadFrame_.load(std::memory_order_relaxed);
    const int64_t duration = durationFrames_.load(std::memory_order_relaxed);
    int32_t renderedFrames = 0;
    for (int32_t i = 0; i < numFrames; ++i) {
        float mixL = 0.0f, mixR = 0.0f;
        bool trackUnderrun = false;
        const int64_t gateUntil = trackGateUntilFrame_.load(std::memory_order_relaxed);
        const bool suppressImportedTracks = gateUntil >= 0 && frame < gateUntil;
        if (gateUntil >= 0 && frame >= gateUntil) {
            trackGateUntilFrame_.store(-1, std::memory_order_relaxed);
        }

        for (const auto &track : tracks_) {
            float l = 0.0f, r = 0.0f;
            if (!track->rings[active]->readStereo(l, r)) {
                trackUnderrun = true;
                continue;
            }
            if (suppressImportedTracks) continue;
            if (track->mute.load(std::memory_order_relaxed)) continue;
            if (anySolo && !track->solo.load(std::memory_order_relaxed)) continue;
            if (track->type == CLICK) continue;
            if (track->type == GUIDE && !guideEnabled_.load(std::memory_order_relaxed)) continue;

            const float volume = track->volume.load(std::memory_order_relaxed);
            const int route = track->outputRoute.load(std::memory_order_relaxed);
            if (route == LEFT || route == RIGHT) {
                const float mono = (l + r) * 0.5f * volume;
                if (route == LEFT) mixL += mono;
                else mixR += mono;
            } else {
                const float pan = track->pan.load(std::memory_order_relaxed);
                const float leftGain = std::sqrt(0.5f * (1.0f - pan));
                const float rightGain = std::sqrt(0.5f * (1.0f + pan));
                mixL += l * volume * leftGain * 1.41421356f;
                mixR += r * volume * rightGain * 1.41421356f;
            }
        }
        if (trackUnderrun) underruns_.fetch_add(1, std::memory_order_relaxed);
        const float click = generatedClickSample(frame);
        const int clickRoute = clickRoute_.load(std::memory_order_relaxed);
        if (clickRoute == LEFT) mixL += click;
        else if (clickRoute == RIGHT) mixR += click;
        else {
            mixL += click;
            mixR += click;
        }
        const float master = masterVolume_.load(std::memory_order_relaxed);
        out[i * 2] = clampUnit(mixL * master);
        out[i * 2 + 1] = clampUnit(mixR * master);
        renderedFrames++;

        frame = nextTimelineFrame(frame, callbackPath, callbackJumpConsumed_, callbackLoopDisabledLocally_);
        if (frame >= duration) {
            frame = duration;
            playing_.store(false, std::memory_order_release);
            break;
        }
    }
    playheadFrame_.store(frame, std::memory_order_release);
    if (renderedFrames > 0) outputFrameCounter_.fetch_add(static_cast<uint64_t>(renderedFrames), std::memory_order_relaxed);

    const auto elapsed = std::chrono::duration<double>(std::chrono::steady_clock::now() - begin).count();
    const double budget = static_cast<double>(numFrames) / std::max(1, stream->getSampleRate());
    const float load = budget > 0.0 ? static_cast<float>(elapsed / budget) : 0.0f;
    const float previous = cpuLoad_.load(std::memory_order_relaxed);
    cpuLoad_.store(previous * 0.9f + load * 0.1f, std::memory_order_relaxed);
    return oboe::DataCallbackResult::Continue;
}

void NativeAudioEngine::onErrorAfterClose(oboe::AudioStream *, oboe::Result error) {
    __android_log_print(ANDROID_LOG_ERROR, TAG, "Audio stream disconnected: %s", oboe::convertToText(error));
    setLastError(std::string("Audio stream disconnected: ") + oboe::convertToText(error));
    playing_.store(false, std::memory_order_release);
    streamStarted_.store(false, std::memory_order_release);
}

void NativeAudioEngine::setLastError(std::string message) {
    std::lock_guard<std::mutex> lock(errorMutex_);
    lastError_ = std::move(message);
}

std::string NativeAudioEngine::lastError() const {
    std::lock_guard<std::mutex> lock(errorMutex_);
    return lastError_;
}
