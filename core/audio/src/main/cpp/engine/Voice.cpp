#include "Voice.h"

#include <algorithm>
#include <cmath>

#include "Dsp.h"

namespace jb {

namespace {
constexpr float kSilence = 1e-4f;
constexpr float kBassReleaseSeconds = 0.015f;  // time constant (~70 ms to -40 dB)
}  // namespace

void Voice::startFade(float seconds, float sampleRate) {
    const float step = 1.f / std::max(1.f, seconds * sampleRate);
    fadeStep = std::max(fadeStep, step);
}

void Voice::release() { releasing = true; }

float Voice::render(const ChannelData& ch, float sampleRate) {
    float out = 0.f;
    switch (type) {
        case InstrumentType::Drum:
            out = renderDrum(ch, sampleRate);
            break;
        case InstrumentType::Bass808:
            out = render808(ch, sampleRate);
            break;
        case InstrumentType::Synth:
            out = renderSynth(ch, sampleRate);
            break;
    }
    if (fadeStep > 0.f) {
        fade -= fadeStep;
        if (fade <= 0.f) {
            active = false;
            return 0.f;
        }
        out *= fade;
    }
    time += 1.f / sampleRate;
    return out;
}

float Voice::renderDrum(const ChannelData& ch, float sampleRate) {
    (void)sampleRate;
    if (buffer == nullptr) {
        active = false;
        return 0.f;
    }
    const auto size = static_cast<double>(buffer->data.size());
    if (position >= size - 1.0) {
        active = false;
        return 0.f;
    }
    const auto i = static_cast<size_t>(position);
    const auto frac = static_cast<float>(position - static_cast<double>(i));
    const float s = buffer->data[i] + (buffer->data[i + 1] - buffer->data[i]) * frac;
    position += rate;

    float amp = velocity;
    const float decay = ch.params[drum_param::kDecay];
    if (decay > 0.f && decay < 0.999f) {
        const float env = std::exp(-time / (decay * 0.6f));
        if (env < kSilence) {
            active = false;
            return 0.f;
        }
        amp *= env;
    }
    return s * amp;
}

float Voice::render808(const ChannelData& ch, float sampleRate) {
    const float glide = ch.params[bass_param::kGlide];
    if (freq != targetFreq) {
        freq += (targetFreq - freq) * timeCoef(std::max(glide, 0.001f) * 0.35f, sampleRate);
    }
    const float punch = ch.params[bass_param::kPunch];
    const float pitchEnv = 1.f + punch * 2.5f * std::exp(-time / 0.025f);
    phase1 += static_cast<double>(freq * pitchEnv / sampleRate);
    if (phase1 >= 1.0) phase1 -= std::floor(phase1);

    const float decay = std::max(0.05f, ch.params[bass_param::kDecay]);
    const float attack = std::min(1.f, time / 0.002f);
    const float body = std::exp(-time / decay);
    if (releasing) {
        env *= 1.f - timeCoef(kBassReleaseSeconds, sampleRate);
    }
    const float level = body * env;
    if (level < kSilence) {
        active = false;
        return 0.f;
    }

    float x = static_cast<float>(std::sin(2.0 * M_PI * phase1)) * attack * level;
    const float k = 1.f + ch.params[bass_param::kDrive] * 9.f;
    x = std::tanh(x * k) / std::tanh(k);
    return x * velocity;
}

float Voice::renderSynth(const ChannelData& ch, float sampleRate) {
    // Envelope
    const float attack = std::max(0.001f, ch.params[synth_param::kAttack]);
    const float decay = std::max(0.005f, ch.params[synth_param::kDecay]);
    const float sustain = std::clamp(ch.params[synth_param::kSustain], 0.f, 1.f);
    const float rel = std::max(0.005f, ch.params[synth_param::kRelease]);
    if (releasing && envStage != 2) envStage = 2;
    switch (envStage) {
        case 0:
            env += 1.f / (attack * sampleRate);
            if (env >= 1.f) {
                env = 1.f;
                envStage = 1;
            }
            break;
        case 1:
            env += (sustain - env) * timeCoef(decay * 0.35f, sampleRate);
            break;
        default:
            env -= env * timeCoef(rel * 0.35f, sampleRate);
            if (env < kSilence) {
                active = false;
                return 0.f;
            }
            break;
    }

    // Oscillators
    const int wave = static_cast<int>(ch.params[synth_param::kWaveform]);
    const float detune = std::pow(2.f, ch.params[synth_param::kDetune] / 1200.f);
    const float dt1 = freq / sampleRate;
    const float dt2 = freq * detune / sampleRate;
    auto osc = [wave](double& phase, float dt) {
        const auto p = static_cast<float>(phase);
        float v;
        switch (wave) {
            case 1:  // square
                v = (p < 0.5f ? 1.f : -1.f) + polyBlep(p, dt) - polyBlep(std::fmod(p + 0.5f, 1.f), dt);
                break;
            case 2:  // triangle
                v = 4.f * std::fabs(p - 0.5f) - 1.f;
                break;
            case 3:  // sine
                v = static_cast<float>(std::sin(2.0 * M_PI * phase));
                break;
            default:  // saw
                v = 2.f * p - 1.f - polyBlep(p, dt);
                break;
        }
        phase += dt;
        if (phase >= 1.0) phase -= 1.0;
        return v;
    };
    const float x = 0.5f * (osc(phase1, dt1) + osc(phase2, dt2));

    // State-variable low-pass filter (TPT form), cutoff follows the envelope.
    const float cutoff = std::clamp(ch.params[synth_param::kCutoff], 0.f, 1.f);
    const float envAmount = ch.params[synth_param::kEnvAmount];
    float fc = 30.f * std::pow(2.f, cutoff * 9.4f + envAmount * 4.f * env);
    fc = std::min(fc, sampleRate * 0.45f);
    const float g = std::tan(static_cast<float>(M_PI) * fc / sampleRate);
    const float k = 2.f - 1.94f * std::clamp(ch.params[synth_param::kResonance], 0.f, 1.f);
    const float a1 = 1.f / (1.f + g * (g + k));
    const float a2 = g * a1;
    const float a3 = g * a2;
    const float v3 = x - filterIc2;
    const float v1 = a1 * filterIc1 + a2 * v3;
    const float v2 = filterIc2 + a2 * filterIc1 + a3 * v3;
    filterIc1 = 2.f * v1 - filterIc1;
    filterIc2 = 2.f * v2 - filterIc2;

    return v2 * env * velocity * 0.6f;
}

}  // namespace jb
