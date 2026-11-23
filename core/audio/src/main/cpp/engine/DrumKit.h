#pragma once

#include <cstdint>
#include <vector>

namespace jb {

// Ids must match DrumSound enum order in core/model (DrumSound.kt).
enum class DrumSoundId : int32_t {
    Kick = 0,
    Snare,
    Clap,
    HatClosed,
    HatOpen,
    Rim,
    Tom,
    Crash,
    Perc,
    Shaker,
    Chhing,  // Khmer small hand cymbals (synthesised placeholder)
    Skor,    // Khmer barrel drum (synthesised placeholder)
    Count
};

struct SampleBuffer {
    std::vector<float> data;  // mono, kKitSampleRate
};

// Built-in drum sounds, synthesised once at start-up so the app ships without
// any third-party sample files. Read-only after construction.
class DrumKit {
public:
    DrumKit();
    const SampleBuffer* get(int32_t id) const;

private:
    std::vector<SampleBuffer> sounds_;
};

}  // namespace jb
