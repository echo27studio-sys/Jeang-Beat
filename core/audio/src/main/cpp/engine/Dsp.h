#pragma once

#include <cmath>
#include <cstdint>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

namespace jb {

// Deterministic white noise in [-1, 1] (xorshift32). Real-time safe.
class Noise {
public:
    explicit Noise(uint32_t seed = 1) : state_(seed ? seed : 1) {}
    float next() {
        state_ ^= state_ << 13;
        state_ ^= state_ >> 17;
        state_ ^= state_ << 5;
        return static_cast<float>(state_) / 2147483648.f - 1.f;
    }

private:
    uint32_t state_;
};

// RBJ cookbook biquad, transposed direct form II.
class Biquad {
public:
    void setHighpass(float freq, float q, float sr) {
        const float w = 2.f * static_cast<float>(M_PI) * freq / sr;
        const float alpha = std::sin(w) / (2.f * q);
        const float c = std::cos(w);
        const float a0 = 1.f + alpha;
        b0_ = (1.f + c) / 2.f / a0;
        b1_ = -(1.f + c) / a0;
        b2_ = b0_;
        a1_ = -2.f * c / a0;
        a2_ = (1.f - alpha) / a0;
    }

    void setBandpass(float freq, float q, float sr) {
        const float w = 2.f * static_cast<float>(M_PI) * freq / sr;
        const float alpha = std::sin(w) / (2.f * q);
        const float c = std::cos(w);
        const float a0 = 1.f + alpha;
        b0_ = alpha / a0;
        b1_ = 0.f;
        b2_ = -alpha / a0;
        a1_ = -2.f * c / a0;
        a2_ = (1.f - alpha) / a0;
    }

    float process(float x) {
        const float y = b0_ * x + z1_;
        z1_ = b1_ * x - a1_ * y + z2_;
        z2_ = b2_ * x - a2_ * y;
        return y;
    }

private:
    float b0_ = 1.f, b1_ = 0.f, b2_ = 0.f, a1_ = 0.f, a2_ = 0.f;
    float z1_ = 0.f, z2_ = 0.f;
};

// PolyBLEP residual for band-limited saw and square oscillators.
inline float polyBlep(float t, float dt) {
    if (t < dt) {
        t /= dt;
        return t + t - t * t - 1.f;
    }
    if (t > 1.f - dt) {
        t = (t - 1.f) / dt;
        return t * t + t + t + 1.f;
    }
    return 0.f;
}

inline float midiToHz(float pitch) { return 440.f * std::pow(2.f, (pitch - 69.f) / 12.f); }

// One-pole coefficient so a value moves ~63% of the way in `seconds`.
inline float timeCoef(float seconds, float sr) {
    if (seconds <= 0.f) return 1.f;
    return 1.f - std::exp(-1.f / (seconds * sr));
}

}  // namespace jb
