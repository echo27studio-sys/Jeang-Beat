// Host-side tests for the Jeang Beat audio engine (no Android needed).
// Build & run: see native-tests/CMakeLists.txt or ./native-tests/run.sh

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <functional>
#include <string>
#include <vector>

#include "Constants.h"
#include "DrumKit.h"
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

// Renders `frames` stereo frames in audio-sized blocks; returns the left channel.
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

int firstNonZero(const std::vector<float>& d, int from = 0) {
    for (size_t i = static_cast<size_t>(from); i < d.size(); ++i) {
        if (std::fabs(d[i]) > 1e-6f) return static_cast<int>(i);
    }
    return -1;
}

bool allFinite(const std::vector<float>& d) {
    for (float s : d) {
        if (!std::isfinite(s)) return false;
    }
    return true;
}

float peak(const std::vector<float>& d, int from = 0, int to = -1) {
    if (to < 0) to = static_cast<int>(d.size());
    float p = 0.f;
    for (int i = from; i < to; ++i) p = std::max(p, std::fabs(d[static_cast<size_t>(i)]));
    return p;
}

struct ChannelSpec {
    int type;
    int sound;
    std::vector<float> params;
    std::vector<int> notes;  // packed start, length, pitch, velocity
    bool muted = false;
};

void load(Engine& e, int lengthTicks, const std::vector<ChannelSpec>& channels) {
    e.beginSnapshot(lengthTicks);
    for (size_t i = 0; i < channels.size(); ++i) {
        const auto& c = channels[i];
        e.addChannel(c.type, c.sound, c.params.data(), static_cast<int>(c.params.size()), 0.8f, 0.f, c.muted, false);
        e.setChannelNotes(static_cast<int>(i), c.notes.data(), static_cast<int>(c.notes.size() / 4));
    }
    e.commitSnapshot();
}

std::vector<float> drumParams() { return {0.f, 1.f, 0.f}; }
std::vector<float> bassParams() { return {0.8f, 0.08f, 0.3f, 0.5f, 0.f}; }
std::vector<float> synthParams() { return {0.f, 0.6f, 0.3f, 0.01f, 0.3f, 0.6f, 0.2f, 7.f, 0.4f}; }

// 120 BPM at 48 kHz -> 250 frames per tick.
constexpr int kFramesPerTick = kSr * 60 / (120 * kTicksPerBeat);

void testKit() {
    std::puts("DrumKit");
    DrumKit kit;
    bool ok = true;
    for (int i = 0; i < static_cast<int>(DrumSoundId::Count); ++i) {
        const SampleBuffer* b = kit.get(i);
        if (b == nullptr || b->data.empty() || !allFinite(b->data) || peak(b->data) < 0.5f ||
            peak(b->data) > 0.91f) {
            ok = false;
            std::printf("    bad sound id %d\n", i);
        }
    }
    check(ok, "all built-in sounds are generated, finite and normalised");
    check(kit.get(-1) == nullptr && kit.get(999) == nullptr, "out-of-range sound ids return null");
}

void testTiming() {
    std::puts("Sequencer timing");
    {
        Engine e;
        e.setSampleRate(kSr);
        e.setTempo(120.f);
        load(e, 16 * kTicksPerStep, {{0, 0, drumParams(), {48, 24, 60, 127}}});
        e.play();
        auto out = renderLeft(e, kSr);
        const int onset = firstNonZero(out);
        check(std::abs(onset - 48 * kFramesPerTick) <= 1,
              "note at tick 48 starts at frame 12000 (got " + std::to_string(onset) + ")");
    }
    {
        Engine e;
        e.setSampleRate(kSr);
        e.setTempo(120.f);
        e.setSwing(1.f);
        load(e, 16 * kTicksPerStep, {{0, 0, drumParams(), {24, 24, 60, 127}}});
        e.play();
        auto out = renderLeft(e, kSr);
        const int onset = firstNonZero(out);
        check(std::abs(onset - 32 * kFramesPerTick) <= 1,
              "full swing delays the off-beat 16th by a third of a step (got " + std::to_string(onset) + ")");
    }
    {
        Engine e;
        e.setSampleRate(kSr);
        e.setTempo(120.f);
        const int length = 16 * kTicksPerStep;  // 96000 frames at 120 BPM
        load(e, length, {{0, 0, drumParams(), {0, 24, 60, 127}}});
        e.play();
        auto out = renderLeft(e, length * kFramesPerTick + kSr / 2);
        check(firstNonZero(out) == 0, "first note plays at frame 0");
        const int second = firstNonZero(out, 60000);
        check(std::abs(second - length * kFramesPerTick) <= 1,
              "pattern loops and replays at frame 96000 (got " + std::to_string(second) + ")");
    }
    {
        Engine e;
        e.setSampleRate(kSr);
        load(e, 16 * kTicksPerStep, {{0, 0, drumParams(), {0, 24, 60, 127}}});
        auto out = renderLeft(e, kSr / 2);
        check(firstNonZero(out) == -1, "nothing plays while the transport is stopped");
    }
}

void testMuteAndLive() {
    std::puts("Mute and live notes");
    {
        Engine e;
        e.setSampleRate(kSr);
        ChannelSpec c{0, 0, drumParams(), {0, 24, 60, 127}};
        c.muted = true;
        load(e, 16 * kTicksPerStep, {c});
        e.play();
        auto out = renderLeft(e, kSr / 2);
        check(firstNonZero(out) == -1, "muted channel is silent");
    }
    {
        Engine e;
        e.setSampleRate(kSr);
        load(e, 16 * kTicksPerStep, {{0, 1, drumParams(), {}}});
        e.noteOn(0, 60, 100);
        auto out = renderLeft(e, kSr / 4);
        check(firstNonZero(out) == 0, "live note-on plays immediately");
    }
    {
        Engine e;
        e.setSampleRate(kSr);
        e.setTempo(120.f);
        load(e, 16 * kTicksPerStep, {{0, 3, drumParams(), {}}});
        e.setRepeatTicks(kTicksPerStep);  // 1/16 note repeat
        e.padHold(0, 60, 100);
        auto out = renderLeft(e, kSr);
        // Closed hat is 0.25 s long, 16ths at 120 BPM are 0.125 s apart: signal
        // must still be present late in the second, long after one hit decays.
        check(peak(out, kSr * 3 / 4, kSr) > 0.05f, "note repeat keeps re-triggering while a pad is held");
        e.padRelease(0, 60);
        auto after = renderLeft(e, kSr);
        check(peak(after, kSr / 2, kSr) < 1e-4f, "releasing the pad stops note repeat");
    }
}

void testInstruments() {
    std::puts("Instruments");
    {
        Engine e;
        e.setSampleRate(kSr);
        e.setTempo(120.f);
        // Quarter-note long 808 at C2.
        load(e, 64 * kTicksPerStep, {{1, 0, bassParams(), {0, 96, 36, 120}}});
        e.play();
        auto out = renderLeft(e, kSr);
        check(allFinite(out), "808 output is finite");
        check(peak(out, 0, 12000) > 0.2f, "808 is audible while the note is held");
        // note ends at 96 ticks = 24000 frames; release is ~60 ms
        check(peak(out, 24000 + 6000, kSr) < 5e-3f, "808 releases after the note ends");
    }
    {
        Engine e;
        e.setSampleRate(kSr);
        e.setTempo(120.f);
        // Two overlapping 808 notes: the second should glide, not retrigger.
        load(e, 64 * kTicksPerStep, {{1, 0, bassParams(), {0, 120, 36, 120, 96, 96, 43, 120}}});
        e.play();
        auto out = renderLeft(e, kSr);
        check(allFinite(out) && peak(out, 24000, 30000) > 0.05f, "overlapping 808 notes glide legato");
    }
    {
        Engine e;
        e.setSampleRate(kSr);
        e.setTempo(120.f);
        for (int wave = 0; wave < 4; ++wave) {
            auto p = synthParams();
            p[0] = static_cast<float>(wave);
            load(e, 64 * kTicksPerStep, {{2, 0, p, {0, 96, 60, 110, 0, 96, 64, 110, 0, 96, 67, 110}}});
            e.play();
            auto out = renderLeft(e, kSr);
            e.stop();
            renderLeft(e, kSr);  // let it ring out
            check(allFinite(out) && peak(out, 0, 20000) > 0.05f,
                  "synth waveform " + std::to_string(wave) + " plays a chord");
        }
    }
}

void testLimiter() {
    std::puts("Master limiter");
    Engine e;
    e.setSampleRate(kSr);
    e.setTempo(120.f);
    std::vector<ChannelSpec> loud;
    for (int i = 0; i < 12; ++i) loud.push_back({0, 0, drumParams(), {0, 24, 60, 127}});
    load(e, 16 * kTicksPerStep, loud);
    e.setMasterGain(2.f);
    e.play();
    auto out = renderLeft(e, kSr / 2);
    check(allFinite(out) && peak(out) < 1.f, "12 stacked kicks at 2x gain never exceed full scale");
}

void testSnapshotSwapUnderLoad() {
    std::puts("Snapshot swap");
    Engine e;
    e.setSampleRate(kSr);
    e.play();
    std::vector<float> buf(kBlock * 2);
    bool ok = true;
    for (int i = 0; i < 2000; ++i) {
        load(e, (1 + i % 4) * 16 * kTicksPerStep,
             {{0, i % 12, drumParams(), {0, 24, 60, 100, 96, 24, 62, 100}}, {2, 0, synthParams(), {48, 48, 60, 90}}});
        e.render(buf.data(), kBlock);
        for (float s : buf) ok = ok && std::isfinite(s);
    }
    check(ok, "2000 rapid project updates while playing stay glitch-free (no NaN, no crash)");
}

}  // namespace

int main() {
    testKit();
    testTiming();
    testMuteAndLive();
    testInstruments();
    testLimiter();
    testSnapshotSwapUnderLoad();
    if (failures == 0) {
        std::puts("\nAll engine tests passed.");
        return EXIT_SUCCESS;
    }
    std::printf("\n%d engine test(s) FAILED.\n", failures);
    return EXIT_FAILURE;
}
