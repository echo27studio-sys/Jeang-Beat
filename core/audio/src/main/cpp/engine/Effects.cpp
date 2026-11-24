#include "Effects.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>

#include "Dsp.h"

namespace jb {
namespace {

constexpr float kPi = static_cast<float>(M_PI);

inline float dbToGain(float db) { return std::pow(10.f, db / 20.f); }
inline float gainToDb(float g) { return 20.f * std::log10(std::max(g, 1e-9f)); }

// ---------------------------------------------------------------------------
// Biquad with RBJ designs (stereo-agnostic: one instance per channel).
// ---------------------------------------------------------------------------
struct Bq {
    float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0, z1 = 0, z2 = 0;

    void set(float nb0, float nb1, float nb2, float a0, float na1, float na2) {
        b0 = nb0 / a0; b1 = nb1 / a0; b2 = nb2 / a0; a1 = na1 / a0; a2 = na2 / a0;
    }
    void lowShelf(float f, float db, float sr) {
        const float A = std::pow(10.f, db / 40.f), w = 2 * kPi * f / sr, c = std::cos(w), s = std::sin(w);
        const float al = s / 2 * std::sqrt(2.f), sa = 2 * std::sqrt(A) * al;
        set(A * ((A + 1) - (A - 1) * c + sa), 2 * A * ((A - 1) - (A + 1) * c), A * ((A + 1) - (A - 1) * c - sa),
            (A + 1) + (A - 1) * c + sa, -2 * ((A - 1) + (A + 1) * c), (A + 1) + (A - 1) * c - sa);
    }
    void highShelf(float f, float db, float sr) {
        const float A = std::pow(10.f, db / 40.f), w = 2 * kPi * f / sr, c = std::cos(w), s = std::sin(w);
        const float al = s / 2 * std::sqrt(2.f), sa = 2 * std::sqrt(A) * al;
        set(A * ((A + 1) + (A - 1) * c + sa), -2 * A * ((A - 1) + (A + 1) * c), A * ((A + 1) + (A - 1) * c - sa),
            (A + 1) - (A - 1) * c + sa, 2 * ((A - 1) - (A + 1) * c), (A + 1) - (A - 1) * c - sa);
    }
    void peak(float f, float db, float q, float sr) {
        const float A = std::pow(10.f, db / 40.f), w = 2 * kPi * f / sr, c = std::cos(w), al = std::sin(w) / (2 * q);
        set(1 + al * A, -2 * c, 1 - al * A, 1 + al / A, -2 * c, 1 - al / A);
    }
    void highpass(float f, float q, float sr) {
        const float w = 2 * kPi * f / sr, c = std::cos(w), al = std::sin(w) / (2 * q);
        set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al);
    }
    void lowpass(float f, float q, float sr) {
        const float w = 2 * kPi * f / sr, c = std::cos(w), al = std::sin(w) / (2 * q);
        set((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al);
    }
    float run(float x) {
        const float y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return y;
    }
    void clear() { z1 = z2 = 0; }
};

inline float safeFreq(float f, float sr) { return std::clamp(f, 10.f, sr * 0.45f); }

bool paramsChanged(float* cache, const float* p, int count, float sr, float& cachedSr) {
    bool changed = sr != cachedSr;
    for (int i = 0; i < count && !changed; ++i) changed = cache[i] != p[i];
    if (changed) {
        std::memcpy(cache, p, sizeof(float) * count);
        cachedSr = sr;
    }
    return changed;
}

// ---------------------------------------------------------------------------
// Parametric EQ: low cut, low shelf, mid peak, high shelf.
// params: lowFreq, lowGain, midFreq, midGain, midQ, highFreq, highGain, lowCut
// ---------------------------------------------------------------------------
class EqEffect : public Effect {
public:
    void process(float* l, float* r, int n, const float* p, const float*, const float*, const EffectContext& ctx) override {
        const float sr = ctx.sampleRate;
        if (paramsChanged(cache_, p, 8, sr, sr_)) {
            for (int c = 0; c < 2; ++c) {
                cut_[c].highpass(safeFreq(p[7], sr), 0.707f, sr);
                low_[c].lowShelf(safeFreq(p[0], sr), p[1], sr);
                mid_[c].peak(safeFreq(p[2], sr), p[3], std::max(0.1f, p[4]), sr);
                high_[c].highShelf(safeFreq(p[5], sr), p[6], sr);
            }
        }
        const bool useCut = p[7] > 21.f;
        for (int i = 0; i < n; ++i) {
            float x = l[i], y = r[i];
            if (useCut) { x = cut_[0].run(x); y = cut_[1].run(y); }
            l[i] = high_[0].run(mid_[0].run(low_[0].run(x)));
            r[i] = high_[1].run(mid_[1].run(low_[1].run(y)));
        }
    }
    void reset() override {
        for (int c = 0; c < 2; ++c) { cut_[c].clear(); low_[c].clear(); mid_[c].clear(); high_[c].clear(); }
    }

private:
    Bq cut_[2], low_[2], mid_[2], high_[2];
    float cache_[8] = {-1};
    float sr_ = 0;
};

// ---------------------------------------------------------------------------
// Compressor (feed-forward, soft knee) with optional sidechain key.
// params: threshold dB, ratio, attack ms, release ms, makeup dB, sidechain source
// ---------------------------------------------------------------------------
class CompressorEffect : public Effect {
public:
    void process(float* l, float* r, int n, const float* p, const float* scL, const float* scR,
                 const EffectContext& ctx) override {
        const float sr = ctx.sampleRate;
        const float threshold = p[0], ratio = std::max(1.f, p[1]);
        const float att = timeCoef(std::max(0.05f, p[2]) / 1000.f, sr);
        const float rel = timeCoef(std::max(5.f, p[3]) / 1000.f, sr);
        const float makeup = dbToGain(p[4]);
        constexpr float knee = 6.f;
        for (int i = 0; i < n; ++i) {
            const float key = (scL != nullptr) ? std::max(std::fabs(scL[i]), std::fabs(scR[i]))
                                               : std::max(std::fabs(l[i]), std::fabs(r[i]));
            const float level = gainToDb(key);
            const float over = level - threshold;
            float reduction;  // dB of gain reduction wanted
            if (over <= -knee / 2) {
                reduction = 0.f;
            } else if (over >= knee / 2) {
                reduction = over * (1.f - 1.f / ratio);
            } else {
                const float x = over + knee / 2;
                reduction = (1.f - 1.f / ratio) * x * x / (2 * knee);
            }
            env_ += (reduction - env_) * (reduction > env_ ? att : rel);
            const float g = dbToGain(-env_) * makeup;
            l[i] *= g;
            r[i] *= g;
        }
        gainReductionDb = env_;
    }
    void reset() override { env_ = 0; }
    float gainReductionDb = 0.f;

private:
    float env_ = 0.f;
};

// ---------------------------------------------------------------------------
// Peak limiter: instant attack, smooth release, hard ceiling.
// params: gain dB, ceiling dB, release ms
// ---------------------------------------------------------------------------
class LimiterEffect : public Effect {
public:
    void process(float* l, float* r, int n, const float* p, const float*, const float*, const EffectContext& ctx) override {
        const float pre = dbToGain(p[0]);
        const float ceiling = dbToGain(p[1]);
        const float rel = timeCoef(std::max(1.f, p[2]) / 1000.f, ctx.sampleRate);
        for (int i = 0; i < n; ++i) {
            const float x = l[i] * pre, y = r[i] * pre;
            const float peak = std::max(std::fabs(x), std::fabs(y));
            const float target = peak > ceiling ? ceiling / peak : 1.f;
            gain_ = target < gain_ ? target : gain_ + (target - gain_) * rel;
            l[i] = std::clamp(x * gain_, -ceiling, ceiling);
            r[i] = std::clamp(y * gain_, -ceiling, ceiling);
        }
    }
    void reset() override { gain_ = 1.f; }

private:
    float gain_ = 1.f;
};

// ---------------------------------------------------------------------------
// Reverb: Freeverb (Schroeder/Moorer) with pre-delay and input low cut.
// Output is 100% wet; the slot's mix knob blends it with the dry signal.
// params: size, damping, width, pre-delay ms, low cut Hz
// ---------------------------------------------------------------------------
class ReverbEffect : public Effect {
    static constexpr int kCombs = 8, kAllpasses = 4, kSpread = 23;
    static constexpr int kCombTuning[kCombs] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
    static constexpr int kAllpassTuning[kAllpasses] = {556, 441, 341, 225};

    struct Line {
        std::vector<float> buf;
        int len = 1, pos = 0;
        float store = 0;
    };

public:
    ReverbEffect() {
        const float scale = kMaxEffectSampleRate / 44100.f;
        for (int c = 0; c < 2; ++c) {
            for (int i = 0; i < kCombs; ++i) comb_[c][i].buf.assign(static_cast<size_t>((kCombTuning[i] + kSpread) * scale) + 2, 0.f);
            for (int i = 0; i < kAllpasses; ++i) ap_[c][i].buf.assign(static_cast<size_t>((kAllpassTuning[i] + kSpread) * scale) + 2, 0.f);
        }
        pre_.assign(static_cast<size_t>(0.1f * kMaxEffectSampleRate) + 2, 0.f);
    }

    void process(float* l, float* r, int n, const float* p, const float*, const float*, const EffectContext& ctx) override {
        const float sr = ctx.sampleRate;
        if (sr != sr_) setRate(sr);
        if (p[4] != lowCut_) {
            lowCut_ = p[4];
            hp_[0].highpass(safeFreq(lowCut_, sr), 0.707f, sr);
            hp_[1].highpass(safeFreq(lowCut_, sr), 0.707f, sr);
        }
        const float feedback = 0.7f + 0.28f * std::clamp(p[0], 0.f, 1.f);
        const float damp = 0.4f * std::clamp(p[1], 0.f, 1.f);
        const float width = std::clamp(p[2], 0.f, 1.f);
        const float wet1 = width / 2 + 0.5f, wet2 = (1 - width) / 2;
        const int preDelay = std::clamp(static_cast<int>(p[3] / 1000.f * sr), 0, static_cast<int>(pre_.size()) - 1);
        constexpr float kInputGain = 0.015f;

        for (int i = 0; i < n; ++i) {
            // pre-delay (mono sum), then low cut
            pre_[prePos_] = (l[i] + r[i]) * kInputGain;
            int readPos = prePos_ - preDelay;
            if (readPos < 0) readPos += static_cast<int>(pre_.size());
            const float in = pre_[static_cast<size_t>(readPos)];
            if (++prePos_ >= static_cast<int>(pre_.size())) prePos_ = 0;

            float out[2] = {0, 0};
            for (int c = 0; c < 2; ++c) {
                const float x = hp_[c].run(in);
                for (auto& cb : comb_[c]) {
                    float& s = cb.buf[static_cast<size_t>(cb.pos)];
                    const float y = s;
                    cb.store = y * (1 - damp) + cb.store * damp;
                    s = x + cb.store * feedback;
                    if (++cb.pos >= cb.len) cb.pos = 0;
                    out[c] += y;
                }
                for (auto& a : ap_[c]) {
                    float& s = a.buf[static_cast<size_t>(a.pos)];
                    const float bufout = s;
                    const float y = -out[c] + bufout;
                    s = out[c] + bufout * 0.5f;
                    if (++a.pos >= a.len) a.pos = 0;
                    out[c] = y;
                }
            }
            l[i] = out[0] * wet1 + out[1] * wet2;
            r[i] = out[1] * wet1 + out[0] * wet2;
        }
    }

    void reset() override {
        for (int c = 0; c < 2; ++c) {
            for (auto& cb : comb_[c]) { std::fill(cb.buf.begin(), cb.buf.end(), 0.f); cb.store = 0; cb.pos = 0; }
            for (auto& a : ap_[c]) { std::fill(a.buf.begin(), a.buf.end(), 0.f); a.pos = 0; }
            hp_[c].clear();
        }
        std::fill(pre_.begin(), pre_.end(), 0.f);
    }

private:
    void setRate(float sr) {
        sr_ = sr;
        const float scale = sr / 44100.f;
        for (int c = 0; c < 2; ++c) {
            const int spread = c == 0 ? 0 : kSpread;
            for (int i = 0; i < kCombs; ++i) {
                auto& cb = comb_[c][i];
                cb.len = std::clamp(static_cast<int>((kCombTuning[i] + spread) * scale), 1, static_cast<int>(cb.buf.size()));
                cb.pos = 0;
            }
            for (int i = 0; i < kAllpasses; ++i) {
                auto& a = ap_[c][i];
                a.len = std::clamp(static_cast<int>((kAllpassTuning[i] + spread) * scale), 1, static_cast<int>(a.buf.size()));
                a.pos = 0;
            }
        }
        lowCut_ = -1;
    }

    std::array<std::array<Line, kCombs>, 2> comb_;
    std::array<std::array<Line, kAllpasses>, 2> ap_;
    std::vector<float> pre_;
    int prePos_ = 0;
    Bq hp_[2];
    float sr_ = 0, lowCut_ = -1;
};

// ---------------------------------------------------------------------------
// Tempo-synced stereo delay with ping-pong and filtered feedback. 100% wet.
// params: time index, feedback, ping-pong, low cut, high cut
// ---------------------------------------------------------------------------
class DelayEffect : public Effect {
public:
    DelayEffect() {
        const size_t len = static_cast<size_t>(4.0f * kMaxEffectSampleRate) + 2;
        buf_[0].assign(len, 0.f);
        buf_[1].assign(len, 0.f);
    }

    void process(float* l, float* r, int n, const float* p, const float*, const float*, const EffectContext& ctx) override {
        static constexpr float kBeats[6] = {0.25f, 0.5f, 0.75f, 1.f, 1.5f, 2.f};
        const int idx = std::clamp(static_cast<int>(std::lround(p[0])), 0, 5);
        const float seconds = kBeats[idx] * 60.f / std::max(20.f, ctx.tempo);
        const int size = static_cast<int>(buf_[0].size());
        const float targetDelay = std::clamp(seconds * ctx.sampleRate, 1.f, static_cast<float>(size - 2));
        if (delay_ <= 0.f) delay_ = targetDelay;
        const float fb = std::clamp(p[1], 0.f, 0.95f);
        const bool ping = p[2] >= 0.5f;
        if (p[3] != lc_ || p[4] != hc_ || ctx.sampleRate != sr_) {
            lc_ = p[3]; hc_ = p[4]; sr_ = ctx.sampleRate;
            for (int c = 0; c < 2; ++c) {
                lo_[c].highpass(safeFreq(lc_, sr_), 0.707f, sr_);
                hi_[c].lowpass(safeFreq(hc_, sr_), 0.707f, sr_);
            }
        }
        for (int i = 0; i < n; ++i) {
            delay_ += (targetDelay - delay_) * 0.0005f;  // smooth tempo changes
            float readPos = static_cast<float>(pos_) - delay_;
            if (readPos < 0) readPos += static_cast<float>(size);
            const int i0 = static_cast<int>(readPos);
            const int i1 = (i0 + 1) % size;
            const float frac = readPos - static_cast<float>(i0);
            const float dl = buf_[0][static_cast<size_t>(i0)] + (buf_[0][static_cast<size_t>(i1)] - buf_[0][static_cast<size_t>(i0)]) * frac;
            const float dr = buf_[1][static_cast<size_t>(i0)] + (buf_[1][static_cast<size_t>(i1)] - buf_[1][static_cast<size_t>(i0)]) * frac;
            const float fl = hi_[0].run(lo_[0].run(dl)) * fb;
            const float fr = hi_[1].run(lo_[1].run(dr)) * fb;
            if (ping) {
                buf_[0][static_cast<size_t>(pos_)] = (l[i] + r[i]) * 0.5f + fr;
                buf_[1][static_cast<size_t>(pos_)] = fl;
            } else {
                buf_[0][static_cast<size_t>(pos_)] = l[i] + fl;
                buf_[1][static_cast<size_t>(pos_)] = r[i] + fr;
            }
            if (++pos_ >= size) pos_ = 0;
            l[i] = dl;
            r[i] = dr;
        }
    }

    void reset() override {
        std::fill(buf_[0].begin(), buf_[0].end(), 0.f);
        std::fill(buf_[1].begin(), buf_[1].end(), 0.f);
        for (int c = 0; c < 2; ++c) { lo_[c].clear(); hi_[c].clear(); }
        delay_ = -1;
    }

private:
    std::vector<float> buf_[2];
    int pos_ = 0;
    float delay_ = -1, lc_ = -1, hc_ = -1, sr_ = 0;
    Bq lo_[2], hi_[2];
};

// ---------------------------------------------------------------------------
// Saturator: drive into tanh with tone tilt and output trim.
// params: drive dB, tone -1..1, output dB
// ---------------------------------------------------------------------------
class SaturatorEffect : public Effect {
public:
    void process(float* l, float* r, int n, const float* p, const float*, const float*, const EffectContext& ctx) override {
        const float drive = dbToGain(p[0]);
        const float comp = 1.f / std::tanh(std::max(1.f, drive));  // keep loudness roughly constant
        const float out = dbToGain(p[2]) * comp;
        const float tone = std::clamp(p[1], -1.f, 1.f);
        const float coef = timeCoef(1.f / (2 * kPi * 2500.f), ctx.sampleRate);
        for (int i = 0; i < n; ++i) {
            float x[2] = {std::tanh(l[i] * drive), std::tanh(r[i] * drive)};
            for (int c = 0; c < 2; ++c) {
                lp_[c] += (x[c] - lp_[c]) * coef;
                const float high = x[c] - lp_[c];
                x[c] = tone < 0 ? lp_[c] + high * (1 + tone) : x[c] + high * tone;
            }
            l[i] = x[0] * out;
            r[i] = x[1] * out;
        }
    }
    void reset() override { lp_[0] = lp_[1] = 0; }

private:
    float lp_[2] = {0, 0};
};

// ---------------------------------------------------------------------------
// Resonant state-variable filter (TPT). params: type (LP/HP/BP), cutoff Hz, resonance
// ---------------------------------------------------------------------------
class FilterEffect : public Effect {
public:
    void process(float* l, float* r, int n, const float* p, const float*, const float*, const EffectContext& ctx) override {
        const int type = std::clamp(static_cast<int>(std::lround(p[0])), 0, 2);
        const float target = safeFreq(p[1], ctx.sampleRate);
        if (cut_ <= 0) cut_ = target;
        const float k = 2.f - 1.9f * std::clamp(p[2], 0.f, 1.f);
        float* io[2] = {l, r};
        for (int i = 0; i < n; ++i) {
            cut_ += (target - cut_) * 0.01f;  // smooth sweeps / automation
            const float g = std::tan(kPi * cut_ / ctx.sampleRate);
            const float a1 = 1.f / (1.f + g * (g + k)), a2 = g * a1, a3 = g * a2;
            for (int c = 0; c < 2; ++c) {
                const float x = io[c][i];
                const float v3 = x - ic2_[c];
                const float v1 = a1 * ic1_[c] + a2 * v3;
                const float v2 = ic2_[c] + a2 * ic1_[c] + a3 * v3;
                ic1_[c] = 2 * v1 - ic1_[c];
                ic2_[c] = 2 * v2 - ic2_[c];
                io[c][i] = type == 0 ? v2 : (type == 1 ? x - k * v1 - v2 : v1);
            }
        }
    }
    void reset() override { ic1_[0] = ic1_[1] = ic2_[0] = ic2_[1] = 0; cut_ = -1; }

private:
    float ic1_[2] = {0, 0}, ic2_[2] = {0, 0}, cut_ = -1;
};

}  // namespace

std::unique_ptr<Effect> createEffect(EffectType type) {
    switch (type) {
        case EffectType::Eq: return std::make_unique<EqEffect>();
        case EffectType::Compressor: return std::make_unique<CompressorEffect>();
        case EffectType::Limiter: return std::make_unique<LimiterEffect>();
        case EffectType::Reverb: return std::make_unique<ReverbEffect>();
        case EffectType::Delay: return std::make_unique<DelayEffect>();
        case EffectType::Saturator: return std::make_unique<SaturatorEffect>();
        case EffectType::Filter: return std::make_unique<FilterEffect>();
        default: return nullptr;
    }
}

}  // namespace jb
