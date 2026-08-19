#pragma once
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

class WavReader {
public:
    explicit WavReader(const std::string &path);
    ~WavReader();
    WavReader(const WavReader &) = delete;
    WavReader &operator=(const WavReader &) = delete;

    bool valid() const noexcept { return valid_; }
    const std::string &error() const noexcept { return error_; }
    int sampleRate() const noexcept { return sampleRate_; }
    int channels() const noexcept { return channels_; }
    int bitsPerSample() const noexcept { return bitsPerSample_; }
    int64_t frameCount() const noexcept { return frameCount_; }
    double durationSeconds() const noexcept {
        return sampleRate_ > 0 ? static_cast<double>(frameCount_) / sampleRate_ : 0.0;
    }

    bool sampleAt(double sourceFrame, float &left, float &right);

private:
    bool parse();
    bool loadCache(int64_t startFrame);
    float decodeSample(const uint8_t *p) const noexcept;
    static uint16_t u16le(const uint8_t *p) noexcept;
    static uint32_t u32le(const uint8_t *p) noexcept;

    std::string path_;
    FILE *file_{nullptr};
    bool valid_{false};
    std::string error_;
    uint16_t formatCode_{0};
    int channels_{0};
    int sampleRate_{0};
    int bitsPerSample_{0};
    int blockAlign_{0};
    int64_t dataOffset_{0};
    int64_t dataBytes_{0};
    int64_t frameCount_{0};

    static constexpr int64_t kCacheFrames = 4096;
    int64_t cacheStartFrame_{-1};
    int64_t cacheFrames_{0};
    std::vector<float> cache_;
    std::vector<uint8_t> raw_;
};
