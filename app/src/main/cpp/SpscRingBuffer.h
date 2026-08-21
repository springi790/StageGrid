#pragma once
#include <atomic>
#include <cstddef>
#include <vector>

class SpscRingBuffer {
public:
    explicit SpscRingBuffer(size_t capacitySamples)
        : buffer_(capacitySamples), capacity_(capacitySamples) {}

    size_t availableSamples() const noexcept {
        const auto w = writeIndex_.load(std::memory_order_acquire);
        const auto r = readIndex_.load(std::memory_order_acquire);
        return w - r;
    }

    size_t freeSamples() const noexcept {
        return capacity_ - availableSamples();
    }

    size_t availableFrames() const noexcept { return availableSamples() / 2; }
    size_t freeFrames() const noexcept { return freeSamples() / 2; }

    bool writeStereo(float left, float right) noexcept {
        const auto w = writeIndex_.load(std::memory_order_relaxed);
        const auto r = readIndex_.load(std::memory_order_acquire);
        if (capacity_ - (w - r) < 2) return false;
        buffer_[w % capacity_] = left;
        buffer_[(w + 1) % capacity_] = right;
        writeIndex_.store(w + 2, std::memory_order_release);
        return true;
    }

    /**
     * Links the alternate prepared bank for a very short handoff crossfade.
     *
     * Both rings still remain ordinary SPSC buffers. During the handoff the audio callback is the
     * sole consumer of both rings, while each decoder remains the sole producer of its own ring.
     */
    void setCrossfadePeer(SpscRingBuffer *peer, size_t frames) noexcept {
        crossfadePeer_ = peer;
        crossfadeFrames_ = frames;
    }

    bool readStereo(float &left, float &right) noexcept {
        if (!readStereoRaw(left, right)) return false;

        if (crossfadeRemaining_ > 0 && crossfadePeer_ != nullptr) {
            float previousLeft = 0.0f;
            float previousRight = 0.0f;
            if (crossfadePeer_->readStereoRaw(previousLeft, previousRight)) {
                const size_t total = crossfadeTotal_ > 0 ? crossfadeTotal_ : 1;
                const size_t completed = total - crossfadeRemaining_;
                float t = total <= 1
                    ? 1.0f
                    : static_cast<float>(completed) / static_cast<float>(total - 1);
                // Smoothstep keeps both the start and end slope at zero. For identical content the
                // gain stays exactly unity, while small DSP phase differences are blended away.
                t = t * t * (3.0f - 2.0f * t);
                left = previousLeft * (1.0f - t) + left * t;
                right = previousRight * (1.0f - t) + right * t;
                --crossfadeRemaining_;
            } else {
                // The old bank should normally have ample buffered audio. If it does not, never
                // sacrifice the prepared bank: fall back to it immediately instead of underrunning.
                crossfadeRemaining_ = 0;
            }
        }
        return true;
    }

    /**
     * Advances the consumer by up to `frames` without reading samples.
     *
     * The double-buffered path engine uses this only on an inactive/prepared bank immediately
     * before that bank becomes active. It keeps the prepared bank aligned with the output frames
     * that elapsed while the currently-active bank continued feeding the callback. That exact
     * handoff point also arms a short crossfade from the old bank so selecting a section cannot
     * expose a phase discontinuity as a click or micro-drop on a large PA.
     */
    size_t discardFrames(size_t frames) noexcept {
        const auto r = readIndex_.load(std::memory_order_relaxed);
        const auto w = writeIndex_.load(std::memory_order_acquire);
        const size_t available = (w - r) / 2;
        const size_t discard = frames < available ? frames : available;
        readIndex_.store(r + discard * 2, std::memory_order_release);

        if (crossfadePeer_ != nullptr && crossfadeFrames_ > 0) {
            const size_t peerAvailable = crossfadePeer_->availableFrames();
            crossfadeTotal_ = crossfadeFrames_ < peerAvailable ? crossfadeFrames_ : peerAvailable;
            crossfadeRemaining_ = crossfadeTotal_;
        }
        return discard;
    }

    void clear() noexcept {
        const auto w = writeIndex_.load(std::memory_order_acquire);
        readIndex_.store(w, std::memory_order_release);
        crossfadeRemaining_ = 0;
        crossfadeTotal_ = 0;
    }

private:
    bool readStereoRaw(float &left, float &right) noexcept {
        const auto r = readIndex_.load(std::memory_order_relaxed);
        const auto w = writeIndex_.load(std::memory_order_acquire);
        if (w - r < 2) return false;
        left = buffer_[r % capacity_];
        right = buffer_[(r + 1) % capacity_];
        readIndex_.store(r + 2, std::memory_order_release);
        return true;
    }

    std::vector<float> buffer_;
    const size_t capacity_;
    alignas(64) std::atomic<size_t> writeIndex_{0};
    alignas(64) std::atomic<size_t> readIndex_{0};
    SpscRingBuffer *crossfadePeer_{nullptr};
    size_t crossfadeFrames_{0};
    size_t crossfadeRemaining_{0};
    size_t crossfadeTotal_{0};
};
