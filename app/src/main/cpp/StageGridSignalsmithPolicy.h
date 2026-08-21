#pragma once

// StageGrid performs Signalsmith processing in background decoder workers which feed generous
// SPSC ring buffers. We therefore do not need Signalsmith's split-computation mode: that mode is
// useful when processing directly under a strict callback deadline, but it deliberately adds one
// extra processing interval of output latency. Keeping computation unsplit lowers the musical
// offset which Click/Guide must compensate while the ring buffer absorbs short CPU bursts.
#include "signalsmith-stretch.h"

namespace signalsmith::stretch {

template<typename Sample = float, class RandomEngine = void>
struct StageGridStretch : SignalsmithStretch<Sample, RandomEngine> {
    using Base = SignalsmithStretch<Sample, RandomEngine>;

    void presetCheaper(int channels, Sample sampleRate, bool /*splitComputation*/ = true) {
        Base::presetCheaper(channels, sampleRate, false);
    }
};

} // namespace signalsmith::stretch

// NativeAudioEngine.cpp intentionally keeps the upstream type name. The forced-include policy is
// scoped to the stagegrid_audio target and maps that one type token to our low-latency adapter.
#define SignalsmithStretch StageGridStretch
