#include "Engine.h"

#include <algorithm>
#include <cmath>

#include "Dsp.h"

namespace jb {

namespace {
constexpr double kFreeClockWrap = static_cast<double>(kTicksPerBeat) * 4.0 * 1024.0;

float velocityToGain(int32_t velocity) {
    const float v = std::clamp(static_cast<float>(velocity) / 127.f, 0.f, 1.f);
    return std::pow(v, 1.6f);
}

// Soft knee limiter: transparent below 0.7, smoothly saturates towards 0.98.
float softClip(float x) {
    const float a = std::fabs(x);
    if (a <= 0.7f) return x;
    const float y = 0.7f + 0.28f * std::tanh((a - 0.7f) / 0.28f);
    return x < 0.f ? -y : y;
}
}  // namespace

Engine::Engine() : current_(new Snapshot()) { recalcTiming(); }

Engine::~Engine() {
    collectGarbage();
    delete pending_.exchange(nullptr);
    delete current_;
}

void Engine::setSampleRate(int32_t sampleRate) {
    if (sampleRate > 0) sampleRate_ = sampleRate;
    recalcTiming();
}

void Engine::recalcTiming() {
    framesPerTick_ = static_cast<double>(sampleRate_) * 60.0 / (static_cast<double>(tempo_) * kTicksPerBeat);
}

// ---------------------------------------------------------------------------
// Control thread
// ---------------------------------------------------------------------------

void Engine::send(const Command& cmd) {
    // The queue holds 1024 commands; if the audio thread is not running the
    // queue may fill up and further commands are dropped, which is harmless.
    commands_.push(cmd);
}

void Engine::play() { send({CommandType::Play}); }
void Engine::stop() { send({CommandType::Stop}); }
void Engine::setTempo(float bpm) { send({CommandType::SetTempo, 0, 0, 0, std::clamp(bpm, 20.f, 400.f)}); }
void Engine::setSwing(float amount) { send({CommandType::SetSwing, 0, 0, 0, std::clamp(amount, 0.f, 1.f)}); }
void Engine::noteOn(int32_t channel, int32_t pitch, int32_t velocity) {
    send({CommandType::NoteOn, channel, pitch, velocity});
}
void Engine::noteOff(int32_t channel, int32_t pitch) { send({CommandType::NoteOff, channel, pitch}); }
void Engine::padHold(int32_t channel, int32_t pitch, int32_t velocity) {
    send({CommandType::PadHold, channel, pitch, velocity});
}
void Engine::padRelease(int32_t channel, int32_t pitch) { send({CommandType::PadRelease, channel, pitch}); }
void Engine::setRepeatTicks(int32_t ticks) { send({CommandType::SetRepeatTicks, std::max(0, ticks)}); }
void Engine::allNotesOff() { send({CommandType::AllNotesOff}); }
void Engine::setMasterGain(float gain) { send({CommandType::SetMasterGain, 0, 0, 0, std::clamp(gain, 0.f, 2.f)}); }

void Engine::beginSnapshot(int32_t lengthTicks) {
    building_ = std::make_unique<Snapshot>();
    building_->lengthTicks = std::max(kTicksPerStep, lengthTicks);
}

int32_t Engine::addChannel(int32_t type, int32_t sound, const float* params, int32_t paramCount, float volume,
                           float pan, bool muted, bool solo) {
    if (!building_) return -1;
    ChannelData ch;
    ch.type = static_cast<InstrumentType>(std::clamp(type, 0, 2));
    ch.sound = sound;
    const int32_t n = std::clamp(paramCount, 0, kMaxParams);
    for (int32_t i = 0; i < n; ++i) ch.params[i] = params[i];
    const float v = std::clamp(volume, 0.f, 1.f);
    const float gain = v * v * 1.2f;
    const float angle = (std::clamp(pan, -1.f, 1.f) + 1.f) * static_cast<float>(M_PI) / 4.f;
    ch.gainL = gain * std::cos(angle) * static_cast<float>(M_SQRT2);
    ch.gainR = gain * std::sin(angle) * static_cast<float>(M_SQRT2);
    ch.muted = muted;
    ch.solo = solo;
    building_->channels.push_back(std::move(ch));
    return static_cast<int32_t>(building_->channels.size()) - 1;
}

void Engine::setChannelNotes(int32_t channel, const int32_t* packed, int32_t count) {
    if (!building_ || channel < 0 || channel >= static_cast<int32_t>(building_->channels.size())) return;
    auto& notes = building_->channels[static_cast<size_t>(channel)].notes;
    notes.clear();
    notes.reserve(static_cast<size_t>(std::max(0, count)));
    for (int32_t i = 0; i < count; ++i) {
        const int32_t* p = packed + i * 4;
        NoteEvent e{p[0], std::max(1, p[1]), std::clamp(p[2], 0, 127), std::clamp(p[3], 1, 127)};
        if (e.startTick < 0 || e.startTick >= building_->lengthTicks) continue;
        notes.push_back(e);
    }
    std::sort(notes.begin(), notes.end(),
              [](const NoteEvent& a, const NoteEvent& b) { return a.startTick < b.startTick; });
}

void Engine::commitSnapshot() {
    if (!building_) return;
    bool anySolo = false;
    for (const auto& ch : building_->channels) anySolo = anySolo || ch.solo;
    for (auto& ch : building_->channels) ch.audible = !ch.muted && (!anySolo || ch.solo);

    Snapshot* replaced = pending_.exchange(building_.release(), std::memory_order_acq_rel);
    // The audio thread never saw `replaced` (it only takes snapshots via
    // exchange), so it is safe to free it here.
    delete replaced;
    collectGarbage();
}

void Engine::collectGarbage() {
    Snapshot* old = nullptr;
    while (retired_.pop(old)) delete old;
}

// ---------------------------------------------------------------------------
// Audio thread
// ---------------------------------------------------------------------------

void Engine::handle(const Command& cmd) {
    switch (cmd.type) {
        case CommandType::Play:
            playing_ = true;
            tick_ = 0.0;
            break;
        case CommandType::Stop:
            playing_ = false;
            tick_ = 0.0;
            for (auto& v : voices_) {
                if (v.active && !v.live) v.release();
            }
            break;
        case CommandType::SetTempo:
            tempo_ = cmd.f;
            recalcTiming();
            break;
        case CommandType::SetSwing:
            swing_ = cmd.f;
            break;
        case CommandType::NoteOn:
            trigger({0, cmd.a, cmd.b, cmd.c, -1});
            break;
        case CommandType::NoteOff:
            releaseLive(cmd.a, cmd.b);
            break;
        case CommandType::PadHold: {
            trigger({0, cmd.a, cmd.b, cmd.c, -1});
            HeldPad* slot = nullptr;
            for (auto& p : heldPads_) {
                if (p.held && p.channel == cmd.a && p.pitch == cmd.b) slot = &p;
            }
            if (slot == nullptr) {
                for (auto& p : heldPads_) {
                    if (!p.held) {
                        slot = &p;
                        break;
                    }
                }
            }
            if (slot != nullptr) *slot = {true, cmd.a, cmd.b, cmd.c};
            break;
        }
        case CommandType::PadRelease:
            for (auto& p : heldPads_) {
                if (p.held && p.channel == cmd.a && p.pitch == cmd.b) p.held = false;
            }
            releaseLive(cmd.a, cmd.b);
            break;
        case CommandType::SetRepeatTicks:
            repeatTicks_ = cmd.a;
            break;
        case CommandType::AllNotesOff:
            for (auto& p : heldPads_) p.held = false;
            for (auto& v : voices_) {
                if (v.active) v.startFade(0.01f, static_cast<float>(sampleRate_));
            }
            break;
        case CommandType::SetMasterGain:
            masterGain_ = cmd.f;
            break;
    }
}

int32_t Engine::swingOffset(int32_t tick) const {
    // Delay every off-beat 16th. Full swing = triplet shuffle (1/3 of a step).
    if (swing_ <= 0.f) return 0;
    if (tick % (kTicksPerStep * 2) != kTicksPerStep) return 0;
    return static_cast<int32_t>(std::lround(swing_ * kTicksPerStep / 3.0f));
}

void Engine::addEvent(int32_t offset, int32_t channel, int32_t pitch, int32_t velocity, int64_t lengthFrames) {
    if (eventCount_ >= kMaxEventsPerSubBlock) return;
    events_[static_cast<size_t>(eventCount_++)] = {offset, channel, pitch, velocity, lengthFrames};
}

void Engine::collectSequenceEvents(double fromTick, double toTick, double frameBase) {
    const Snapshot& snap = *current_;
    const int32_t length = snap.lengthTicks;
    for (size_t c = 0; c < snap.channels.size(); ++c) {
        const ChannelData& ch = snap.channels[c];
        if (!ch.audible) continue;
        for (const NoteEvent& n : ch.notes) {
            double start = n.startTick + swingOffset(n.startTick);
            if (start >= length) start -= length;
            if (start < fromTick || start >= toTick) continue;
            const auto offset = static_cast<int32_t>(std::floor(frameBase + (start - fromTick) * framesPerTick_ + 1e-6));
            const auto lengthFrames = static_cast<int64_t>(std::max(1.0, n.lengthTicks * framesPerTick_));
            addEvent(offset, static_cast<int32_t>(c), n.pitch, n.velocity, lengthFrames);
        }
    }
}

void Engine::collectRepeatEvents(double fromTick, double toTick) {
    if (repeatTicks_ <= 0) return;
    bool anyHeld = false;
    for (const auto& p : heldPads_) anyHeld = anyHeld || p.held;
    if (!anyHeld) return;
    const double r = repeatTicks_;
    const auto lengthFrames = static_cast<int64_t>(std::max(1.0, r * 0.9 * framesPerTick_));
    for (double g = std::ceil(fromTick / r) * r; g < toTick; g += r) {
        const auto offset = static_cast<int32_t>(std::floor((g - fromTick) * framesPerTick_ + 1e-6));
        for (const auto& p : heldPads_) {
            if (p.held) addEvent(offset, p.channel, p.pitch, p.velocity, lengthFrames);
        }
    }
}

Voice* Engine::allocateVoice() {
    Voice* oldest = &voices_[0];
    for (auto& v : voices_) {
        if (!v.active) return &v;
        if (v.order < oldest->order) oldest = &v;
    }
    return oldest;  // steal the oldest voice
}

void Engine::releaseLive(int32_t channel, int32_t pitch) {
    for (auto& v : voices_) {
        if (v.active && v.live && v.channel == channel && v.pitch == pitch) v.release();
    }
}

void Engine::trigger(const Event& e) {
    const Snapshot& snap = *current_;
    if (e.channel < 0 || e.channel >= static_cast<int32_t>(snap.channels.size())) return;
    const ChannelData& ch = snap.channels[static_cast<size_t>(e.channel)];
    const auto sr = static_cast<float>(sampleRate_);
    const bool live = e.lengthFrames < 0;
    const int64_t untilOff = live ? -1 : std::max<int64_t>(1, e.lengthFrames);

    if (ch.type == InstrumentType::Bass808) {
        const float tune = ch.params[bass_param::kTune];
        const float target = midiToHz(static_cast<float>(e.pitch) + tune);
        const bool glide = ch.params[bass_param::kGlide] > 0.001f;
        for (auto& v : voices_) {
            if (!v.active || v.channel != e.channel || v.type != InstrumentType::Bass808) continue;
            if (v.fadeStep > 0.f) continue;
            const bool held = !v.releasing && (v.live || v.framesUntilOff > 0);
            if (glide && held) {
                // Overlapping 808 notes slide into each other (legato glide).
                v.targetFreq = target;
                v.pitch = e.pitch;
                v.live = live;
                v.framesUntilOff = untilOff;
                return;
            }
            v.startFade(0.005f, sr);  // 808 is monophonic per channel
        }
    }

    if (ch.type == InstrumentType::Drum) {
        const auto choke = static_cast<int32_t>(ch.params[drum_param::kChokeGroup]);
        if (choke > 0) {
            for (auto& v : voices_) {
                if (v.active && v.type == InstrumentType::Drum && v.chokeGroup == choke) v.startFade(0.008f, sr);
            }
        }
    }

    Voice* v = allocateVoice();
    *v = Voice{};
    v->active = true;
    v->type = ch.type;
    v->channel = e.channel;
    v->pitch = e.pitch;
    v->velocity = velocityToGain(e.velocity);
    v->live = live;
    v->framesUntilOff = untilOff;
    v->order = ++voiceCounter_;

    switch (ch.type) {
        case InstrumentType::Drum: {
            v->buffer = kit_.get(ch.sound);
            v->chokeGroup = static_cast<int32_t>(ch.params[drum_param::kChokeGroup]);
            const float semis = static_cast<float>(e.pitch - 60) + ch.params[drum_param::kTune];
            v->rate = static_cast<double>(kKitSampleRate) / sampleRate_ * std::pow(2.0, semis / 12.0);
            break;
        }
        case InstrumentType::Bass808: {
            const float f = midiToHz(static_cast<float>(e.pitch) + ch.params[bass_param::kTune]);
            v->freq = f;
            v->targetFreq = f;
            v->env = 1.f;
            break;
        }
        case InstrumentType::Synth: {
            const float f = midiToHz(static_cast<float>(e.pitch));
            v->freq = f;
            v->targetFreq = f;
            v->phase2 = 0.25;  // avoid the two oscillators cancelling at start
            break;
        }
    }
}

void Engine::renderSegment(float* out, int32_t from, int32_t to) {
    if (to <= from) return;
    const Snapshot& snap = *current_;
    const auto sr = static_cast<float>(sampleRate_);
    for (auto& v : voices_) {
        if (!v.active) continue;
        if (v.channel < 0 || v.channel >= static_cast<int32_t>(snap.channels.size())) {
            v.active = false;
            continue;
        }
        const ChannelData& ch = snap.channels[static_cast<size_t>(v.channel)];
        for (int32_t i = from; i < to && v.active; ++i) {
            if (v.framesUntilOff > 0 && --v.framesUntilOff == 0) v.release();
            const float s = v.render(ch, sr);
            out[2 * i] += s * ch.gainL;
            out[2 * i + 1] += s * ch.gainR;
        }
    }
}

void Engine::render(float* out, int32_t frames) {
    // Adopt the newest snapshot, if any. The old one is freed on the control thread.
    if (Snapshot* next = pending_.exchange(nullptr, std::memory_order_acq_rel)) {
        if (!retired_.push(current_)) {
            // Queue full: control thread has not collected garbage. Keep the old
            // snapshot (never free on the audio thread) and drop the new one
            // back into pending so we try again next block.
            Snapshot* expected = nullptr;
            if (!pending_.compare_exchange_strong(expected, next)) retired_.push(next);
        } else {
            current_ = next;
        }
    }

    Command cmd;
    while (commands_.pop(cmd)) handle(cmd);

    std::fill(out, out + static_cast<size_t>(frames) * 2, 0.f);

    const double length = current_->lengthTicks;
    if (playing_ && tick_ >= length) tick_ = std::fmod(tick_, length);

    for (int32_t done = 0; done < frames;) {
        const int32_t n = std::min(kSubBlockFrames, frames - done);
        const double ticks = n / framesPerTick_;
        eventCount_ = 0;

        if (playing_) {
            const double from = tick_;
            const double to = tick_ + ticks;
            if (to <= length) {
                collectSequenceEvents(from, to, 0.0);
            } else {
                collectSequenceEvents(from, length, 0.0);
                collectSequenceEvents(0.0, to - length, (length - from) * framesPerTick_);
            }
            collectRepeatEvents(from, to);
        } else {
            collectRepeatEvents(freeTick_, freeTick_ + ticks);
        }

        // Insertion sort by frame offset (event counts are small).
        for (int32_t i = 1; i < eventCount_; ++i) {
            Event e = events_[static_cast<size_t>(i)];
            int32_t j = i - 1;
            while (j >= 0 && events_[static_cast<size_t>(j)].offset > e.offset) {
                events_[static_cast<size_t>(j + 1)] = events_[static_cast<size_t>(j)];
                --j;
            }
            events_[static_cast<size_t>(j + 1)] = e;
        }

        float* block = out + static_cast<size_t>(done) * 2;
        int32_t pos = 0;
        for (int32_t i = 0; i < eventCount_; ++i) {
            const Event& e = events_[static_cast<size_t>(i)];
            const int32_t at = std::clamp(e.offset, 0, n - 1);
            renderSegment(block, pos, at);
            trigger(e);
            pos = at;
        }
        renderSegment(block, pos, n);

        if (playing_) {
            tick_ += ticks;
            if (tick_ >= length) tick_ -= length;
        }
        freeTick_ += ticks;
        if (freeTick_ >= kFreeClockWrap) freeTick_ -= kFreeClockWrap;
        done += n;
    }

    float peak = 0.f;
    for (int32_t i = 0; i < frames * 2; ++i) {
        const float y = softClip(out[i] * masterGain_);
        out[i] = y;
        peak = std::max(peak, std::fabs(y));
    }
    if (peak > peak_.load(std::memory_order_relaxed)) peak_.store(peak, std::memory_order_relaxed);
    positionTicks_.store(static_cast<float>(tick_), std::memory_order_relaxed);
    playingFlag_.store(playing_, std::memory_order_relaxed);
}

}  // namespace jb
