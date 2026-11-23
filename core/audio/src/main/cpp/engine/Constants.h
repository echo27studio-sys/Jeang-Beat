#pragma once

#include <cstdint>

namespace jb {

// Timing: one quarter note (beat) = 96 ticks, one 16th-note step = 24 ticks.
// These values must match com.jeangbeat.core.model.Timing on the Kotlin side.
constexpr int32_t kTicksPerBeat = 96;
constexpr int32_t kTicksPerStep = 24;

// Built-in drum sounds are synthesised at this rate and resampled on playback.
constexpr int32_t kKitSampleRate = 48000;

constexpr int32_t kMaxVoices = 48;
constexpr int32_t kMaxParams = 16;
constexpr int32_t kMaxHeldPads = 16;
constexpr int32_t kSubBlockFrames = 64;
constexpr int32_t kMaxEventsPerSubBlock = 256;

// Instrument types. Must match InstrumentType ordinal mapping in AudioEngine.kt.
enum class InstrumentType : int32_t {
    Drum = 0,
    Bass808 = 1,
    Synth = 2,
};

// Parameter slots per instrument type. Must match ParamSlots in AudioEngine.kt.
namespace drum_param {
constexpr int kTune = 0;        // semitones, -24..24
constexpr int kDecay = 1;       // 0.05..1 (1 = natural length)
constexpr int kChokeGroup = 2;  // 0 = none, otherwise group id
}  // namespace drum_param

namespace bass_param {
constexpr int kDecay = 0;  // seconds, 0.1..4
constexpr int kGlide = 1;  // seconds, 0..0.5
constexpr int kDrive = 2;  // 0..1
constexpr int kPunch = 3;  // 0..1 pitch-envelope amount
constexpr int kTune = 4;   // semitones
}  // namespace bass_param

namespace synth_param {
constexpr int kWaveform = 0;   // 0 saw, 1 square, 2 triangle, 3 sine
constexpr int kCutoff = 1;     // 0..1
constexpr int kResonance = 2;  // 0..1
constexpr int kAttack = 3;     // seconds
constexpr int kDecay = 4;      // seconds
constexpr int kSustain = 5;    // 0..1
constexpr int kRelease = 6;    // seconds
constexpr int kDetune = 7;     // cents for oscillator 2
constexpr int kEnvAmount = 8;  // 0..1 filter envelope amount
}  // namespace synth_param

}  // namespace jb
