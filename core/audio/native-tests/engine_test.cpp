// Host-side tests for the Jeang Beat audio engine (no Android needed).
// Build & run: ./native-tests/run.sh

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include "Constants.h"
#include "DrumKit.h"
#include "Dsp.h"
#include "Effects.h"
#include "Engine.h"

using namespace jb;

namespace {

int failures = 0;

void check(bool ok, const std::string& what) {
    std::printf("  %s %s\n", ok ? "PASS" : "FAIL", what.c_str());
    if (!ok) ++failures;
}

constexpr int kSr = 48000;
constexpr int kBlock = 256;
constexpr int kBar = 384;
// 120 BPM at 48 kHz -> 250 frames per tick.
constexpr int kFramesPerTick = kSr * 60 / (120 * kTicksPerBeat);

std::vector<float> renderLeft(Engine& e, int frames) {
    std::vector<float> left;
    left.reserve(static_cast<size_t>(frames));
    std::vector<float> buf(kBlock * 2);
    for (int done = 0; done < frames; done += kBlock) {
        const int n = std::min(kBlock, frames - done);
        e.render(buf.data(), n);
        for (int i = 0; i < n; ++i) left.push_back(buf[static_cast<size_t>(2 * i)]);
    }
    return left;
}

int firstNonZero(const std::vector<float>& d, int from = 0, float eps = 1e-6f) {
    for (size_t i = static_cast<size_t>(from); i < d.size(); ++i) {
        if (std::fabs(d[i]) > eps) return static_cast<int>(i);
    }
    return -1;
}

bool allFinite(const std::vector<float>& d) {
    for (float s : d) if (!std::isfinite(s)) return false;
    return true;
}

float peak(const std::vector<float>& d, int from = 0, int to = -1) {
    if (to < 0) to = static_cast<int>(d.size());
    float p = 0.f;
    for (int i = from; i < to; ++i) p = std::max(p, std::fabs(d[static_cast<size_t>(i)]));
    return p;
}

float rms(const std::vector<float>& d, int from, int to) {
    double s = 0;
    for (int i = from; i < to; ++i) s += d[static_cast<size_t>(i)] * d[static_cast<size_t>(i)];
    return static_cast<float>(std::sqrt(s / std::max(1, to - from)));
}

struct ChannelSpec {
    int type;
    int sound;
    std::vector<float> params;
    std::vector<int> notes;  // packed start, length, pitch, velocity
    bool muted = false;
    int insert = 0;
    int flags = 0;  // applied to all notes
};

std::vector<float> drumParams() { return {0.f, 1.f, 0.f}; }
std::vector<float> bassParams() { return {0.8f, 0.08f, 0.3f, 0.5f, 0.f}; }
std::vector<float> synthParams() { return {0.f, 0.6f, 0.3f, 0.01f, 0.3f, 0.6f, 0.2f, 7.f, 0.4f}; }

void addNotes(Engine& e, int pattern, int channel, const std::vector<int>& packed, int flags = 0) {
    const int count = static_cast<int>(packed.size() / 4);
    std::vector<int> ints;
    std::vector<float> floats;
    for (int i = 0; i < count; ++i) {
        for (int k = 0; k < 4; ++k) ints.push_back(packed[static_cast<size_t>(i * 4 + k)]);
        ints.push_back(flags);
        floats.insert(floats.end(), {0.f, 0.f, 0.5f});
    }
    e.setPatternNotes(pattern, channel, ints.data(), floats.data(), count);
}

// Pattern mode, one pattern, every channel straight to Master.
void load(Engine& e, int lengthTicks, const std::vector<ChannelSpec>& channels, int mode = 0, int songLength = 0,
          std::vector<std::vector<long>> clips = {}) {
    e.beginSnapshot(mode, 0, songLength > 0 ? songLength : lengthTicks, -1, -1);
    for (const auto& c : channels) {
        e.addChannel(c.type, c.sound, -1, c.params.data(), static_cast<int>(c.params.size()), 0.8f, 0.f, c.muted, false, c.insert);
    }
    const int p = e.addPattern(lengthTicks, kTicksPerStep);
    for (size_t i = 0; i < channels.size(); ++i) addNotes(e, p, static_cast<int>(i), channels[i].notes, channels[i].flags);
    for (const auto& c : clips) e.addPatternClip(c[0], c[1], c[2], static_cast<int>(c[3]));
    e.commitSnapshot();
}

Engine* newEngine() {
    auto* e = new Engine();
    e->setSampleRate(kSr);
    e->setTempo(120.f);
    return e;
}

void testKit() {
    std::puts("DrumKit");
    DrumKit kit;
    bool ok = true;
    for (int i = 0; i < static_cast<int>(DrumSoundId::Count); ++i) {
        const SampleBuffer* b = kit.get(i);
        if (b == nullptr || b->data.empty() || !allFinite(b->data) || peak(b->data) < 0.5f || peak(b->data) > 0.91f) {
            ok = false;
            std::printf("    bad sound id %d\n", i);
        }
    }
    check(ok, "all built-in sounds are generated, finite and normalised");
    check(kit.get(-1) == nullptr && kit.get(999) == nullptr, "out-of-range sound ids return null");
}

void testTiming() {
    std::puts("Pattern timing");
    {
        Engine& e = *newEngine();
        load(e, 16 * kTicksPerStep, {{0, 0, drumParams(), {48, 24, 60, 127}}});
        e.play();
        auto out = renderLeft(e, kSr);
        const int onset = firstNonZero(out);
        check(std::abs(onset - 48 * kFramesPerTick) <= 1, "note at tick 48 starts at frame 12000 (got " + std::to_string(onset) + ")");
        delete &e;
    }
    {
        Engine& e = *newEngine();
        e.setSwing(1.f);
        load(e, 16 * kTicksPerStep, {{0, 0, drumParams(), {24, 24, 60, 127}}});
        e.play();
        auto out = renderLeft(e, kSr);
        const int onset = firstNonZero(out);
        check(std::abs(onset - 32 * kFramesPerTick) <= 1, "full swing delays the off-beat 16th by a third of a step (got " + std::to_string(onset) + ")");
        delete &e;
    }
    {
        Engine& e = *newEngine();
        load(e, kBar, {{0, 0, drumParams(), {0, 24, 60, 127}}});
        e.play();
        auto out = renderLeft(e, kBar * kFramesPerTick + kSr / 2);
        check(firstNonZero(out) == 0, "first note plays at frame 0");
        const int second = firstNonZero(out, 60000);
        check(std::abs(second - kBar * kFramesPerTick) <= 1, "pattern loops and replays at frame 96000 (got " + std::to_string(second) + ")");
        delete &e;
    }
    {
        Engine& e = *newEngine();
        load(e, kBar, {{0, 0, drumParams(), {0, 24, 60, 127}}});
        auto out = renderLeft(e, kSr / 2);
        check(firstNonZero(out) == -1, "nothing plays while the transport is stopped");
        delete &e;
    }
}

void testSong() {
    std::puts("Song mode (Playlist)");
    {
        // Pattern with a hit on tick 0; clip placed at bar 2, two bars long -> hits at bar 2 and 3.
        Engine& e = *newEngine();
        load(e, kBar, {{0, 0, drumParams(), {0, 24, 60, 127}}}, 1, 4 * kBar, {{kBar, 2 * kBar, 0, 0}});
        e.play();
        auto out = renderLeft(e, 4 * kBar * kFramesPerTick);
        const int first = firstNonZero(out);
        check(std::abs(first - kBar * kFramesPerTick) <= 1, "clip at bar 2 starts at frame 96000 (got " + std::to_string(first) + ")");
        const int second = firstNonZero(out, first + 60000);
        check(std::abs(second - 2 * kBar * kFramesPerTick) <= 1, "a clip longer than its pattern repeats it (got " + std::to_string(second) + ")");
        check(peak(out, 3 * kBar * kFramesPerTick + 40000, 4 * kBar * kFramesPerTick) < 1e-4f, "nothing plays after the clip ends");
        delete &e;
    }
    {
        // Offset clip (after slicing): starts half-way into the pattern.
        Engine& e = *newEngine();
        load(e, kBar, {{0, 0, drumParams(), {192, 24, 60, 127}}}, 1, kBar, {{0, 192, 192, 0}});
        e.play();
        auto out = renderLeft(e, kBar * kFramesPerTick / 2);
        check(firstNonZero(out) == 0, "clip offset plays the pattern from the middle");
        delete &e;
    }
    {
        // Song marker: start playback from bar 2.
        Engine& e = *newEngine();
        load(e, kBar, {{0, 0, drumParams(), {0, 24, 60, 127}}}, 1, 3 * kBar, {{kBar, kBar, 0, 0}});
        e.setPosition(kBar);
        e.play();
        auto out = renderLeft(e, kSr / 4);
        check(firstNonZero(out) == 0, "play starts from the song marker");
        delete &e;
    }
    {
        // Loop region: [bar 2, bar 3) repeats.
        Engine& e = *newEngine();
        e.beginSnapshot(1, 0, 4 * kBar, kBar, 2 * kBar);
        std::vector<float> p = drumParams();
        e.addChannel(0, 0, -1, p.data(), 3, 0.8f, 0.f, false, false, 0);
        const int pat = e.addPattern(kBar, kTicksPerStep);
        addNotes(e, pat, 0, {0, 24, 60, 127});
        e.addPatternClip(kBar, kBar, 0, pat);
        e.commitSnapshot();
        e.setPosition(kBar);
        e.play();
        auto out = renderLeft(e, kBar * kFramesPerTick + 1000);
        const int again = firstNonZero(out, 60000);
        check(std::abs(again - kBar * kFramesPerTick) <= 1, "loop region repeats (got " + std::to_string(again) + ")");
        delete &e;
    }
}

void testPatternLoop() {
    std::puts("Pattern loop (piano roll)");
    Engine& e = *newEngine();
    // 4-bar pattern, hit at bar 3; loop bars 3..4 starting from bar 3.
    e.beginSnapshot(0, 0, 4 * kBar, -1, -1, 2 * kBar, 3 * kBar);
    std::vector<float> p = drumParams();
    e.addChannel(0, 0, -1, p.data(), 3, 0.8f, 0.f, false, false, 0);
    addNotes(e, e.addPattern(4 * kBar, kTicksPerStep), 0, {2 * kBar, 24, 60, 127});
    e.commitSnapshot();
    e.setPatternPosition(2 * kBar);
    e.play();
    auto out = renderLeft(e, kBar * kFramesPerTick + 2000);
    check(firstNonZero(out) == 0, "PAT mode starts at the pattern position");
    const int again = firstNonZero(out, 60000);
    check(std::abs(again - kBar * kFramesPerTick) <= 1, "piano-roll loop repeats one bar (got " + std::to_string(again) + ")");
    delete &e;
}

void testMixer() {
    std::puts("Mixer");
    const std::vector<int> hit = {0, 24, 60, 127};
    {
        Engine& e = *newEngine();
        e.beginSnapshot(0, 0, kBar, -1, -1);
        std::vector<float> p = drumParams();
        e.addChannel(0, 0, -1, p.data(), 3, 0.8f, 0.f, false, false, 1);
        addNotes(e, e.addPattern(kBar, 24), 0, hit);
        e.setInsert(1, 0.f, 0.f, false, false, true, nullptr, nullptr, 0);  // fader down
        e.commitSnapshot();
        e.play();
        check(peak(renderLeft(e, kSr / 4)) < 1e-6f, "insert fader at zero silences its channel");
        delete &e;
    }
    {
        Engine& e = *newEngine();
        e.beginSnapshot(0, 0, kBar, -1, -1);
        std::vector<float> p = drumParams();
        e.addChannel(0, 0, -1, p.data(), 3, 0.8f, 0.f, false, false, 1);
        addNotes(e, e.addPattern(kBar, 24), 0, hit);
        const int targets[] = {2};
        const float levels[] = {1.f};
        e.setInsert(1, 0.8f, 0.f, false, false, false, targets, levels, 1);  // not to master, send to 2
        e.commitSnapshot();
        e.play();
        check(peak(renderLeft(e, kSr / 4)) > 0.2f, "a send reaches Master through another insert");
        delete &e;
    }
    {
        Engine& e = *newEngine();
        e.beginSnapshot(0, 0, kBar, -1, -1);
        std::vector<float> p = drumParams();
        e.addChannel(0, 0, -1, p.data(), 3, 0.8f, 0.f, false, false, 1);
        e.addChannel(0, 1, -1, p.data(), 3, 0.8f, 0.f, false, false, 2);
        const int pat = e.addPattern(kBar, 24);
        addNotes(e, pat, 0, hit);
        addNotes(e, pat, 1, hit);
        e.setInsert(2, 0.8f, 0.f, true, false, true, nullptr, nullptr, 0);  // mute insert 2
        e.commitSnapshot();
        e.setStemInsert(2);
        e.play();
        check(peak(renderLeft(e, kSr / 4)) < 1e-6f, "muted insert is silent (stem render of insert 2)");
        e.setStemInsert(1);
        e.stop();
        e.play();
        check(peak(renderLeft(e, kSr / 4)) > 0.2f, "stem render outputs a single insert");
        float meters[kMaxInserts * 2];
        e.takeMeters(meters);
        check(meters[2] > 0.1f && meters[4] < 1e-6f, "meters report per-insert peaks");
        delete &e;
    }
}

// Renders a held synth on insert 2 with a kick on insert 1; returns insert 2's stem.
std::vector<float> sidechainRun(bool withSidechain) {
    Engine& e = *newEngine();
    e.beginSnapshot(0, 0, kBar, -1, -1);
    std::vector<float> d = drumParams();
    std::vector<float> s = synthParams();
    s[5] = 1.f;  // full sustain
    e.addChannel(0, 0, -1, d.data(), 3, 0.8f, 0.f, false, false, 1);
    e.addChannel(2, 0, -1, s.data(), 9, 0.8f, 0.f, false, false, 2);
    const int pat = e.addPattern(kBar, 24);
    addNotes(e, pat, 0, {0, 24, 60, 127, 192, 24, 60, 127});
    addNotes(e, pat, 1, {0, kBar, 48, 110});
    if (withSidechain) {
        const float comp[] = {-30.f, 10.f, 0.5f, 80.f, 0.f, 1.f};
        e.addEffect(2, 1, static_cast<int>(EffectType::Compressor), true, 1.f, comp, 6);
    }
    e.commitSnapshot();
    e.setStemInsert(2);
    e.play();
    auto out = renderLeft(e, kBar * kFramesPerTick);
    delete &e;
    return out;
}

void testSidechain() {
    std::puts("Sidechain");
    auto dry = sidechainRun(false);
    auto ducked = sidechainRun(true);
    // Just after the second kick (tick 192 = frame 48000) the synth should be quieter.
    const int from = 192 * kFramesPerTick + 500, to = from + 4000;
    const float a = rms(dry, from, to), b = rms(ducked, from, to);
    check(allFinite(ducked) && b < a * 0.6f, "kick on insert 1 ducks the synth on insert 2 (" + std::to_string(b / a) + "x)");
}

void testEffects() {
    std::puts("Effects");
    const EffectContext ctx{48000.f, 120.f};
    const std::vector<std::vector<float>> params = {
        {100, 6, 1000, -6, 1, 8000, 3, 80},  // EQ
        {-20, 4, 10, 120, 3, -1},            // Compressor
        {6, -1, 60},                         // Limiter
        {0.8f, 0.4f, 1, 20, 150},            // Reverb
        {1, 0.6f, 1, 200, 8000},             // Delay
        {12, 0.3f, 0},                       // Saturator
        {0, 800, 0.9f},                      // Filter
    };
    for (int t = 0; t < static_cast<int>(EffectType::Count); ++t) {
        auto fx = createEffect(static_cast<EffectType>(t));
        Noise noise(t + 1);
        bool ok = fx != nullptr;
        float maxOut = 0;
        for (int block = 0; block < 400 && ok; ++block) {
            float l[64], r[64];
            for (int i = 0; i < 64; ++i) { l[i] = noise.next() * 0.9f; r[i] = noise.next() * 0.9f; }
            fx->process(l, r, 64, params[static_cast<size_t>(t)].data(), nullptr, nullptr, ctx);
            for (int i = 0; i < 64; ++i) {
                ok = ok && std::isfinite(l[i]) && std::isfinite(r[i]);
                maxOut = std::max(maxOut, std::max(std::fabs(l[i]), std::fabs(r[i])));
            }
        }
        check(ok && maxOut < 8.f && maxOut > 1e-4f, "effect " + std::to_string(t) + " is stable on loud noise (max " + std::to_string(maxOut) + ")");
    }
    {
        auto lim = createEffect(EffectType::Limiter);
        const float p[] = {12.f, -1.f, 60.f};
        float l[64], r[64];
        float maxOut = 0;
        Noise noise(9);
        for (int b = 0; b < 100; ++b) {
            for (int i = 0; i < 64; ++i) { l[i] = noise.next(); r[i] = noise.next(); }
            lim->process(l, r, 64, p, nullptr, nullptr, ctx);
            for (int i = 0; i < 64; ++i) maxOut = std::max(maxOut, std::max(std::fabs(l[i]), std::fabs(r[i])));
        }
        check(maxOut <= std::pow(10.f, -1.f / 20.f) + 1e-5f, "limiter never exceeds its ceiling");
    }
    {
        // Delay: 1/4 note at 120 BPM = 0.5 s = 24000 frames.
        auto delay = createEffect(EffectType::Delay);
        const float p[] = {3, 0.f, 0, 20, 20000};
        std::vector<float> out;
        for (int b = 0; b < 500; ++b) {
            float l[64] = {}, r[64] = {};
            if (b == 0) l[0] = r[0] = 1.f;
            delay->process(l, r, 64, p, nullptr, nullptr, ctx);
            out.insert(out.end(), l, l + 64);
        }
        const int echo = firstNonZero(out, 1, 0.05f);
        check(std::abs(echo - 24000) <= 2, "tempo-synced 1/4 delay echoes after 24000 frames (got " + std::to_string(echo) + ")");
    }
}

void testReverbSurvivesEdits() {
    std::puts("Effect state across edits");
    Engine& e = *newEngine();
    auto build = [&](float vol) {
        e.beginSnapshot(0, 0, 4 * kBar, -1, -1);
        std::vector<float> p = drumParams();
        e.addChannel(0, 1, -1, p.data(), 3, vol, 0.f, false, false, 1);
        addNotes(e, e.addPattern(4 * kBar, 24), 0, {0, 24, 60, 127});
        const float rv[] = {0.9f, 0.2f, 1.f, 0.f, 20.f};
        e.addEffect(1, 77, static_cast<int>(EffectType::Reverb), true, 1.f, rv, 5);
        e.commitSnapshot();
    };
    build(0.8f);
    e.play();
    renderLeft(e, kSr / 4);
    build(0.7f);  // same slot id 77: the reverb tail must continue
    auto tail = renderLeft(e, kSr / 4);
    check(rms(tail, 0, 4000) > 1e-3f, "reverb tail continues after a project edit");
    delete &e;
}

void testAudioClipsAndAutomation() {
    std::puts("Audio clips and automation");
    std::vector<float> click(4800 * 2, 0.f);
    click[0] = click[1] = 0.9f;
    for (int i = 1; i < 4800; ++i) click[static_cast<size_t>(i * 2)] = click[static_cast<size_t>(i * 2 + 1)] = 0.5f;
    {
        Engine& e = *newEngine();
        e.loadSample(5, click.data(), 4800, 2, 48000.f);
        e.beginSnapshot(1, 0, kBar, -1, -1);
        e.addPattern(kBar, 24);
        e.addAudioClip(96, 96, 0, 5, 1.f, 0);
        e.commitSnapshot();
        e.play();
        auto out = renderLeft(e, kSr);
        const int onset = firstNonZero(out);
        check(std::abs(onset - 96 * kFramesPerTick) <= 1, "audio clip at tick 96 starts at frame 24000 (got " + std::to_string(onset) + ")");
        check(peak(out, 96 * kFramesPerTick + 4900, kSr) < 1e-6f, "audio clip stops at the end of the sample");
        delete &e;
    }
    {
        Engine& e = *newEngine();
        e.loadSample(5, click.data(), 4800, 2, 48000.f);
        e.beginSnapshot(1, 0, kBar, -1, -1);
        e.addPattern(kBar, 24);
        e.addAudioClip(0, kBar, 0, 5, 1.f, 3);
        const int ticks[] = {0, kBar};
        const float values[] = {0.f, 0.f};
        e.addAutomation(0, 3, 0, 0, 0.f, 1.f, false, 0, kBar, ticks, values, 2);  // insert 3 volume -> 0
        e.commitSnapshot();
        e.play();
        check(peak(renderLeft(e, kSr / 4)) < 1e-6f, "volume automation at 0 silences the insert");
        delete &e;
    }
    {
        Engine& e = *newEngine();
        e.loadSample(5, click.data(), 4800, 2, 48000.f);
        e.beginSnapshot(1, 0, kBar, -1, -1);
        e.addPattern(kBar, 24);
        e.addAudioClip(0, kBar, 0, 5, 1.f, 0);
        e.commitSnapshot();
        e.play();
        renderLeft(e, 512);
        e.unloadSample(5);
        e.beginSnapshot(1, 0, kBar, -1, -1);  // sample removed from the project
        e.addPattern(kBar, 24);
        e.commitSnapshot();
        auto out = renderLeft(e, 4096);
        check(allFinite(out), "removing a playing sample is safe");
        delete &e;
    }
}

void testInstruments() {
    std::puts("Instruments");
    {
        Engine& e = *newEngine();
        load(e, 4 * kBar, {{1, 0, bassParams(), {0, 96, 36, 120}}});
        e.play();
        auto out = renderLeft(e, kSr);
        check(allFinite(out) && peak(out, 0, 12000) > 0.2f, "808 is audible while the note is held");
        check(peak(out, 24000 + 6000, kSr) < 5e-3f, "808 releases after the note ends");
        delete &e;
    }
    {
        // Slide note: glides the playing 808 note instead of starting a new one.
        Engine& e = *newEngine();
        e.beginSnapshot(0, 0, 4 * kBar, -1, -1);
        std::vector<float> b = bassParams();
        b[1] = 0.f;  // no channel glide, only the slide note
        e.addChannel(1, 0, -1, b.data(), 5, 0.8f, 0.f, false, false, 0);
        const int pat = e.addPattern(4 * kBar, 24);
        const int ints[] = {0, 192, 36, 120, 0, 96, 96, 43, 120, 1};
        const float floats[] = {0, 0, 0.5f, 0, 0, 0.5f};
        e.setPatternNotes(pat, 0, ints, floats, 2);
        e.commitSnapshot();
        e.play();
        auto out = renderLeft(e, kSr);
        check(allFinite(out) && peak(out, 96 * kFramesPerTick, 150 * kFramesPerTick) > 0.05f, "slide note keeps the 808 sounding and glides it");
        delete &e;
    }
    {
        Engine& e = *newEngine();
        for (int wave = 0; wave < 4; ++wave) {
            auto p = synthParams();
            p[0] = static_cast<float>(wave);
            load(e, 4 * kBar, {{2, 0, p, {0, 96, 60, 110, 0, 96, 64, 110, 0, 96, 67, 110}}});
            e.play();
            auto out = renderLeft(e, kSr);
            e.stop();
            renderLeft(e, kSr);
            check(allFinite(out) && peak(out, 0, 20000) > 0.05f, "synth waveform " + std::to_string(wave) + " plays a chord");
        }
        delete &e;
    }
    {
        // Sampler channel plays an imported sample at the root pitch.
        Engine& e = *newEngine();
        std::vector<float> tone(9600);
        for (size_t i = 0; i < tone.size(); ++i) tone[i] = 0.5f * std::sin(i * 2 * M_PI * 440 / 48000);
        e.loadSample(9, tone.data(), 9600, 1, 48000.f);
        e.beginSnapshot(0, 0, kBar, -1, -1);
        const float sp[] = {0.f, 1.f, 0.f, 60.f};
        e.addChannel(3, 0, 9, sp, 4, 0.8f, 0.f, false, false, 0);
        addNotes(e, e.addPattern(kBar, 24), 0, {0, 24, 60, 120});
        e.commitSnapshot();
        e.play();
        auto out = renderLeft(e, kSr / 4);
        check(firstNonZero(out) <= 1 && peak(out, 0, 9000) > 0.1f && peak(out, 9700, kSr / 4) < 1e-6f, "sampler channel plays the imported sample once");
        delete &e;
    }
}

void testLimiterAndSwap() {
    std::puts("Safety");
    {
        Engine& e = *newEngine();
        std::vector<ChannelSpec> loud;
        for (int i = 0; i < 12; ++i) loud.push_back({0, 0, drumParams(), {0, 24, 60, 127}});
        load(e, kBar, loud);
        e.setMasterGain(2.f);
        e.play();
        auto out = renderLeft(e, kSr / 2);
        check(allFinite(out) && peak(out) < 1.f, "12 stacked kicks at 2x gain never exceed full scale");
        delete &e;
    }
    {
        Engine& e = *newEngine();
        e.play();
        std::vector<float> buf(kBlock * 2);
        bool ok = true;
        for (int i = 0; i < 1500; ++i) {
            e.beginSnapshot(i % 2, 0, 2 * kBar, -1, -1);
            std::vector<float> d = drumParams(), s = synthParams();
            e.addChannel(0, i % 12, -1, d.data(), 3, 0.8f, 0.f, false, false, 1 + i % 3);
            e.addChannel(2, 0, -1, s.data(), 9, 0.8f, 0.f, false, false, 2);
            const int pat = e.addPattern((1 + i % 4) * kBar, 24);
            addNotes(e, pat, 0, {0, 24, 60, 100, 96, 24, 62, 100});
            addNotes(e, pat, 1, {48, 48, 60, 90});
            e.addPatternClip(0, 2 * kBar, 0, pat);
            const float rv[] = {0.6f, 0.4f, 1.f, 10.f, 150.f};
            e.addEffect(2, 100 + i % 3, static_cast<int>(EffectType::Reverb), true, 0.3f, rv, 5);
            const int t[] = {3};
            const float l[] = {0.5f};
            e.setInsert(1, 0.8f, 0.f, false, false, true, t, l, 1);
            e.commitSnapshot();
            e.render(buf.data(), kBlock);
            for (float x : buf) ok = ok && std::isfinite(x);
        }
        check(ok, "1500 rapid project updates while playing stay glitch-free (no NaN, no crash)");
        delete &e;
    }
}

}  // namespace

int main() {
    testKit();
    testTiming();
    testSong();
    testPatternLoop();
    testMixer();
    testSidechain();
    testEffects();
    testReverbSurvivesEdits();
    testAudioClipsAndAutomation();
    testInstruments();
    testLimiterAndSwap();
    if (failures == 0) {
        std::puts("\nAll engine tests passed.");
        return EXIT_SUCCESS;
    }
    std::printf("\n%d engine test(s) FAILED.\n", failures);
    return EXIT_FAILURE;
}
