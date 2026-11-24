#pragma once

#include <cstdint>

#include "Constants.h"
#include "DrumKit.h"
#include "Snapshot.h"

namespace jb {

// One sounding note. A single struct covers every instrument type so the voice
// pool is a flat, pre-allocated array (no allocation on the audio thread).
struct Voice {
    bool active = false;
    InstrumentType type = InstrumentType::Drum;
    int32_t channel = -1;
    int32_t pitch = 0;
    float velocity = 1.f;         // linear gain from MIDI velocity
    float panL = 1.f, panR = 1.f;  // per-note pan gains
    float releaseScale = 1.f;      // per-note release multiplier
    bool live = false;             // started by a live note-on, ended by note-off
    int64_t framesUntilOff = -1;   // sequenced note length countdown, -1 = none
    bool releasing = false;
    uint64_t order = 0;  // start order, used for voice stealing
    float time = 0.f;    // seconds since the note started

    // Fast fade used for choke groups and voice stealing.
    float fade = 1.f;
    float fadeStep = 0.f;

    // Sample playback (drums, sampler)
    const SampleBuffer* buffer = nullptr;
    double position = 0.0;
    double rate = 1.0;
    int32_t chokeGroup = 0;

    // Oscillator instruments (808, synth)
    double phase1 = 0.0;
    double phase2 = 0.0;
    float freq = 0.f;
    float targetFreq = 0.f;
    float slideSeconds = 0.f;  // >0 while gliding towards targetFreq because of a slide note
    float env = 0.f;
    int32_t envStage = 0;  // synth: 0 attack, 1 decay/sustain, 2 release
    float filterIc1 = 0.f;
    float filterIc2 = 0.f;

    // Renders one stereo frame (before channel gain). Sets active=false when done.
    void render(const ChannelData& ch, float sampleRate, float& outL, float& outR);

    void startFade(float seconds, float sampleRate);
    void release();

private:
    void renderSample(const ChannelData& ch, float& outL, float& outR);
    float render808(const ChannelData& ch, float sampleRate);
    float renderSynth(const ChannelData& ch, float sampleRate);
};

}  // namespace jb
