#pragma once

#include <cstdint>
#include <string>
#include <vector>

struct OfflineMixTrackConfig {
    std::string path;
    int type{0};
    float volume{1.0f};
    bool muted{false};
    bool solo{false};
    float pan{0.0f};
    int route{0};
};

struct OfflineMixRequest {
    std::string outputPath;
    std::vector<OfflineMixTrackConfig> tracks;
    double bpm{0.0};
    int beatsPerBar{4};
    int64_t gridOffsetMs{0};
    float masterVolume{1.0f};
    float tempoRatio{1.0f};
    float pitchSemitones{0.0f};
    bool clickEnabled{false};
    bool guideEnabled{false};
    int clickSubdivision{1};
    int clickRoute{0};
};

/**
 * Renders a stereo rehearsal fold-down without touching the live Oboe engine.
 * Returns true on success; error receives a human-readable reason on failure.
 */
bool renderOfflineMix(const OfflineMixRequest &request, std::string &error);
