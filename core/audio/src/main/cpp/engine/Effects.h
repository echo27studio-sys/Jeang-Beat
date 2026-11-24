#pragma once

#include <cstdint>
#include <memory>
#include <vector>

namespace jb {

// Effect type ids and parameter slots. Must match EffectType in core/model (Mixer.kt):
// same order, same parameter order.
enum class EffectType : int32_t {
    Eq = 0,
    Compressor = 1,
    Limiter = 2,
    Reverb = 3,
    Delay = 4,
    Saturator = 5,
    Filter = 6,
    Count
};

constexpr int kMaxEffectParams = 12;
// Effects allocate their buffers for this rate up front, so a device change
// (e.g. 48 kHz -> 44.1 kHz) never allocates on the audio thread.
constexpr float kMaxEffectSampleRate = 96000.f;

struct EffectContext {
    float sampleRate = 48000.f;
    float tempo = 120.f;
};

// A mixer effect. Created and destroyed on the control thread; process() runs
// on the audio thread and must not allocate, lock or block.
class Effect {
public:
    virtual ~Effect() = default;
    // Processes [n] frames in place. [scL]/[scR] are the sidechain key signal
    // (null when the effect has no sidechain input).
    virtual void process(float* l, float* r, int n, const float* params, const float* scL, const float* scR,
                         const EffectContext& ctx) = 0;
    virtual void reset() = 0;
};

std::unique_ptr<Effect> createEffect(EffectType type);

}  // namespace jb
