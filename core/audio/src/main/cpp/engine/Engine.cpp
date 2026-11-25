#include "Engine.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#include "Dsp.h"

namespace jb {

namespace {
constexpr double kFreeClockWrap = static_cast<double>(kTicksPerBeat) * 4.0 * 1024.0;
constexpr float kSqrt2 = 1.41421356f;

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

// Equal-power pan, normalised so centre = 1.0 on both sides.
void panGains(float pan, float& l, float& r) {
    const float angle = (std::clamp(pan, -1.f, 1.f) + 1.f) * static_cast<float>(M_PI) / 4.f;
    l = std::cos(angle) * kSqrt2;
    r = std::sin(angle) * kSqrt2;
}

// Mixer fader position (0..1, 0.8 = unity) to linear gain. Matches faderGain() in Mixer.kt.
float faderGain(float v) {
    const float x = std::max(0.f, v) / 0.8f;
    return x * x;
}
}  // namespace

Engine::Engine() : current_(new Snapshot()) {
    for (auto& m : meters_) m.store(0.f);
    computeOrder(*current_);
    recalcTiming();
}

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

void Engine::updateChannelGain(ChannelData& ch) {
    const float v = std::clamp(ch.liveVolume, 0.f, 1.f);
    const float gain = v * v * 1.2f;
    float l, r;
    panGains(ch.livePan, l, r);
    ch.gainL = gain * l;
    ch.gainR = gain * r;
}

// ---------------------------------------------------------------------------
// Control thread
// ---------------------------------------------------------------------------

void Engine::send(const Command& cmd) {
    // If the audio thread is not running the queue may fill up and further
    // commands are dropped, which is harmless.
    commands_.push(cmd);
}

void Engine::play() { send({CommandType::Play}); }
void Engine::stop() { send({CommandType::Stop}); }
void Engine::setPosition(int64_t tick) { send({CommandType::SetPosition, 0, 0, 0, 0.f, std::max<int64_t>(0, tick)}); }
void Engine::setPatternPosition(int64_t tick) { send({CommandType::SetPatternPosition, 0, 0, 0, 0.f, std::max<int64_t>(0, tick)}); }
void Engine::setTempo(float bpm) { send({CommandType::SetTempo, 0, 0, 0, std::clamp(bpm, 20.f, 400.f)}); }
void Engine::setSwing(float amount) { send({CommandType::SetSwing, 0, 0, 0, std::clamp(amount, 0.f, 1.f)}); }
void Engine::noteOn(int32_t channel, int32_t pitch, int32_t velocity) { send({CommandType::NoteOn, channel, pitch, velocity}); }
void Engine::noteOff(int32_t channel, int32_t pitch) { send({CommandType::NoteOff, channel, pitch}); }
void Engine::padHold(int32_t channel, int32_t pitch, int32_t velocity) { send({CommandType::PadHold, channel, pitch, velocity}); }
void Engine::padRelease(int32_t channel, int32_t pitch) { send({CommandType::PadRelease, channel, pitch}); }
void Engine::setRepeatTicks(int32_t ticks) { send({CommandType::SetRepeatTicks, std::max(0, ticks)}); }
void Engine::allNotesOff() { send({CommandType::AllNotesOff}); }
void Engine::setMasterGain(float gain) { send({CommandType::SetMasterGain, 0, 0, 0, std::clamp(gain, 0.f, 2.f)}); }
void Engine::setStemInsert(int32_t insert) { send({CommandType::SetStemInsert, insert}); }

void Engine::loadSample(int32_t id, const float* data, int64_t frames, int32_t channels, float sampleRate) {
    auto buf = std::make_shared<SampleBuffer>();
    const int32_t c = std::clamp(channels, 1, 2);
    buf->channels = c;
    buf->sampleRate = sampleRate > 0 ? sampleRate : 48000.f;
    buf->data.assign(data, data + frames * c);
    samples_[id] = std::move(buf);
}

void Engine::unloadSample(int32_t id) { samples_.erase(id); }

void Engine::beginSnapshot(int32_t mode, int32_t currentPattern, int64_t songLength, int64_t loopStart, int64_t loopEnd,
                           int64_t patternLoopStart, int64_t patternLoopEnd) {
    building_ = std::make_unique<Snapshot>();
    building_->mode = mode == 1 ? PlayMode::Song : PlayMode::Pattern;
    building_->currentPattern = std::max(0, currentPattern);
    building_->songLength = std::max<int64_t>(kTicksPerStep, songLength);
    building_->loopStart = loopStart;
    building_->loopEnd = loopEnd;
    building_->patternLoopStart = patternLoopStart;
    building_->patternLoopEnd = patternLoopEnd;
}

int32_t Engine::addChannel(int32_t type, int32_t sound, int32_t sampleId, const float* params, int32_t paramCount,
                           float volume, float pan, bool muted, bool solo, int32_t insert) {
    if (!building_) return -1;
    ChannelData ch;
    ch.type = static_cast<InstrumentType>(std::clamp(type, 0, 3));
    ch.sound = sound;
    if (ch.type == InstrumentType::Sampler) {
        auto it = samples_.find(sampleId);
        if (it != samples_.end()) ch.sample = it->second;
    }
    const int32_t n = std::clamp(paramCount, 0, kMaxParams);
    for (int32_t i = 0; i < n; ++i) ch.params[i] = params[i];
    ch.volume = ch.liveVolume = std::clamp(volume, 0.f, 1.f);
    ch.pan = ch.livePan = std::clamp(pan, -1.f, 1.f);
    updateChannelGain(ch);
    ch.muted = muted;
    ch.solo = solo;
    ch.insert = std::clamp(insert, 0, kMaxInserts - 1);
    building_->channels.push_back(std::move(ch));
    return static_cast<int32_t>(building_->channels.size()) - 1;
}

int32_t Engine::addPattern(int32_t lengthTicks, int32_t stepTicks) {
    if (!building_) return -1;
    PatternData p;
    p.lengthTicks = std::max(kTicksPerStep, lengthTicks);
    p.stepTicks = std::max(1, stepTicks);
    building_->patterns.push_back(std::move(p));
    return static_cast<int32_t>(building_->patterns.size()) - 1;
}

void Engine::setPatternNotes(int32_t pattern, int32_t channel, const int32_t* ints, const float* floats, int32_t count) {
    if (!building_ || pattern < 0 || pattern >= static_cast<int32_t>(building_->patterns.size())) return;
    if (channel < 0 || channel >= static_cast<int32_t>(building_->channels.size())) return;
    auto& p = building_->patterns[static_cast<size_t>(pattern)];
    if (p.notes.size() < building_->channels.size()) p.notes.resize(building_->channels.size());
    auto& notes = p.notes[static_cast<size_t>(channel)];
    notes.clear();
    notes.reserve(static_cast<size_t>(std::max(0, count)));
    for (int32_t i = 0; i < count; ++i) {
        const int32_t* q = ints + i * 5;
        const float* f = floats + i * 3;
        NoteEvent e{q[0], std::max(1, q[1]), std::clamp(q[2], 0, 127), std::clamp(q[3], 1, 127),
                    std::clamp(f[0], -1.f, 1.f), std::clamp(f[1], -100.f, 100.f), std::clamp(f[2], 0.f, 1.f), q[4]};
        if (e.startTick < 0 || e.startTick >= p.lengthTicks) continue;
        notes.push_back(e);
    }
    std::sort(notes.begin(), notes.end(), [](const NoteEvent& a, const NoteEvent& b) { return a.startTick < b.startTick; });
}

void Engine::setChannelNotes(int32_t channel, const int32_t* packed, int32_t count) {
    // Old 4-int format [start, length, pitch, velocity] into pattern 0.
    if (!building_) return;
    if (building_->patterns.empty()) addPattern(16 * kTicksPerStep, kTicksPerStep);
    std::vector<int32_t> ints(static_cast<size_t>(count) * 5);
    std::vector<float> floats(static_cast<size_t>(count) * 3);
    for (int32_t i = 0; i < count; ++i) {
        for (int k = 0; k < 4; ++k) ints[static_cast<size_t>(i * 5 + k)] = packed[i * 4 + k];
        ints[static_cast<size_t>(i * 5 + 4)] = 0;
        floats[static_cast<size_t>(i * 3)] = 0.f;
        floats[static_cast<size_t>(i * 3 + 1)] = 0.f;
        floats[static_cast<size_t>(i * 3 + 2)] = 0.5f;
    }
    setPatternNotes(0, channel, ints.data(), floats.data(), count);
}

void Engine::addPatternClip(int64_t start, int64_t length, int64_t offset, int32_t pattern) {
    if (!building_ || length <= 0) return;
    ClipData c;
    c.type = ClipType::Pattern;
    c.start = start;
    c.length = length;
    c.offset = offset;
    c.pattern = pattern;
    building_->clips.push_back(std::move(c));
}

void Engine::addAudioClip(int64_t start, int64_t length, int64_t offset, int32_t sampleId, float gain, int32_t insert) {
    if (!building_ || length <= 0) return;
    auto it = samples_.find(sampleId);
    if (it == samples_.end()) return;
    ClipData c;
    c.type = ClipType::Audio;
    c.start = start;
    c.length = length;
    c.offset = offset;
    c.sample = it->second;
    c.gain = gain;
    c.insert = std::clamp(insert, 0, kMaxInserts - 1);
    building_->clips.push_back(std::move(c));
}

void Engine::addAutomation(int32_t target, int32_t a, int32_t b, int32_t c, float min, float max, bool log, int64_t start,
                           int64_t length, const int32_t* ticks, const float* values, int32_t count) {
    if (!building_ || count <= 0 || length <= 0) return;
    AutomationData d;
    d.target = static_cast<AutoTarget>(std::clamp(target, 0, 4));
    d.a = a;
    d.b = b;
    d.c = c;
    d.min = min;
    d.max = max;
    d.log = log;
    d.start = start;
    d.length = length;
    d.ticks.assign(ticks, ticks + count);
    d.values.assign(values, values + count);
    building_->automation.push_back(std::move(d));
}

void Engine::setInsert(int32_t index, float volume, float pan, bool muted, bool solo, bool toMaster,
                       const int32_t* sendTargets, const float* sendLevels, int32_t sendCount) {
    if (!building_ || index < 0 || index >= kMaxInserts) return;
    InsertData& ins = building_->inserts[index];
    ins.volume = ins.liveVolume = std::max(0.f, volume);
    ins.pan = ins.livePan = std::clamp(pan, -1.f, 1.f);
    ins.muted = muted;
    ins.solo = solo;
    ins.toMaster = toMaster;
    ins.sends.clear();
    for (int32_t i = 0; i < sendCount; ++i) {
        const int32_t t = sendTargets[i];
        if (t > 0 && t < kMaxInserts && t != index) ins.sends.push_back({t, std::clamp(sendLevels[i], 0.f, 1.f)});
    }
}

void Engine::addEffect(int32_t insert, int32_t slotId, int32_t type, bool enabled, float mix, const float* params,
                       int32_t paramCount) {
    if (!building_ || insert < 0 || insert >= kMaxInserts) return;
    InsertData& ins = building_->inserts[insert];
    if (ins.slotCount >= kMaxEffectSlots) return;
    const auto t = static_cast<EffectType>(std::clamp(type, 0, static_cast<int32_t>(EffectType::Count) - 1));
    // Reuse the instance for this slot id so reverb tails and delay lines survive edits.
    auto it = effects_.find(slotId);
    if (it == effects_.end() || it->second.type != t) {
        std::shared_ptr<Effect> fx(createEffect(t).release());
        effects_[slotId] = {t, fx};
        it = effects_.find(slotId);
    }
    EffectData& slot = ins.slots[ins.slotCount++];
    slot.fx = it->second.fx;
    slot.type = t;
    slot.enabled = enabled;
    slot.mix = std::clamp(mix, 0.f, 1.f);
    const int32_t n = std::clamp(paramCount, 0, kMaxEffectParams);
    for (int32_t i = 0; i < n; ++i) slot.params[i] = slot.live[i] = params[i];
    slot.sidechain = t == EffectType::Compressor && n > 5 ? static_cast<int32_t>(std::lround(params[5])) : -1;
}

void Engine::computeOrder(Snapshot& s) const {
    // Kahn's algorithm over inserts 1..N (edges: sends and sidechain keys); Master is always last.
    int indeg[kMaxInserts] = {};
    bool edge[kMaxInserts][kMaxInserts] = {};
    auto link = [&](int from, int to) {
        if (from <= 0 || to <= 0 || from == to || edge[from][to]) return;
        edge[from][to] = true;
        indeg[to]++;
    };
    for (int i = 1; i < kMaxInserts; ++i) {
        for (const auto& snd : s.inserts[i].sends) link(i, snd.target);
    }
    for (int j = 0; j < kMaxInserts; ++j) {
        for (int k = 0; k < s.inserts[j].slotCount; ++k) {
            const int src = s.inserts[j].slots[k].sidechain;
            if (src > 0 && src < kMaxInserts) link(src, j);
        }
    }
    bool done[kMaxInserts] = {};
    s.orderCount = 0;
    for (int round = 1; round < kMaxInserts; ++round) {
        int pick = -1;
        for (int i = 1; i < kMaxInserts; ++i) {
            if (!done[i] && indeg[i] == 0) { pick = i; break; }
        }
        if (pick < 0) break;  // cycle: remaining inserts are appended below
        done[pick] = true;
        s.order[s.orderCount++] = pick;
        for (int j = 1; j < kMaxInserts; ++j) if (edge[pick][j]) indeg[j]--;
    }
    for (int i = 1; i < kMaxInserts; ++i) if (!done[i]) s.order[s.orderCount++] = i;
    s.order[s.orderCount++] = 0;
}

void Engine::commitSnapshot() {
    if (!building_) return;
    Snapshot& s = *building_;
    bool anySolo = false;
    for (const auto& ch : s.channels) anySolo = anySolo || ch.solo;
    for (auto& ch : s.channels) ch.audible = !ch.muted && (!anySolo || ch.solo);
    for (auto& p : s.patterns) p.notes.resize(s.channels.size());
    if (s.currentPattern >= static_cast<int32_t>(s.patterns.size())) s.currentPattern = 0;

    bool anyInsertSolo = false;
    for (int i = 1; i < kMaxInserts; ++i) anyInsertSolo = anyInsertSolo || s.inserts[i].solo;
    for (int i = 0; i < kMaxInserts; ++i) {
        InsertData& ins = s.inserts[i];
        ins.audible = !ins.muted && (i == 0 || !anyInsertSolo || ins.solo);
    }
    computeOrder(s);

    // Forget effect instances no longer used. Snapshots still holding them keep
    // them alive until those snapshots are freed here on the control thread.
    for (auto it = effects_.begin(); it != effects_.end();) {
        bool used = false;
        for (int i = 0; i < kMaxInserts && !used; ++i) {
            for (int k = 0; k < s.inserts[i].slotCount && !used; ++k) used = s.inserts[i].slots[k].fx == it->second.fx;
        }
        it = used ? std::next(it) : effects_.erase(it);
    }

    Snapshot* replaced = pending_.exchange(building_.release(), std::memory_order_acq_rel);
    // The audio thread never saw `replaced` (it only takes snapshots via exchange).
    delete replaced;
    collectGarbage();
}

void Engine::collectGarbage() {
    Snapshot* old = nullptr;
    while (retired_.pop(old)) delete old;
}

void Engine::takeMeters(float* out) {
    for (int i = 0; i < kMaxInserts * 2; ++i) out[i] = meters_[i].exchange(0.f, std::memory_order_relaxed);
}

// ---------------------------------------------------------------------------
// Audio thread
// ---------------------------------------------------------------------------

void Engine::releaseSequenced() {
    for (auto& v : voices_) {
        if (v.active && !v.live) v.release();
    }
}

void Engine::handle(const Command& cmd) {
    switch (cmd.type) {
        case CommandType::Play:
            playing_ = true;
            tick_ = current_->mode == PlayMode::Song ? startTick_ : patternStart_;
            break;
        case CommandType::Stop:
            playing_ = false;
            tick_ = current_->mode == PlayMode::Song ? startTick_ : patternStart_;
            releaseSequenced();
            break;
        case CommandType::SetPosition:
            startTick_ = static_cast<double>(cmd.t);
            if (current_->mode == PlayMode::Song) {
                tick_ = startTick_;
                if (playing_) releaseSequenced();
            }
            break;
        case CommandType::SetPatternPosition:
            patternStart_ = static_cast<double>(cmd.t);
            if (current_->mode == PlayMode::Pattern) {
                tick_ = patternStart_;
                if (playing_) releaseSequenced();
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
            trigger({0, cmd.a, cmd.b, cmd.c, -1, 0.f, 0.f, 0.5f, 0});
            break;
        case CommandType::NoteOff:
            releaseLive(cmd.a, cmd.b);
            break;
        case CommandType::PadHold: {
            trigger({0, cmd.a, cmd.b, cmd.c, -1, 0.f, 0.f, 0.5f, 0});
            HeldPad* slot = nullptr;
            for (auto& p : heldPads_) if (p.held && p.channel == cmd.a && p.pitch == cmd.b) slot = &p;
            if (slot == nullptr) {
                for (auto& p : heldPads_) if (!p.held) { slot = &p; break; }
            }
            if (slot != nullptr) *slot = {true, cmd.a, cmd.b, cmd.c};
            break;
        }
        case CommandType::PadRelease:
            for (auto& p : heldPads_) if (p.held && p.channel == cmd.a && p.pitch == cmd.b) p.held = false;
            releaseLive(cmd.a, cmd.b);
            break;
        case CommandType::SetRepeatTicks:
            repeatTicks_ = cmd.a;
            break;
        case CommandType::AllNotesOff:
            for (auto& p : heldPads_) p.held = false;
            for (auto& v : voices_) if (v.active) v.startFade(0.01f, static_cast<float>(sampleRate_));
            break;
        case CommandType::SetMasterGain:
            masterGain_ = cmd.f;
            break;
        case CommandType::SetStemInsert:
            stemInsert_ = cmd.a;
            break;
    }
}

int32_t Engine::swingOffset(int32_t tick, int32_t stepTicks) const {
    // Delay every off-beat 16th. Full swing = triplet shuffle (1/3 of a step). 16th grids only.
    if (swing_ <= 0.f || stepTicks != kTicksPerStep) return 0;
    if (tick % (kTicksPerStep * 2) != kTicksPerStep) return 0;
    return static_cast<int32_t>(std::lround(swing_ * kTicksPerStep / 3.0f));
}

void Engine::addEvent(const Event& e) {
    if (eventCount_ >= kMaxEventsPerSubBlock) return;
    events_[static_cast<size_t>(eventCount_++)] = e;
}

void Engine::timelineBounds(double& start, double& end) const {
    const Snapshot& s = *current_;
    if (s.mode == PlayMode::Song) {
        if (s.loopStart >= 0 && s.loopEnd > s.loopStart) {
            start = static_cast<double>(s.loopStart);
            end = static_cast<double>(s.loopEnd);
        } else {
            start = 0.0;
            end = static_cast<double>(s.songLength);
        }
    } else {
        const double len = s.lengthTicks();
        if (s.patternLoopStart >= 0 && s.patternLoopEnd > s.patternLoopStart && s.patternLoopStart < len) {
            start = static_cast<double>(s.patternLoopStart);
            end = std::min(static_cast<double>(s.patternLoopEnd), len);
        } else {
            start = 0.0;
            end = len;
        }
    }
}

int32_t Engine::makeSegments(double ticks, int32_t n, Segment* segs) {
    double start, end;
    timelineBounds(start, end);
    if (tick_ >= end || tick_ < 0.0) tick_ = start;
    const double from = tick_;
    const double to = tick_ + ticks;
    if (to <= end) {
        segs[0] = {from, to, 0, n};
        return 1;
    }
    const auto split = std::clamp(static_cast<int32_t>(std::lround((end - from) * framesPerTick_)), 0, n);
    segs[0] = {from, end, 0, split};
    segs[1] = {start, start + (to - end), split, n};
    return 2;
}

void Engine::collectPatternNotes(const PatternData& p, double base, double clipStart, double clipEnd, const Segment& seg) {
    const double lo = std::max(seg.from, clipStart);
    const double hi = std::min(seg.to, clipEnd);
    if (hi <= lo) return;
    const double len = p.lengthTicks;
    const Snapshot& snap = *current_;
    const size_t channelCount = std::min(p.notes.size(), snap.channels.size());
    for (size_t c = 0; c < channelCount; ++c) {
        if (!snap.channels[c].audible) continue;
        for (const NoteEvent& n : p.notes[c]) {
            double s = n.startTick + swingOffset(n.startTick, p.stepTicks);
            if (s >= len) s -= len;
            const double k0 = std::ceil((lo - base - s) / len);
            const double k1 = std::ceil((hi - base - s) / len) - 1.0;
            for (double k = k0; k <= k1; k += 1.0) {
                const double t = base + k * len + s;
                if (t < lo || t >= hi) continue;
                const double lengthTicks = std::min(static_cast<double>(n.lengthTicks), clipEnd - t);
                const auto offset = static_cast<int32_t>(std::floor(seg.frameStart + (t - seg.from) * framesPerTick_ + 1e-6));
                const auto lengthFrames = static_cast<int64_t>(std::max(1.0, lengthTicks * framesPerTick_));
                addEvent({offset, static_cast<int32_t>(c), n.pitch, n.velocity, lengthFrames, n.pan, n.fineCents, n.release, n.flags});
            }
        }
    }
}

void Engine::collectEvents(const Segment& seg) {
    const Snapshot& s = *current_;
    if (s.patterns.empty()) return;
    if (s.mode == PlayMode::Pattern) {
        const PatternData& p = s.patterns[static_cast<size_t>(s.currentPattern)];
        collectPatternNotes(p, 0.0, 0.0, p.lengthTicks, seg);
        return;
    }
    for (const ClipData& c : s.clips) {
        if (c.type != ClipType::Pattern || c.pattern < 0 || c.pattern >= static_cast<int32_t>(s.patterns.size())) continue;
        const double start = static_cast<double>(c.start);
        collectPatternNotes(s.patterns[static_cast<size_t>(c.pattern)], start - static_cast<double>(c.offset), start,
                            start + static_cast<double>(c.length), seg);
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
            if (p.held) addEvent({offset, p.channel, p.pitch, p.velocity, lengthFrames, 0.f, 0.f, 0.5f, 0});
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
    const float semis = static_cast<float>(e.pitch) + e.fineCents / 100.f;
    const bool melodic = ch.type == InstrumentType::Bass808 || ch.type == InstrumentType::Synth;
    const float tuneSemis = ch.type == InstrumentType::Bass808 ? ch.params[bass_param::kTune] : 0.f;

    // FL-style slide note: glide the notes already playing on this channel.
    if ((e.flags & kNoteSlide) != 0 && melodic) {
        bool slid = false;
        for (auto& v : voices_) {
            if (!v.active || v.channel != e.channel || v.releasing || v.fadeStep > 0.f) continue;
            v.targetFreq = midiToHz(semis + tuneSemis);
            v.slideSeconds = std::max(0.005f, static_cast<float>(untilOff > 0 ? untilOff : sr / 8) / sr);
            v.pitch = e.pitch;
            if (!v.live && untilOff > v.framesUntilOff) v.framesUntilOff = untilOff;
            slid = true;
        }
        if (slid) return;
    }

    if (ch.type == InstrumentType::Bass808) {
        const float target = midiToHz(semis + tuneSemis);
        const bool glide = ch.params[bass_param::kGlide] > 0.001f;
        for (auto& v : voices_) {
            if (!v.active || v.channel != e.channel || v.type != InstrumentType::Bass808) continue;
            if (v.fadeStep > 0.f) continue;
            const bool held = !v.releasing && (v.live || v.framesUntilOff > 0);
            if (glide && held) {
                // Overlapping 808 notes slide into each other (legato glide).
                v.targetFreq = target;
                v.slideSeconds = 0.f;
                v.pitch = e.pitch;
                v.live = live;
                v.framesUntilOff = untilOff;
                return;
            }
            v.startFade(0.005f, sr);  // 808 is monophonic per channel
        }
    }

    if (ch.type == InstrumentType::Drum || ch.type == InstrumentType::Sampler) {
        const auto choke = static_cast<int32_t>(ch.params[drum_param::kChokeGroup]);
        if (choke > 0) {
            for (auto& v : voices_) {
                if (v.active && v.chokeGroup == choke &&
                    (v.type == InstrumentType::Drum || v.type == InstrumentType::Sampler)) {
                    v.startFade(0.008f, sr);
                }
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
    panGains(e.pan, v->panL, v->panR);
    v->releaseScale = std::max(0.05f, e.release * 2.f);
    v->live = live;
    v->framesUntilOff = untilOff;
    v->order = ++voiceCounter_;

    switch (ch.type) {
        case InstrumentType::Drum:
        case InstrumentType::Sampler: {
            const SampleBuffer* buf = ch.type == InstrumentType::Drum ? kit_.get(ch.sound) : ch.sample.get();
            if (buf == nullptr) {
                v->active = false;
                return;
            }
            v->buffer = buf;
            v->chokeGroup = static_cast<int32_t>(ch.params[drum_param::kChokeGroup]);
            const float root = ch.type == InstrumentType::Sampler ? ch.params[3] : 60.f;
            const float rel = semis - root + ch.params[drum_param::kTune];
            v->rate = static_cast<double>(buf->sampleRate) / sampleRate_ * std::pow(2.0, rel / 12.0);
            break;
        }
        case InstrumentType::Bass808: {
            const float f = midiToHz(semis + tuneSemis);
            v->freq = f;
            v->targetFreq = f;
            v->env = 1.f;
            break;
        }
        case InstrumentType::Synth: {
            const float f = midiToHz(semis);
            v->freq = f;
            v->targetFreq = f;
            v->phase2 = 0.25;  // avoid the two oscillators cancelling at start
            break;
        }
    }
}

void Engine::renderVoices(int32_t from, int32_t to) {
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
        // A sample removed from the project: stop before its buffer can be freed.
        if (v.type == InstrumentType::Sampler && v.buffer != ch.sample.get()) {
            v.active = false;
            continue;
        }
        float* bl = insIn_[ch.insert][0];
        float* br = insIn_[ch.insert][1];
        for (int32_t i = from; i < to && v.active; ++i) {
            if (v.framesUntilOff > 0 && --v.framesUntilOff == 0) v.release();
            float l, r;
            v.render(ch, sr, l, r);
            bl[i] += l * ch.gainL;
            br[i] += r * ch.gainR;
        }
    }
}

void Engine::mixAudioClips(const Segment& seg) {
    const Snapshot& s = *current_;
    const double secondsPerTick = framesPerTick_ / sampleRate_;
    for (const ClipData& c : s.clips) {
        if (c.type != ClipType::Audio || !c.sample) continue;
        const double start = static_cast<double>(c.start);
        const double lo = std::max(seg.from, start);
        const double hi = std::min(seg.to, start + static_cast<double>(c.length));
        if (hi <= lo) continue;
        const SampleBuffer& b = *c.sample;
        const size_t ch = static_cast<size_t>(b.channels);
        const double frames = static_cast<double>(b.frames());
        const int32_t f0 = std::max(seg.frameStart, static_cast<int32_t>(std::ceil(seg.frameStart + (lo - seg.from) * framesPerTick_ - 1e-9)));
        const int32_t f1 = std::min(seg.frameEnd, static_cast<int32_t>(std::ceil(seg.frameStart + (hi - seg.from) * framesPerTick_ - 1e-9)));
        float* bl = insIn_[c.insert][0];
        float* br = insIn_[c.insert][1];
        for (int32_t f = f0; f < f1; ++f) {
            const double t = seg.from + (f - seg.frameStart) / framesPerTick_;
            const double src = (t - start + static_cast<double>(c.offset)) * secondsPerTick * b.sampleRate;
            if (src < 0.0 || src >= frames - 1.0) continue;
            const auto i = static_cast<size_t>(src);
            const auto frac = static_cast<float>(src - static_cast<double>(i));
            const float* d = b.data.data();
            const float l = d[i * ch] + (d[(i + 1) * ch] - d[i * ch]) * frac;
            const float r = ch >= 2 ? d[i * ch + 1] + (d[(i + 1) * ch + 1] - d[i * ch + 1]) * frac : l;
            bl[f] += l * c.gain;
            br[f] += r * c.gain;
        }
    }
}

void Engine::applyAutomation(double tick) {
    Snapshot& s = *current_;
    if (s.automation.empty()) return;
    // Reset every automated value to its base, then apply clips playing now.
    for (const auto& a : s.automation) {
        switch (a.target) {
            case AutoTarget::MixerVolume: if (a.a >= 0 && a.a < kMaxInserts) s.inserts[a.a].liveVolume = s.inserts[a.a].volume; break;
            case AutoTarget::MixerPan: if (a.a >= 0 && a.a < kMaxInserts) s.inserts[a.a].livePan = s.inserts[a.a].pan; break;
            case AutoTarget::ChannelVolume:
            case AutoTarget::ChannelPan:
                if (a.a >= 0 && a.a < static_cast<int32_t>(s.channels.size())) {
                    auto& ch = s.channels[static_cast<size_t>(a.a)];
                    ch.liveVolume = ch.volume;
                    ch.livePan = ch.pan;
                }
                break;
            case AutoTarget::EffectParam:
                if (a.a >= 0 && a.a < kMaxInserts && a.b >= 0 && a.b < s.inserts[a.a].slotCount && a.c >= 0 && a.c < kMaxEffectParams) {
                    auto& slot = s.inserts[a.a].slots[a.b];
                    slot.live[a.c] = slot.params[a.c];
                }
                break;
        }
    }
    for (const auto& a : s.automation) {
        const double rel = tick - static_cast<double>(a.start);
        if (rel < 0.0 || rel >= static_cast<double>(a.length)) continue;
        // Linear interpolation between points.
        float v = a.values.front();
        const size_t n = a.ticks.size();
        if (rel >= a.ticks[n - 1]) {
            v = a.values[n - 1];
        } else {
            for (size_t i = 0; i + 1 < n; ++i) {
                if (rel >= a.ticks[i] && rel < a.ticks[i + 1]) {
                    const double span = std::max(1, a.ticks[i + 1] - a.ticks[i]);
                    v = a.values[i] + static_cast<float>((rel - a.ticks[i]) / span) * (a.values[i + 1] - a.values[i]);
                    break;
                }
            }
        }
        v = std::clamp(v, 0.f, 1.f);
        switch (a.target) {
            case AutoTarget::MixerVolume: if (a.a >= 0 && a.a < kMaxInserts) s.inserts[a.a].liveVolume = v; break;
            case AutoTarget::MixerPan: if (a.a >= 0 && a.a < kMaxInserts) s.inserts[a.a].livePan = v * 2.f - 1.f; break;
            case AutoTarget::ChannelVolume:
                if (a.a >= 0 && a.a < static_cast<int32_t>(s.channels.size())) s.channels[static_cast<size_t>(a.a)].liveVolume = v;
                break;
            case AutoTarget::ChannelPan:
                if (a.a >= 0 && a.a < static_cast<int32_t>(s.channels.size())) s.channels[static_cast<size_t>(a.a)].livePan = v * 2.f - 1.f;
                break;
            case AutoTarget::EffectParam:
                if (a.a >= 0 && a.a < kMaxInserts && a.b >= 0 && a.b < s.inserts[a.a].slotCount && a.c >= 0 && a.c < kMaxEffectParams) {
                    s.inserts[a.a].slots[a.b].live[a.c] =
                        a.log && a.min > 0.f ? a.min * std::pow(a.max / a.min, v) : a.min + (a.max - a.min) * v;
                }
                break;
        }
    }
    for (auto& ch : s.channels) updateChannelGain(ch);
}

void Engine::processMixer(float* out, int32_t n) {
    Snapshot& s = *current_;
    const EffectContext ctx{static_cast<float>(sampleRate_), tempo_};
    for (int32_t oi = 0; oi < s.orderCount; ++oi) {
        const int32_t i = s.order[oi];
        InsertData& ins = s.inserts[i];
        float* l = insIn_[i][0];
        float* r = insIn_[i][1];
        for (int32_t k = 0; k < ins.slotCount; ++k) {
            EffectData& slot = ins.slots[k];
            if (!slot.enabled || !slot.fx) continue;
            const float* scL = nullptr;
            const float* scR = nullptr;
            if (slot.sidechain > 0 && slot.sidechain < kMaxInserts) {
                scL = insOut_[slot.sidechain][0];
                scR = insOut_[slot.sidechain][1];
            }
            if (slot.mix >= 0.999f) {
                slot.fx->process(l, r, n, slot.live, scL, scR, ctx);
            } else {
                std::memcpy(dry_[0], l, sizeof(float) * static_cast<size_t>(n));
                std::memcpy(dry_[1], r, sizeof(float) * static_cast<size_t>(n));
                slot.fx->process(l, r, n, slot.live, scL, scR, ctx);
                const float wet = slot.mix, dry = 1.f - slot.mix;
                for (int32_t f = 0; f < n; ++f) {
                    l[f] = l[f] * wet + dry_[0][f] * dry;
                    r[f] = r[f] * wet + dry_[1][f] * dry;
                }
            }
        }
        const float g = ins.audible ? faderGain(ins.liveVolume) : 0.f;
        float pl, pr;
        panGains(ins.livePan, pl, pr);
        const float gl = g * pl, gr = g * pr;
        float peakL = 0.f, peakR = 0.f;
        float* ol = insOut_[i][0];
        float* orr = insOut_[i][1];
        for (int32_t f = 0; f < n; ++f) {
            ol[f] = l[f] * gl;
            orr[f] = r[f] * gr;
            peakL = std::max(peakL, std::fabs(ol[f]));
            peakR = std::max(peakR, std::fabs(orr[f]));
        }
        if (peakL > meters_[i * 2].load(std::memory_order_relaxed)) meters_[i * 2].store(peakL, std::memory_order_relaxed);
        if (peakR > meters_[i * 2 + 1].load(std::memory_order_relaxed)) meters_[i * 2 + 1].store(peakR, std::memory_order_relaxed);

        if (i != 0) {
            if (ins.toMaster) {
                for (int32_t f = 0; f < n; ++f) {
                    insIn_[0][0][f] += ol[f];
                    insIn_[0][1][f] += orr[f];
                }
            }
            for (const auto& snd : ins.sends) {
                for (int32_t f = 0; f < n; ++f) {
                    insIn_[snd.target][0][f] += ol[f] * snd.level;
                    insIn_[snd.target][1][f] += orr[f] * snd.level;
                }
            }
        }
    }
    const int32_t src = (stemInsert_ >= 0 && stemInsert_ < kMaxInserts) ? stemInsert_ : 0;
    for (int32_t f = 0; f < n; ++f) {
        out[2 * f] = insOut_[src][0][f];
        out[2 * f + 1] = insOut_[src][1][f];
    }
}

void Engine::render(float* out, int32_t frames) {
    // Adopt the newest snapshot, if any. The old one is freed on the control thread.
    if (Snapshot* next = pending_.exchange(nullptr, std::memory_order_acq_rel)) {
        if (!retired_.push(current_)) {
            // Queue full: control thread has not collected garbage. Keep the old
            // snapshot (never free on the audio thread) and retry next block.
            Snapshot* expected = nullptr;
            if (!pending_.compare_exchange_strong(expected, next)) retired_.push(next);
        } else {
            current_ = next;
            if (current_->mode != lastMode_) {
                // PAT <-> SONG switch: restart from the right place.
                lastMode_ = current_->mode;
                tick_ = lastMode_ == PlayMode::Song ? startTick_ : patternStart_;
                releaseSequenced();
            }
        }
    }

    Command cmd;
    while (commands_.pop(cmd)) handle(cmd);

    for (int32_t done = 0; done < frames;) {
        const int32_t n = std::min(kSubBlockFrames, frames - done);
        const double ticks = n / framesPerTick_;
        eventCount_ = 0;

        Segment segs[2];
        int32_t segCount = 0;
        if (playing_) {
            segCount = makeSegments(ticks, n, segs);
            for (int32_t k = 0; k < segCount; ++k) collectEvents(segs[k]);
            collectRepeatEvents(segs[0].from, segs[0].from + ticks);
            if (current_->mode == PlayMode::Song) applyAutomation(tick_);
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

        for (int i = 0; i < kMaxInserts; ++i) {
            std::memset(insIn_[i][0], 0, sizeof(float) * static_cast<size_t>(n));
            std::memset(insIn_[i][1], 0, sizeof(float) * static_cast<size_t>(n));
        }

        int32_t pos = 0;
        for (int32_t i = 0; i < eventCount_; ++i) {
            const Event& e = events_[static_cast<size_t>(i)];
            const int32_t at = std::clamp(e.offset, 0, n - 1);
            renderVoices(pos, at);
            trigger(e);
            pos = at;
        }
        renderVoices(pos, n);

        if (playing_ && current_->mode == PlayMode::Song) {
            for (int32_t k = 0; k < segCount; ++k) mixAudioClips(segs[k]);
        }

        processMixer(out + static_cast<size_t>(done) * 2, n);

        if (playing_) {
            tick_ = segs[segCount - 1].to;
        }
        freeTick_ += ticks;
        if (freeTick_ >= kFreeClockWrap) freeTick_ -= kFreeClockWrap;
        done += n;
    }

    float peak = 0.f;
    const bool stem = stemInsert_ >= 0;
    for (int32_t i = 0; i < frames * 2; ++i) {
        const float y = stem ? out[i] : softClip(out[i] * masterGain_);
        out[i] = y;
        peak = std::max(peak, std::fabs(y));
    }
    if (peak > peak_.load(std::memory_order_relaxed)) peak_.store(peak, std::memory_order_relaxed);
    positionTicks_.store(static_cast<float>(tick_), std::memory_order_relaxed);
    playingFlag_.store(playing_, std::memory_order_relaxed);
}

}  // namespace jb
