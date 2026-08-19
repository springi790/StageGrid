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

    bool readStereo(float &left, float &right) noexcept {
        const auto r = readIndex_.load(std::memory_order_relaxed);
        const auto w = writeIndex_.load(std::memory_order_acquire);
        if (w - r < 2) return false;
        left = buffer_[r % capacity_];
        right = buffer_[(r + 1) % capacity_];
        readIndex_.store(r + 2, std::memory_order_release);
        return true;
    }

    /**
     * Advances the consumer by up to `frames` without reading samples.
     *
     * The double-buffered path engine uses this only on an inactive/prepared bank immediately
     * before that bank becomes active. It keeps the prepared bank aligned with the output frames
     * that elapsed while the currently-active bank continued feeding the callback.
     */
    size_t discardFrames(size_t frames) noexcept {
        const auto r = readIndex_.load(std::memory_order_relaxed);
        const auto w = writeIndex_.load(std::memory_order_acquire);
        const size_t available = (w - r) / 2;
        const size_t discard = frames < available ? frames : available;
        readIndex_.store(r + discard * 2, std::memory_order_release);
        return discard;
    }

    void clear() noexcept {
        const auto w = writeIndex_.load(std::memory_order_acquire);
        readIndex_.store(w, std::memory_order_release);
    }

private:
    std::vector<float> buffer_;
    const size_t capacity_;
    alignas(64) std::atomic<size_t> writeIndex_{0};
    alignas(64) std::atomic<size_t> readIndex_{0};
};
