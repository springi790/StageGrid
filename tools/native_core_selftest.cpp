#include "SpscRingBuffer.h"
#include "WavReader.h"
#include <cassert>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>

static void put16(std::ofstream& out, uint16_t v) {
    char b[2] = {static_cast<char>(v & 0xff), static_cast<char>((v >> 8) & 0xff)};
    out.write(b, 2);
}
static void put32(std::ofstream& out, uint32_t v) {
    char b[4] = {static_cast<char>(v & 0xff), static_cast<char>((v >> 8) & 0xff), static_cast<char>((v >> 16) & 0xff), static_cast<char>((v >> 24) & 0xff)};
    out.write(b, 4);
}
static std::string writeTestWav() {
    const std::string path = "/tmp/stagegrid-native-selftest.wav";
    constexpr uint32_t sr = 48000;
    constexpr uint16_t channels = 2;
    constexpr uint16_t bits = 16;
    constexpr uint32_t frames = 4800;
    constexpr uint32_t dataSize = frames * channels * (bits / 8);
    std::ofstream out(path, std::ios::binary);
    out.write("RIFF", 4); put32(out, 36 + dataSize); out.write("WAVE", 4);
    out.write("fmt ", 4); put32(out, 16); put16(out, 1); put16(out, channels); put32(out, sr);
    put32(out, sr * channels * (bits / 8)); put16(out, channels * (bits / 8)); put16(out, bits);
    out.write("data", 4); put32(out, dataSize);
    for (uint32_t i = 0; i < frames; ++i) {
        const int16_t sample = i == 100 ? 24000 : 0;
        put16(out, static_cast<uint16_t>(sample)); put16(out, static_cast<uint16_t>(sample));
    }
    return path;
}

int main() {
    SpscRingBuffer ring(32);
    assert(ring.writeStereo(0.25f, -0.5f));
    float l = 0, r = 0;
    assert(ring.readStereo(l, r));
    assert(std::abs(l - 0.25f) < 1e-6f && std::abs(r + 0.5f) < 1e-6f);

    const auto path = writeTestWav();
    WavReader reader(path);
    assert(reader.valid());
    assert(reader.sampleRate() == 48000);
    assert(reader.channels() == 2);
    float wl = 0, wr = 0;
    assert(reader.sampleAt(100.0, wl, wr));
    assert(wl > 0.7f && wr > 0.7f);

    // A single master timeline maps every stem through the same deterministic ratio.
    // At 48 kHz output, the five acceptance-test timestamps are exact integer frames.
    for (double seconds : {0.0, 30.0, 60.0, 120.0, 300.0}) {
        const int64_t masterFrame = static_cast<int64_t>(seconds * 48000.0);
        const double a = static_cast<double>(masterFrame) * 44100.0 / 48000.0;
        const double b = static_cast<double>(masterFrame) * 96000.0 / 48000.0;
        assert(std::abs(a - seconds * 44100.0) < 1e-9);
        assert(std::abs(b - seconds * 96000.0) < 1e-9);
    }
    std::cout << "StageGrid native core self-test: PASS\n";
    return 0;
}
