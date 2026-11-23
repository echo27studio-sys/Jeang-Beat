#include "DrumKit.h"

#include <algorithm>
#include <cmath>

#include "Constants.h"
#include "Dsp.h"

namespace jb {
namespace {

constexpr float kSr = static_cast<float>(kKitSampleRate);

int frames(float seconds) { return static_cast<int>(seconds * kSr); }

void normalize(std::vector<float>& d, float target = 0.9f) {
    float peak = 0.f;
    for (float s : d) peak = std::max(peak, std::fabs(s));
    if (peak <= 0.f) return;
    const float g = target / peak;
    for (float& s : d) s *= g;
}

// Short linear fade at the very end so no buffer ends with a click.
void fadeTail(std::vector<float>& d, float seconds = 0.005f) {
    const int n = std::min(static_cast<int>(d.size()), frames(seconds));
    for (int i = 0; i < n; ++i) {
        d[d.size() - 1 - i] *= static_cast<float>(i) / static_cast<float>(n);
    }
}

// Six detuned square waves, the classic analogue cymbal/hat source.
float metallic(float t, const float* freqs, int count) {
    float s = 0.f;
    for (int i = 0; i < count; ++i) {
        s += std::fmod(t * freqs[i], 1.f) < 0.5f ? 1.f : -1.f;
    }
    return s / static_cast<float>(count);
}

constexpr float kMetalFreqs[6] = {205.3f, 304.4f, 369.6f, 522.7f, 540.0f, 800.0f};

std::vector<float> kick() {
    std::vector<float> d(frames(0.8f));
    double phase = 0.0;
    Noise noise(1);
    for (size_t i = 0; i < d.size(); ++i) {
        const float t = i / kSr;
        const float f = 45.f + 110.f * std::exp(-t / 0.04f);
        phase += f / kSr;
        const float body = std::sin(2.0 * M_PI * phase) * std::exp(-t / 0.35f);
        const float click = noise.next() * std::exp(-t / 0.003f) * 0.4f;
        d[i] = body + click;
    }
    return d;
}

std::vector<float> snare() {
    std::vector<float> d(frames(0.4f));
    Noise noise(2);
    Biquad hp;
    hp.setHighpass(1200.f, 0.7f, kSr);
    for (size_t i = 0; i < d.size(); ++i) {
        const float t = i / kSr;
        const float tone = (std::sin(2.0 * M_PI * 185.0 * t) + 0.6f * std::sin(2.0 * M_PI * 330.0 * t)) *
                           std::exp(-t / 0.06f);
        const float n = hp.process(noise.next()) * std::exp(-t / 0.15f);
        d[i] = 0.5f * tone + n;
    }
    return d;
}

std::vector<float> clap() {
    std::vector<float> d(frames(0.5f));
    Noise noise(3);
    Biquad bp;
    bp.setBandpass(1200.f, 1.2f, kSr);
    for (size_t i = 0; i < d.size(); ++i) {
        const float t = i / kSr;
        float env = 0.f;
        // Three quick bursts, then a longer tail.
        for (int b = 0; b < 3; ++b) {
            const float tb = t - 0.011f * b;
            if (tb >= 0.f) env = std::max(env, std::exp(-tb / 0.006f));
        }
        const float tail = t >= 0.022f ? std::exp(-(t - 0.022f) / 0.12f) * 0.7f : 0.f;
        d[i] = bp.process(noise.next()) * std::max(env, tail);
    }
    return d;
}

std::vector<float> hat(float seconds, float decay, uint32_t seed) {
    std::vector<float> d(frames(seconds));
    Noise noise(seed);
    Biquad hp;
    hp.setHighpass(7000.f, 0.7f, kSr);
    for (size_t i = 0; i < d.size(); ++i) {
        const float t = i / kSr;
        const float src = 0.7f * metallic(t, kMetalFreqs, 6) + 0.3f * noise.next();
        d[i] = hp.process(src) * std::exp(-t / decay);
    }
    return d;
}

std::vector<float> rim() {
    std::vector<float> d(frames(0.15f));
    for (size_t i = 0; i < d.size(); ++i) {
        const float t = i / kSr;
        d[i] = (std::sin(2.0 * M_PI * 1700.0 * t) + 0.8f * std::sin(2.0 * M_PI * 500.0 * t)) *
               std::exp(-t / 0.018f);
    }
    return d;
}

std::vector<float> tom() {
    std::vector<float> d(frames(0.6f));
    double phase = 0.0;
    for (size_t i = 0; i < d.size(); ++i) {
        const float t = i / kSr;
        phase += (90.f + 40.f * std::exp(-t / 0.08f)) / kSr;
        d[i] = std::sin(2.0 * M_PI * phase) * std::exp(-t / 0.28f);
    }
    return d;
}

std::vector<float> crash() {
    std::vector<float> d(frames(2.0f));
    Noise noise(5);
    Biquad hp;
    hp.setHighpass(4500.f, 0.7f, kSr);
    for (size_t i = 0; i < d.size(); ++i) {
        const float t = i / kSr;
        const float src = 0.5f * metallic(t * 1.3f, kMetalFreqs, 6) + 0.5f * noise.next();
        d[i] = hp.process(src) * std::exp(-t / 0.7f);
    }
    return d;
}

std::vector<float> perc() {
    // Cowbell-style: two square tones through a band-pass.
    std::vector<float> d(frames(0.5f));
    Biquad bp;
    bp.setBandpass(800.f, 1.5f, kSr);
    for (size_t i = 0; i < d.size(); ++i) {
        const float t = i / kSr;
        const float src = (std::fmod(t * 540.f, 1.f) < 0.5f ? 1.f : -1.f) +
                          (std::fmod(t * 800.f, 1.f) < 0.5f ? 1.f : -1.f);
        const float env = 0.6f * std::exp(-t / 0.02f) + 0.4f * std::exp(-t / 0.15f);
        d[i] = bp.process(src * 0.5f) * env;
    }
    return d;
}

std::vector<float> shaker() {
    std::vector<float> d(frames(0.25f));
    Noise noise(6);
    Biquad hp;
    hp.setHighpass(5000.f, 0.7f, kSr);
    for (size_t i = 0; i < d.size(); ++i) {
        const float t = i / kSr;
        const float env = std::min(1.f, t / 0.012f) * std::exp(-t / 0.06f);
        d[i] = hp.process(noise.next()) * env;
    }
    return d;
}

// Placeholder for Khmer chhing (pair of small cymbals): bright inharmonic
// partials with a long ring. To be replaced by real recordings.
std::vector<float> chhing() {
    std::vector<float> d(frames(1.5f));
    constexpr float partials[5] = {2890.f, 4310.f, 5620.f, 6980.f, 7910.f};
    constexpr float levels[5] = {1.f, 0.7f, 0.55f, 0.35f, 0.25f};
    Noise noise(7);
    for (size_t i = 0; i < d.size(); ++i) {
        const float t = i / kSr;
        float s = 0.f;
        for (int p = 0; p < 5; ++p) {
            s += levels[p] * std::sin(2.0 * M_PI * partials[p] * t) * std::exp(-t / (0.9f - 0.12f * p));
        }
        s += noise.next() * 0.3f * std::exp(-t / 0.01f);
        d[i] = s;
    }
    return d;
}

// Placeholder for Khmer skor (barrel drum): low membrane with a second mode and
// a hand-slap transient. To be replaced by real recordings.
std::vector<float> skor() {
    std::vector<float> d(frames(0.8f));
    double p1 = 0.0, p2 = 0.0;
    Noise noise(8);
    Biquad bp;
    bp.setBandpass(900.f, 0.8f, kSr);
    for (size_t i = 0; i < d.size(); ++i) {
        const float t = i / kSr;
        const float f = 95.f + 25.f * std::exp(-t / 0.05f);
        p1 += f / kSr;
        p2 += (f * 1.59f) / kSr;
        const float body = std::sin(2.0 * M_PI * p1) * std::exp(-t / 0.4f) +
                           0.35f * std::sin(2.0 * M_PI * p2) * std::exp(-t / 0.15f);
        const float slap = bp.process(noise.next()) * std::exp(-t / 0.015f) * 0.8f;
        d[i] = body + slap;
    }
    return d;
}

}  // namespace

DrumKit::DrumKit() {
    sounds_.resize(static_cast<size_t>(DrumSoundId::Count));
    auto set = [this](DrumSoundId id, std::vector<float> d) {
        normalize(d);
        fadeTail(d);
        sounds_[static_cast<size_t>(id)].data = std::move(d);
    };
    set(DrumSoundId::Kick, kick());
    set(DrumSoundId::Snare, snare());
    set(DrumSoundId::Clap, clap());
    set(DrumSoundId::HatClosed, hat(0.25f, 0.045f, 4));
    set(DrumSoundId::HatOpen, hat(0.9f, 0.35f, 9));
    set(DrumSoundId::Rim, rim());
    set(DrumSoundId::Tom, tom());
    set(DrumSoundId::Crash, crash());
    set(DrumSoundId::Perc, perc());
    set(DrumSoundId::Shaker, shaker());
    set(DrumSoundId::Chhing, chhing());
    set(DrumSoundId::Skor, skor());
}

const SampleBuffer* DrumKit::get(int32_t id) const {
    if (id < 0 || id >= static_cast<int32_t>(sounds_.size())) return nullptr;
    return &sounds_[static_cast<size_t>(id)];
}

}  // namespace jb
