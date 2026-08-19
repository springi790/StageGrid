#include "WavReader.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <sys/types.h>

WavReader::WavReader(const std::string &path) : path_(path) {
    file_ = std::fopen(path.c_str(), "rb");
    if (!file_) {
        error_ = "Could not open WAV";
        return;
    }
    valid_ = parse();
}

WavReader::~WavReader() {
    if (file_) std::fclose(file_);
}

uint16_t WavReader::u16le(const uint8_t *p) noexcept {
    return static_cast<uint16_t>(p[0] | (p[1] << 8));
}
uint32_t WavReader::u32le(const uint8_t *p) noexcept {
    return static_cast<uint32_t>(p[0]) |
           (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) |
           (static_cast<uint32_t>(p[3]) << 24);
}

bool WavReader::parse() {
    uint8_t header[12];
    if (std::fread(header, 1, sizeof(header), file_) != sizeof(header) ||
        std::memcmp(header, "RIFF", 4) != 0 || std::memcmp(header + 8, "WAVE", 4) != 0) {
        error_ = "Not a RIFF/WAVE file";
        return false;
    }

    bool gotFmt = false;
    bool gotData = false;
    while (!gotData) {
        uint8_t chunkHeader[8];
        if (std::fread(chunkHeader, 1, 8, file_) != 8) break;
        const uint32_t chunkSize = u32le(chunkHeader + 4);
        const off_t chunkDataPos = ::ftello(file_);
        if (chunkDataPos < 0) {
            error_ = "Could not read WAV file position";
            return false;
        }
        if (std::memcmp(chunkHeader, "fmt ", 4) == 0) {
            if (chunkSize < 16 || chunkSize > 4096) {
                error_ = "Invalid fmt chunk";
                return false;
            }
            std::vector<uint8_t> fmt(chunkSize);
            if (std::fread(fmt.data(), 1, chunkSize, file_) != chunkSize) {
                error_ = "Truncated fmt chunk";
                return false;
            }
            formatCode_ = u16le(fmt.data());
            channels_ = u16le(fmt.data() + 2);
            sampleRate_ = static_cast<int>(u32le(fmt.data() + 4));
            blockAlign_ = u16le(fmt.data() + 12);
            bitsPerSample_ = u16le(fmt.data() + 14);
            if (formatCode_ == 0xFFFE && chunkSize >= 40) {
                formatCode_ = u16le(fmt.data() + 24); // SubFormat GUID starts with canonical format tag.
            }
            gotFmt = true;
        } else if (std::memcmp(chunkHeader, "data", 4) == 0) {
            const off_t offset = ::ftello(file_);
            if (offset < 0) {
                error_ = "Could not read WAV data position";
                return false;
            }
            dataOffset_ = static_cast<int64_t>(offset);
            dataBytes_ = chunkSize;
            gotData = true;
        }
        if (!gotData) {
            const off_t next = chunkDataPos + static_cast<off_t>(chunkSize + (chunkSize & 1u));
            if (::fseeko(file_, next, SEEK_SET) != 0) break;
        }
    }

    if (!gotFmt || !gotData) {
        error_ = "Missing fmt or data chunk";
        return false;
    }
    if (formatCode_ != 1 && formatCode_ != 3) {
        error_ = "Only PCM and IEEE-float WAV are supported";
        return false;
    }
    if (channels_ < 1 || channels_ > 8 || sampleRate_ < 8000 || sampleRate_ > 384000) {
        error_ = "Unsupported channel count or sample rate";
        return false;
    }
    if (bitsPerSample_ != 8 && bitsPerSample_ != 16 && bitsPerSample_ != 24 && bitsPerSample_ != 32) {
        error_ = "Unsupported bit depth";
        return false;
    }
    if (formatCode_ == 3 && bitsPerSample_ != 32) {
        error_ = "IEEE-float WAV must be 32-bit";
        return false;
    }
    const int expectedAlign = channels_ * ((bitsPerSample_ + 7) / 8);
    if (blockAlign_ <= 0 || blockAlign_ < expectedAlign) {
        error_ = "Invalid block alignment";
        return false;
    }
    frameCount_ = dataBytes_ / blockAlign_;
    cache_.resize(static_cast<size_t>(kCacheFrames * channels_));
    raw_.resize(static_cast<size_t>(kCacheFrames * blockAlign_));
    return frameCount_ > 0;
}

float WavReader::decodeSample(const uint8_t *p) const noexcept {
    if (formatCode_ == 3) {
        float value;
        std::memcpy(&value, p, sizeof(float));
        return std::isfinite(value) ? std::clamp(value, -1.0f, 1.0f) : 0.0f;
    }
    switch (bitsPerSample_) {
        case 8:
            return (static_cast<int>(*p) - 128) / 128.0f;
        case 16: {
            const int16_t v = static_cast<int16_t>(u16le(p));
            return static_cast<float>(v) / 32768.0f;
        }
        case 24: {
            int32_t v = static_cast<int32_t>(p[0]) |
                        (static_cast<int32_t>(p[1]) << 8) |
                        (static_cast<int32_t>(p[2]) << 16);
            if (v & 0x00800000) v |= static_cast<int32_t>(0xFF000000);
            return static_cast<float>(v) / 8388608.0f;
        }
        case 32: {
            const int32_t v = static_cast<int32_t>(u32le(p));
            return static_cast<float>(static_cast<double>(v) / 2147483648.0);
        }
        default:
            return 0.0f;
    }
}

bool WavReader::loadCache(int64_t startFrame) {
    if (startFrame < 0 || startFrame >= frameCount_) return false;
    const int64_t frames = std::min<int64_t>(kCacheFrames, frameCount_ - startFrame);
    const int64_t byteOffset = dataOffset_ + startFrame * blockAlign_;
    if (::fseeko(file_, static_cast<off_t>(byteOffset), SEEK_SET) != 0) return false;
    const size_t bytes = static_cast<size_t>(frames * blockAlign_);
    if (std::fread(raw_.data(), 1, bytes, file_) != bytes) return false;

    const int bytesPerSample = (bitsPerSample_ + 7) / 8;
    for (int64_t f = 0; f < frames; ++f) {
        const uint8_t *frame = raw_.data() + f * blockAlign_;
        for (int ch = 0; ch < channels_; ++ch) {
            cache_[static_cast<size_t>(f * channels_ + ch)] = decodeSample(frame + ch * bytesPerSample);
        }
    }
    cacheStartFrame_ = startFrame;
    cacheFrames_ = frames;
    return true;
}

bool WavReader::sampleAt(double sourceFrame, float &left, float &right) {
    if (!valid_ || sourceFrame < 0.0 || sourceFrame >= static_cast<double>(frameCount_)) {
        left = right = 0.0f;
        return false;
    }
    const auto i0 = static_cast<int64_t>(sourceFrame);
    const auto i1 = std::min<int64_t>(i0 + 1, frameCount_ - 1);
    const float frac = static_cast<float>(sourceFrame - static_cast<double>(i0));

    auto fetch = [this](int64_t frame, int channel) -> float {
        if (cacheStartFrame_ < 0 || frame < cacheStartFrame_ || frame >= cacheStartFrame_ + cacheFrames_) {
            const int64_t aligned = (frame / kCacheFrames) * kCacheFrames;
            if (!loadCache(aligned)) return 0.0f;
        }
        const int64_t local = frame - cacheStartFrame_;
        const int ch = std::min(channel, channels_ - 1);
        return cache_[static_cast<size_t>(local * channels_ + ch)];
    };

    const float l0 = fetch(i0, 0);
    const float l1 = fetch(i1, 0);
    if (channels_ == 1) {
        left = l0 + (l1 - l0) * frac;
        right = left;
    } else {
        const float r0 = fetch(i0, 1);
        const float r1 = fetch(i1, 1);
        left = l0 + (l1 - l0) * frac;
        right = r0 + (r1 - r0) * frac;
    }
    return true;
}
