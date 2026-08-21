#pragma once

// StageGrid performs Signalsmith processing in background decoder workers which feed generous
// SPSC ring buffers. We therefore do not need Signalsmith's split-computation mode: that mode is
// useful when processing directly under a strict callback deadline, but it deliberately adds one
// extra processing interval of output latency. Keeping computation unsplit lets the ring buffer
// absorb short CPU bursts instead.
#include "signalsmith-stretch.h"

namespace signalsmith::stretch {

template<typename Sample = float, class RandomEngine = void>
struct StageGridStretch : SignalsmithStretch<Sample, RandomEngine> {
    using Base = SignalsmithStretch<Sample, RandomEngine>;

    void presetCheaper(int channels, Sample sampleRate, bool /*splitComputation*/ = true) {
        Base::presetCheaper(channels, sampleRate, false);
    }

    /**
     * StageGrid's decoder seek currently primes each bank from audio immediately BEFORE the bank
     * start, then begins process() at the bank start. Upstream's low-level latency model has two
     * halves: input look-ahead and output delay. With our historical-preroll strategy both halves
     * contribute to the audible offset relative to the authoritative timeline, so Click/Guide must
     * compensate their sum. Returning only Base::outputLatency() left them slightly early whenever
     * pitch/time DSP was active.
     *
     * This changes only StageGrid's externally reported compensation. Signalsmith's own internal
     * processing remains untouched.
     */
    int outputLatency() const {
        return Base::inputLatency() + Base::outputLatency();
    }
};

} // namespace signalsmith::stretch

// NativeAudioEngine.cpp intentionally keeps the upstream type name. The forced-include policy is
// scoped to the stagegrid_audio target and maps that one type token to our low-latency adapter.
#define SignalsmithStretch StageGridStretch
