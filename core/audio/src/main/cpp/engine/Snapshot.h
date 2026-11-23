#pragma once

#include <cstdint>
#include <vector>

#include "Constants.h"

namespace jb {

struct NoteEvent {
    int32_t startTick;
    int32_t lengthTicks;
    int32_t pitch;
    int32_t velocity;  // 1..127
};

struct ChannelData {
    InstrumentType type = InstrumentType::Drum;
    int32_t sound = 0;  // drum sound id (Drum type only)
    float params[kMaxParams] = {};
    float gainL = 0.f;
    float gainR = 0.f;
    bool muted = false;
    bool solo = false;
    bool audible = true;  // false when muted, or another channel is soloed
    std::vector<NoteEvent> notes;
};

// Immutable playback state built on the control thread and handed to the audio
// thread as a whole. The audio thread never allocates or frees a snapshot.
struct Snapshot {
    int32_t lengthTicks = 16 * kTicksPerStep;
    std::vector<ChannelData> channels;
};

}  // namespace jb
