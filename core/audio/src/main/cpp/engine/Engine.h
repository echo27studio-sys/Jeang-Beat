#pragma once

#include <array>
#include <atomic>
#include <cstdint>
#include <memory>

#include "Constants.h"
#include "DrumKit.h"
#include "Snapshot.h"
#include "SpscQueue.h"
#include "Voice.h"

namespace jb {

// The Jeang Beat audio engine. Portable C++ with no Android dependency, so it
// can be unit-tested on a desktop and rendered offline for export.
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
    void setTempo(float bpm);
    void setSwing(float amount);  // 0..1
    void noteOn(int32_t channel, int32_t pitch, int32_t velocity);
    void noteOff(int32_t channel, int32_t pitch);
    void padHold(int32_t channel, int32_t pitch, int32_t velocity);
    void padRelease(int32_t channel, int32_t pitch);
    void setRepeatTicks(int32_t ticks);  // 0 = note repeat off
    void allNotesOff();
    void setMasterGain(float gain);

    // ---- Control thread: building the playback snapshot ----
    void beginSnapshot(int32_t lengthTicks);
    // Returns the channel index, or -1 if no snapshot is being built.
    int32_t addChannel(int32_t type, int32_t sound, const float* params, int32_t paramCount, float volume,
                       float pan, bool muted, bool solo);
    // packed = [startTick, lengthTicks, pitch, velocity] * count
    void setChannelNotes(int32_t channel, const int32_t* packed, int32_t count);
    void commitSnapshot();

    // ---- Any thread ----
    float positionTicks() const { return positionTicks_.load(std::memory_order_relaxed); }
    bool isPlaying() const { return playingFlag_.load(std::memory_order_relaxed); }
    float takePeak() { return peak_.exchange(0.f, std::memory_order_relaxed); }

    // ---- Audio thread ----
    // Fills `out` with `frames` interleaved stereo float samples.
    void render(float* out, int32_t frames);

private:
    enum class CommandType : int32_t {
        Play,
        Stop,
        SetTempo,
        SetSwing,
        NoteOn,
        NoteOff,
        PadHold,
        PadRelease,
        SetRepeatTicks,
        AllNotesOff,
        SetMasterGain,
    };
    struct Command {
        CommandType type = CommandType::Play;
        int32_t a = 0;
        int32_t b = 0;
        int32_t c = 0;
        float f = 0.f;
    };
    struct Event {
        int32_t offset;
        int32_t channel;
        int32_t pitch;
        int32_t velocity;
        int64_t lengthFrames;  // -1 = live note (no automatic note-off)
    };
    struct HeldPad {
        bool held = false;
        int32_t channel = 0;
        int32_t pitch = 0;
        int32_t velocity = 0;
    };

    void send(const Command& cmd);
    void handle(const Command& cmd);
    void collectGarbage();
    void recalcTiming();

    void collectSequenceEvents(double fromTick, double toTick, double frameBase);
    void collectRepeatEvents(double fromTick, double toTick);
    void addEvent(int32_t offset, int32_t channel, int32_t pitch, int32_t velocity, int64_t lengthFrames);
    void trigger(const Event& e);
    void releaseLive(int32_t channel, int32_t pitch);
    Voice* allocateVoice();
    void renderSegment(float* out, int32_t from, int32_t to);
    int32_t swingOffset(int32_t tick) const;

    int32_t sampleRate_ = 48000;
    DrumKit kit_;

    // Snapshot hand-over
    std::unique_ptr<Snapshot> building_;
    std::atomic<Snapshot*> pending_{nullptr};
    Snapshot* current_ = nullptr;           // owned; audio thread only
    SpscQueue<Snapshot*, 64> retired_;      // audio -> control, freed on control thread

    SpscQueue<Command, 1024> commands_;

    // Audio thread state
    std::array<Voice, kMaxVoices> voices_{};
    std::array<HeldPad, kMaxHeldPads> heldPads_{};
    std::array<Event, kMaxEventsPerSubBlock> events_{};
    int32_t eventCount_ = 0;
    uint64_t voiceCounter_ = 0;
    bool playing_ = false;
    double tick_ = 0.0;      // song position in ticks
    double freeTick_ = 0.0;  // always-running clock for note repeat while stopped
    float tempo_ = 140.f;
    float swing_ = 0.f;
    double framesPerTick_ = 0.0;
    int32_t repeatTicks_ = 0;
    float masterGain_ = 0.9f;

    std::atomic<float> positionTicks_{0.f};
    std::atomic<bool> playingFlag_{false};
    std::atomic<float> peak_{0.f};
};

}  // namespace jb
