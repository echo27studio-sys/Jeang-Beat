#pragma once

#include <cstdint>
#include <memory>
#include <vector>

#include "Constants.h"
#include "DrumKit.h"
#include "Effects.h"

namespace jb {

enum NoteFlags : int32_t {
    kNoteSlide = 1,
};

struct NoteEvent {
    int32_t startTick;
    int32_t lengthTicks;
    int32_t pitch;
    int32_t velocity;  // 1..127
    float pan;         // -1..1
    float fineCents;   // -100..100
    float release;     // 0..1, 0.5 = instrument default
    int32_t flags;
};

struct ChannelData {
    InstrumentType type = InstrumentType::Drum;
    int32_t sound = 0;  // drum sound id (Drum type)
    std::shared_ptr<const SampleBuffer> sample;  // Sampler type
    float params[kMaxParams] = {};
    float volume = 0.8f;  // base values (automation overrides the live ones)
    float pan = 0.f;
    float liveVolume = 0.8f;
    float livePan = 0.f;
    float gainL = 0.f;
    float gainR = 0.f;
    bool muted = false;
    bool solo = false;
    bool audible = true;  // false when muted, or another channel is soloed
    int32_t insert = 0;   // mixer insert this channel plays through
};

struct PatternData {
    int32_t lengthTicks = 16 * kTicksPerStep;
    int32_t stepTicks = kTicksPerStep;
    std::vector<std::vector<NoteEvent>> notes;  // [channel index] -> notes sorted by start
};

enum class ClipType : int32_t { Pattern = 0, Audio = 1 };

struct ClipData {
    ClipType type = ClipType::Pattern;
    int64_t start = 0;
    int64_t length = 0;
    int64_t offset = 0;
    int32_t pattern = -1;                       // Pattern clips
    std::shared_ptr<const SampleBuffer> sample;  // Audio clips
    float gain = 1.f;
    int32_t insert = 0;
};

enum class AutoTarget : int32_t { MixerVolume = 0, MixerPan = 1, ChannelVolume = 2, ChannelPan = 3, EffectParam = 4 };

struct AutomationData {
    AutoTarget target = AutoTarget::MixerVolume;
    int32_t a = 0, b = 0, c = 0;  // insert/channel, slot position, param
    float min = 0.f, max = 1.f;   // EffectParam range
    bool log = false;
    int64_t start = 0, length = 0;
    std::vector<int32_t> ticks;   // relative to start
    std::vector<float> values;    // 0..1
};

struct EffectData {
    std::shared_ptr<Effect> fx;
    EffectType type = EffectType::Eq;
    bool enabled = true;
    float mix = 1.f;
    float params[kMaxEffectParams] = {};
    float live[kMaxEffectParams] = {};  // params after automation
    int32_t sidechain = -1;             // compressor key insert
};

struct SendData {
    int32_t target;
    float level;
};

struct InsertData {
    float volume = 0.8f, pan = 0.f;     // base (fader position, pan)
    float liveVolume = 0.8f, livePan = 0.f;
    bool muted = false, solo = false, toMaster = true;
    bool audible = true;
    std::vector<SendData> sends;
    EffectData slots[kMaxEffectSlots];
    int32_t slotCount = 0;
};

enum class PlayMode : int32_t { Pattern = 0, Song = 1 };

// Immutable playback state built on the control thread and handed to the audio
// thread as a whole. The audio thread never allocates or frees a snapshot; it
// only writes the `live*` automation fields of the snapshot it currently owns.
struct Snapshot {
    PlayMode mode = PlayMode::Pattern;
    int32_t currentPattern = 0;
    int64_t songLength = 16 * kTicksPerStep;
    int64_t loopStart = -1, loopEnd = -1;
    std::vector<ChannelData> channels;
    std::vector<PatternData> patterns;
    std::vector<ClipData> clips;
    std::vector<AutomationData> automation;
    InsertData inserts[kMaxInserts];
    int32_t order[kMaxInserts] = {};  // processing order, Master last
    int32_t orderCount = 0;

    // Pattern-mode helpers for older callers / tests
    int32_t lengthTicks() const {
        return patterns.empty() ? 16 * kTicksPerStep : patterns[static_cast<size_t>(currentPattern)].lengthTicks;
    }
};

}  // namespace jb
