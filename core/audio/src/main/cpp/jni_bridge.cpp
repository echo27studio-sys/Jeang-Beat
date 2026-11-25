// JNI bridge between Kotlin (com.jeangbeat.core.audio.NativeEngine) and the C++ engine.
#include <jni.h>

#include <algorithm>
#include <memory>

#include "Engine.h"
#include "OboeHost.h"

namespace {

struct Handle {
    jb::Engine engine;
    jb::OboeHost host{engine};
};

Handle* from(jlong ptr) { return reinterpret_cast<Handle*>(ptr); }

// RAII helpers for primitive arrays (read-only access, no copy back).
struct Ints {
    JNIEnv* env; jintArray arr; jint* data; jsize size;
    Ints(JNIEnv* e, jintArray a) : env(e), arr(a), data(a ? e->GetIntArrayElements(a, nullptr) : nullptr), size(a ? e->GetArrayLength(a) : 0) {}
    ~Ints() { if (data) env->ReleaseIntArrayElements(arr, data, JNI_ABORT); }
};
struct Floats {
    JNIEnv* env; jfloatArray arr; jfloat* data; jsize size;
    Floats(JNIEnv* e, jfloatArray a) : env(e), arr(a), data(a ? e->GetFloatArrayElements(a, nullptr) : nullptr), size(a ? e->GetArrayLength(a) : 0) {}
    ~Floats() { if (data) env->ReleaseFloatArrayElements(arr, data, JNI_ABORT); }
};

}  // namespace

#define JB_FN(name) Java_com_jeangbeat_core_audio_NativeEngine_##name

extern "C" {

JNIEXPORT jlong JNICALL JB_FN(nativeCreate)(JNIEnv*, jobject) { return reinterpret_cast<jlong>(new Handle()); }

JNIEXPORT void JNICALL JB_FN(nativeDestroy)(JNIEnv*, jobject, jlong ptr) {
    Handle* h = from(ptr);
    if (h == nullptr) return;
    h->host.stop();
    delete h;
}

JNIEXPORT jboolean JNICALL JB_FN(nativeStartStream)(JNIEnv*, jobject, jlong ptr) {
    return from(ptr)->host.start() ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT void JNICALL JB_FN(nativeStopStream)(JNIEnv*, jobject, jlong ptr) { from(ptr)->host.stop(); }
JNIEXPORT jint JNICALL JB_FN(nativeSampleRate)(JNIEnv*, jobject, jlong ptr) { return from(ptr)->host.sampleRate(); }
JNIEXPORT void JNICALL JB_FN(nativeSetSampleRate)(JNIEnv*, jobject, jlong ptr, jint rate) { from(ptr)->engine.setSampleRate(rate); }
JNIEXPORT jdouble JNICALL JB_FN(nativeLatencyMillis)(JNIEnv*, jobject, jlong ptr) { return from(ptr)->host.outputLatencyMillis(); }

JNIEXPORT void JNICALL JB_FN(nativePlay)(JNIEnv*, jobject, jlong ptr) { from(ptr)->engine.play(); }
JNIEXPORT void JNICALL JB_FN(nativeStop)(JNIEnv*, jobject, jlong ptr) { from(ptr)->engine.stop(); }
JNIEXPORT void JNICALL JB_FN(nativeSetPosition)(JNIEnv*, jobject, jlong ptr, jlong tick) { from(ptr)->engine.setPosition(tick); }
JNIEXPORT void JNICALL JB_FN(nativeSetPatternPosition)(JNIEnv*, jobject, jlong ptr, jlong tick) { from(ptr)->engine.setPatternPosition(tick); }
JNIEXPORT void JNICALL JB_FN(nativeSetTempo)(JNIEnv*, jobject, jlong ptr, jfloat bpm) { from(ptr)->engine.setTempo(bpm); }
JNIEXPORT void JNICALL JB_FN(nativeSetSwing)(JNIEnv*, jobject, jlong ptr, jfloat swing) { from(ptr)->engine.setSwing(swing); }
JNIEXPORT void JNICALL JB_FN(nativeNoteOn)(JNIEnv*, jobject, jlong ptr, jint ch, jint pitch, jint vel) { from(ptr)->engine.noteOn(ch, pitch, vel); }
JNIEXPORT void JNICALL JB_FN(nativeNoteOff)(JNIEnv*, jobject, jlong ptr, jint ch, jint pitch) { from(ptr)->engine.noteOff(ch, pitch); }
JNIEXPORT void JNICALL JB_FN(nativePadHold)(JNIEnv*, jobject, jlong ptr, jint ch, jint pitch, jint vel) { from(ptr)->engine.padHold(ch, pitch, vel); }
JNIEXPORT void JNICALL JB_FN(nativePadRelease)(JNIEnv*, jobject, jlong ptr, jint ch, jint pitch) { from(ptr)->engine.padRelease(ch, pitch); }
JNIEXPORT void JNICALL JB_FN(nativeSetRepeatTicks)(JNIEnv*, jobject, jlong ptr, jint ticks) { from(ptr)->engine.setRepeatTicks(ticks); }
JNIEXPORT void JNICALL JB_FN(nativeAllNotesOff)(JNIEnv*, jobject, jlong ptr) { from(ptr)->engine.allNotesOff(); }
JNIEXPORT void JNICALL JB_FN(nativeSetMasterGain)(JNIEnv*, jobject, jlong ptr, jfloat gain) { from(ptr)->engine.setMasterGain(gain); }
JNIEXPORT void JNICALL JB_FN(nativeSetStemInsert)(JNIEnv*, jobject, jlong ptr, jint insert) { from(ptr)->engine.setStemInsert(insert); }

JNIEXPORT void JNICALL JB_FN(nativeLoadSample)(JNIEnv* env, jobject, jlong ptr, jint id, jfloatArray data, jint channels, jfloat rate) {
    Floats f(env, data);
    const jint c = channels > 0 ? channels : 1;
    from(ptr)->engine.loadSample(id, f.data, f.size / c, c, rate);
}
JNIEXPORT void JNICALL JB_FN(nativeUnloadSample)(JNIEnv*, jobject, jlong ptr, jint id) { from(ptr)->engine.unloadSample(id); }

JNIEXPORT void JNICALL JB_FN(nativeBeginSnapshot)(JNIEnv*, jobject, jlong ptr, jint mode, jint currentPattern, jlong songLength,
                                                  jlong loopStart, jlong loopEnd, jlong patternLoopStart, jlong patternLoopEnd) {
    from(ptr)->engine.beginSnapshot(mode, currentPattern, songLength, loopStart, loopEnd, patternLoopStart, patternLoopEnd);
}

JNIEXPORT jint JNICALL JB_FN(nativeAddChannel)(JNIEnv* env, jobject, jlong ptr, jint type, jint sound, jint sampleId,
                                               jfloatArray params, jfloat volume, jfloat pan, jboolean muted, jboolean solo,
                                               jint insert) {
    Floats p(env, params);
    return from(ptr)->engine.addChannel(type, sound, sampleId, p.data, p.size, volume, pan, muted == JNI_TRUE, solo == JNI_TRUE, insert);
}

JNIEXPORT jint JNICALL JB_FN(nativeAddPattern)(JNIEnv*, jobject, jlong ptr, jint lengthTicks, jint stepTicks) {
    return from(ptr)->engine.addPattern(lengthTicks, stepTicks);
}

JNIEXPORT void JNICALL JB_FN(nativeSetPatternNotes)(JNIEnv* env, jobject, jlong ptr, jint pattern, jint channel, jintArray ints,
                                                    jfloatArray floats) {
    Ints i(env, ints);
    Floats f(env, floats);
    const jint count = std::min(i.size / 5, f.size / 3);
    from(ptr)->engine.setPatternNotes(pattern, channel, i.data, f.data, count);
}

JNIEXPORT void JNICALL JB_FN(nativeAddPatternClip)(JNIEnv*, jobject, jlong ptr, jlong start, jlong length, jlong offset, jint pattern) {
    from(ptr)->engine.addPatternClip(start, length, offset, pattern);
}

JNIEXPORT void JNICALL JB_FN(nativeAddAudioClip)(JNIEnv*, jobject, jlong ptr, jlong start, jlong length, jlong offset, jint sampleId,
                                                 jfloat gain, jint insert) {
    from(ptr)->engine.addAudioClip(start, length, offset, sampleId, gain, insert);
}

JNIEXPORT void JNICALL JB_FN(nativeAddAutomation)(JNIEnv* env, jobject, jlong ptr, jint target, jint a, jint b, jint c, jfloat min,
                                                  jfloat max, jboolean log, jlong start, jlong length, jintArray ticks,
                                                  jfloatArray values) {
    Ints t(env, ticks);
    Floats v(env, values);
    from(ptr)->engine.addAutomation(target, a, b, c, min, max, log == JNI_TRUE, start, length, t.data, v.data, std::min(t.size, v.size));
}

JNIEXPORT void JNICALL JB_FN(nativeSetInsert)(JNIEnv* env, jobject, jlong ptr, jint index, jfloat volume, jfloat pan, jboolean muted,
                                              jboolean solo, jboolean toMaster, jintArray targets, jfloatArray levels) {
    Ints t(env, targets);
    Floats l(env, levels);
    from(ptr)->engine.setInsert(index, volume, pan, muted == JNI_TRUE, solo == JNI_TRUE, toMaster == JNI_TRUE, t.data, l.data,
                                std::min(t.size, l.size));
}

JNIEXPORT void JNICALL JB_FN(nativeAddEffect)(JNIEnv* env, jobject, jlong ptr, jint insert, jint slotId, jint type, jboolean enabled,
                                              jfloat mix, jfloatArray params) {
    Floats p(env, params);
    from(ptr)->engine.addEffect(insert, slotId, type, enabled == JNI_TRUE, mix, p.data, p.size);
}

JNIEXPORT void JNICALL JB_FN(nativeCommitSnapshot)(JNIEnv*, jobject, jlong ptr) { from(ptr)->engine.commitSnapshot(); }

JNIEXPORT jfloat JNICALL JB_FN(nativePositionTicks)(JNIEnv*, jobject, jlong ptr) { return from(ptr)->engine.positionTicks(); }
JNIEXPORT jboolean JNICALL JB_FN(nativeIsPlaying)(JNIEnv*, jobject, jlong ptr) { return from(ptr)->engine.isPlaying() ? JNI_TRUE : JNI_FALSE; }
JNIEXPORT jfloat JNICALL JB_FN(nativeTakePeak)(JNIEnv*, jobject, jlong ptr) { return from(ptr)->engine.takePeak(); }

JNIEXPORT void JNICALL JB_FN(nativeTakeMeters)(JNIEnv* env, jobject, jlong ptr, jfloatArray out) {
    float meters[jb::kMaxInserts * 2];
    from(ptr)->engine.takeMeters(meters);
    const jsize n = std::min<jsize>(env->GetArrayLength(out), jb::kMaxInserts * 2);
    env->SetFloatArrayRegion(out, 0, n, meters);
}

// Offline rendering (export): fills `out` with `frames` stereo frames. Only for
// engines whose audio stream was never started.
JNIEXPORT void JNICALL JB_FN(nativeRender)(JNIEnv* env, jobject, jlong ptr, jfloatArray out, jint frames) {
    jfloat* data = env->GetFloatArrayElements(out, nullptr);
    const jint n = std::min(frames, env->GetArrayLength(out) / 2);
    from(ptr)->engine.render(data, n);
    env->ReleaseFloatArrayElements(out, data, 0);
}

}  // extern "C"
