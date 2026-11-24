#pragma once

#include <array>
#include <atomic>
#include <cstdint>
#include <memory>
#include <unordered_map>

#include "Constants.h"
#include "DrumKit.h"
#include "Effects.h"
#include "Snapshot.h"
#include "SpscQueue.h"
#include "Voice.h"

namespace jb {

// The Jeang Beat audio engine. Portable C++ with no Android dependency, so it
// can be unit-tested on a desktop and rendered offline for export.
//
// Signal flow:  voices / audio clips -> channel gain -> mixer insert
//               insert: effect slots -> fader/pan -> Master and sends
//               Master: effect slots -> fader -> soft clip -> output
//
// Threading:
//  * "Control" methods are called from one thread at a time (the app's UI side).
//  * render() is called from the real-time audio thread. It never allocates,
//    frees or locks. Control changes reach it via a lock-free command queue and
//    an atomic snapshot pointer swap.
class Engine {
public:
    Engine();
    ~Engine();

    Engine(const Engine&) = delete;
    Engine& operator=(const Engine&) = delete;

    // Must be called while the audio stream is stopped.
    void setSampleRate(int32_t sampleRate);
    int32_t sampleRate() const { return sampleRate_; }

    // ---- Control thread: transport and live playing ----
    void play();
    void stop();
    void setPosition(int64_t tick);  // song marker (SONG mode); jumps while playing
    void setTempo(float bpm);
    void setSwing(float amount);  // 0..1
    void noteOn(int32_t channel, int32_t pitch, int32_t velocity);
    void noteOff(int32_t channel, int32_t pitch);
    void padHold(int32_t channel, int32_t pitch, int32_t velocity);
    void padRelease(int32_t channel, int32_t pitch);
    void setRepeatTicks(int32_t ticks);  // 0 = note repeat off
    void allNotesOff();
    void setMasterGain(float gain);
    void setStemInsert(int32_t insert);  // offline stems: output one insert instead of Master (-1 = off)

    // ---- Control thread: samples (shared by channels and audio clips) ----
    void loadSample(int32_t id, const float* data, int64_t frames, int32_t channels, float sampleRate);
    void unloadSample(int32_t id);

    // ---- Control thread: building the playback snapshot ----
    void beginSnapshot(int32_t mode, int32_t currentPattern, int64_t songLength, int64_t loopStart, int64_t loopEnd);
    // Returns the channel index, or -1 if no snapshot is being built.
    int32_t addChannel(int32_t type, int32_t sound, int32_t sampleId, const float* params, int32_t paramCount,
                       float volume, float pan, bool muted, bool solo, int32_t insert);
    int32_t addPattern(int32_t lengthTicks, int32_t stepTicks);
    // ints = [start, length, pitch, velocity, flags] * count; floats = [pan, fineCents, release] * count
    void setPatternNotes(int32_t pattern, int32_t channel, const int32_t* ints, const float* floats, int32_t count);
    void addPatternClip(int64_t start, int64_t length, int64_t offset, int32_t pattern);
    void addAudioClip(int64_t start, int64_t length, int64_t offset, int32_t sampleId, float gain, int32_t insert);
    void addAutomation(int32_t target, int32_t a, int32_t b, int32_t c, float min, float max, bool log, int64_t start,
                       int64_t length, const int32_t* ticks, const float* values, int32_t count);
    void setInsert(int32_t index, float volume, float pan, bool muted, bool solo, bool toMaster, const int32_t* sendTargets,
                   const float* sendLevels, int32_t sendCount);
    void addEffect(int32_t insert, int32_t slotId, int32_t type, bool enabled, float mix, const float* params,
                   int32_t paramCount);
    void commitSnapshot();

    // Compatibility: single-pattern helpers used by simple callers and tests.
    void setChannelNotes(int32_t channel, const int32_t* packed, int32_t count);

    // ---- Any thread ----
    float positionTicks() const { return positionTicks_.load(std::memory_order_relaxed); }
    bool isPlaying() const { return playingFlag_.load(std::memory_order_relaxed); }
    float takePeak() { return peak_.exchange(0.f, std::memory_order_relaxed); }
    // Fills out[2*kMaxInserts] with L/R peaks since the last call and resets them.
    void takeMeters(float* out);

    // ---- Audio thread ----
    // Fills `out` with `frames` interleaved stereo float samples.
    void render(float* out, int32_t frames);

private:
    enum class CommandType : int32_t {
        Play, Stop, SetPosition, SetTempo, SetSwing, NoteOn, NoteOff, PadHold, PadRelease,
        SetRepeatTicks, AllNotesOff, SetMasterGain, SetStemInsert,
    };
    struct Command {
        CommandType type = CommandType::Play;
        int32_t a = 0;
        int32_t b = 0;
        int32_t c = 0;
        float f = 0.f;
        int64_t t = 0;
    };
    struct Event {
        int32_t offset;
        int32_t channel;
        int32_t pitch;
        int32_t velocity;
        int64_t lengthFrames;  // -1 = live note (no automatic note-off)
        float pan;
        float fineCents;
        float release;
        int32_t flags;
    };
    struct HeldPad {
        bool held = false;
        int32_t channel = 0;
        int32_t pitch = 0;
        int32_t velocity = 0;
    };
    struct Segment {
        double from, to;     // ticks
        int32_t frameStart;  // frame offset inside the sub-block
        int32_t frameEnd;
    };
    struct EffectEntry {
        EffectType type;
        std::shared_ptr<Effect> fx;
    };

    void send(const Command& cmd);
    void handle(const Command& cmd);
    void collectGarbage();
    void recalcTiming();
    static void updateChannelGain(ChannelData& ch);
    void computeOrder(Snapshot& s) const;

    // audio thread helpers
    void timelineBounds(double& start, double& end) const;
    int32_t makeSegments(double ticks, int32_t n, Segment* segs);
    void collectEvents(const Segment& seg);
    void collectPatternNotes(const PatternData& p, double base, double clipStart, double clipEnd, const Segment& seg);
    void collectRepeatEvents(double fromTick, double toTick);
    void addEvent(const Event& e);
    void trigger(const Event& e);
    void releaseLive(int32_t channel, int32_t pitch);
    void releaseSequenced();
    Voice* allocateVoice();
    void renderVoices(int32_t from, int32_t to);
    void mixAudioClips(const Segment& seg);
    void applyAutomation(double tick);
    void processMixer(float* out, int32_t n);
    int32_t swingOffset(int32_t tick, int32_t stepTicks) const;

    int32_t sampleRate_ = 48000;
    DrumKit kit_;

    // Control-thread state
    std::unique_ptr<Snapshot> building_;
    std::unordered_map<int32_t, std::shared_ptr<const SampleBuffer>> samples_;
    std::unordered_map<int32_t, EffectEntry> effects_;

    // Snapshot hand-over
    std::atomic<Snapshot*> pending_{nullptr};
    Snapshot* current_ = nullptr;       // owned; audio thread only
    SpscQueue<Snapshot*, 64> retired_;  // audio -> control, freed on control thread
    SpscQueue<Command, 1024> commands_;

    // Audio thread state
    std::array<Voice, kMaxVoices> voices_{};
    std::array<HeldPad, kMaxHeldPads> heldPads_{};
    std::array<Event, kMaxEventsPerSubBlock> events_{};
    int32_t eventCount_ = 0;
    uint64_t voiceCounter_ = 0;
    bool playing_ = false;
    PlayMode lastMode_ = PlayMode::Pattern;
    double tick_ = 0.0;       // position in ticks (pattern or song)
    double startTick_ = 0.0;  // song marker
    double freeTick_ = 0.0;   // always-running clock for note repeat while stopped
    float tempo_ = 140.f;
    float swing_ = 0.f;
    double framesPerTick_ = 0.0;
    int32_t repeatTicks_ = 0;
    float masterGain_ = 1.f;
    int32_t stemInsert_ = -1;

    // Mixer bus buffers for one sub-block: [insert][channel][frame]
    float insIn_[kMaxInserts][2][kSubBlockFrames] = {};
    float insOut_[kMaxInserts][2][kSubBlockFrames] = {};
    float dry_[2][kSubBlockFrames] = {};

    std::atomic<float> positionTicks_{0.f};
    std::atomic<bool> playingFlag_{false};
    std::atomic<float> peak_{0.f};
    std::atomic<float> meters_[kMaxInserts * 2];
};

}  // namespace jb
